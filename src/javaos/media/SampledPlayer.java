package javaos.media;

import java.io.File;
import java.io.IOException;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;

/**
 * Plays WAV, AU and AIFF by pushing PCM at a {@link SourceDataLine} from a
 * private thread.
 *
 * <p>A {@code Clip} would have been half the code, but a Clip holds the whole
 * file in memory and will not say what the signal is doing. Feeding the line by
 * hand costs a pump thread and buys the peak meter, which reads the same buffer
 * on its way out.
 */
public final class SampledPlayer implements Player {

    /** 100ms of audio in flight: small enough that pause and seek feel immediate. */
    private static final double BUFFER_SECONDS = 0.1;

    private final File file;
    private final AudioFormat playFormat;
    private final long durationMicros;
    private final String description;
    private final Levels levels;
    private final Object lock = new Object();

    private volatile SourceDataLine line;
    private volatile Thread pump;
    private volatile boolean playing;
    private volatile boolean closed;
    private volatile long baseMicros;
    private volatile long framesWritten;
    private volatile float volume = 1f;
    private volatile Runnable finished = () -> { };

    public SampledPlayer(File file) throws IOException, UnsupportedAudioFileException,
            LineUnavailableException {
        this.file = file;

        AudioFileFormat fileFormat = AudioSystem.getAudioFileFormat(file);
        AudioFormat source = fileFormat.getFormat();
        this.playFormat = decodedForm(source);
        if (!source.matches(playFormat)
                && !AudioSystem.isConversionSupported(playFormat, source)) {
            throw new UnsupportedAudioFileException(
                    source.getEncoding() + " audio cannot be decoded by the JDK");
        }
        if (!AudioSystem.isLineSupported(new DataLine.Info(SourceDataLine.class, playFormat))) {
            throw new LineUnavailableException(
                    "no output line accepts " + playFormat.getSampleRate() + " Hz audio");
        }

        long frames = fileFormat.getFrameLength();
        this.durationMicros = frames == AudioSystem.NOT_SPECIFIED || source.getFrameRate() <= 0
                ? -1
                : (long) (frames / (double) source.getFrameRate() * 1_000_000L);
        this.description = describe(source, fileFormat);
        this.levels = new Levels(playFormat.getChannels());
    }

    /** Signed 16-bit PCM at the source rate: what every output line understands. */
    private static AudioFormat decodedForm(AudioFormat source) {
        int channels = source.getChannels() == AudioSystem.NOT_SPECIFIED
                ? 2 : source.getChannels();
        float rate = source.getSampleRate() == AudioSystem.NOT_SPECIFIED
                ? 44100f : source.getSampleRate();
        return new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 16, channels,
                channels * 2, rate, false);
    }

    private static String describe(AudioFormat source, AudioFileFormat fileFormat) {
        String channels = switch (source.getChannels()) {
            case 1 -> "mono";
            case 2 -> "stereo";
            default -> source.getChannels() + " channels";
        };
        int bits = source.getSampleSizeInBits();
        return "%s  %s  %.0f Hz  %s".formatted(
                fileFormat.getType(),
                bits == AudioSystem.NOT_SPECIFIED ? source.getEncoding().toString() : bits + "-bit",
                source.getSampleRate(),
                channels);
    }

    // ---- transport -----------------------------------------------------

    @Override public void play() {
        synchronized (lock) {
            if (closed || playing) {
                return;
            }
            playing = true;
            startPump(baseMicros);
        }
    }

    @Override public void pause() {
        synchronized (lock) {
            if (!playing) {
                return;
            }
            baseMicros = position();
            playing = false;
            stopPump();
        }
    }

    @Override public void stop() {
        synchronized (lock) {
            playing = false;
            stopPump();
            baseMicros = 0;
        }
        levels.silence();
    }

    @Override public void seek(long micros) {
        synchronized (lock) {
            boolean wasPlaying = playing;
            stopPump();
            baseMicros = clamp(micros);
            if (wasPlaying) {
                startPump(baseMicros);
            }
        }
    }

    private long clamp(long micros) {
        long limit = durationMicros < 0 ? Long.MAX_VALUE : durationMicros;
        return Math.max(0, Math.min(micros, limit));
    }

    @Override public boolean isPlaying() {
        return playing;
    }

    @Override public long position() {
        SourceDataLine open = line;
        if (!playing || open == null) {
            return baseMicros;
        }
        // Frames handed to the line, less the ones still queued inside it.
        int frameSize = playFormat.getFrameSize();
        long queued = frameSize <= 0 ? 0
                : (open.getBufferSize() - open.available()) / frameSize;
        long played = Math.max(0, framesWritten - queued);
        return clamp(baseMicros + (long) (played / (double) playFormat.getFrameRate() * 1e6));
    }

    @Override public long duration() {
        return durationMicros;
    }

    @Override public void setVolume(float fraction) {
        volume = Math.max(0f, Math.min(1f, fraction));
        applyVolume(line);
    }

    private void applyVolume(SourceDataLine target) {
        if (target == null || !target.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            return;
        }
        FloatControl gain = (FloatControl) target.getControl(FloatControl.Type.MASTER_GAIN);
        // Decibels, not amplitude: a linear slider on a linear gain sounds wrong.
        float db = volume <= 0.0001f
                ? gain.getMinimum()
                : (float) (20.0 * Math.log10(volume));
        gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db)));
    }

    @Override public String describe() {
        return description;
    }

    @Override public Levels levels() {
        return levels;
    }

    @Override public void onFinished(Runnable listener) {
        this.finished = listener == null ? () -> { } : listener;
    }

    @Override public void close() {
        closed = true;
        synchronized (lock) {
            playing = false;
            stopPump();
        }
    }

    // ---- the pump ------------------------------------------------------

    private void startPump(long fromMicros) {
        framesWritten = 0;
        Thread thread = new Thread(() -> run(fromMicros), "javaos-audio");
        thread.setDaemon(true);
        pump = thread;
        thread.start();
    }

    /** Ends the current run and waits for the thread to let go of the line. */
    private void stopPump() {
        Thread thread = pump;
        pump = null;
        SourceDataLine open = line;
        if (open != null) {
            open.stop();
            open.flush();
        }
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        levels.silence();
    }

    private void run(long fromMicros) {
        Thread self = Thread.currentThread();
        SourceDataLine output = null;
        try (AudioInputStream stream = openAt(fromMicros)) {
            output = AudioSystem.getSourceDataLine(playFormat);
            output.open(playFormat, bufferBytes());
            line = output;
            applyVolume(output);
            output.start();

            byte[] buffer = new byte[bufferBytes()];
            int read;
            while (pump == self && (read = stream.read(buffer, 0, buffer.length)) > 0) {
                meter(buffer, read);
                output.write(buffer, 0, read);
                framesWritten += read / Math.max(1, playFormat.getFrameSize());
            }
            if (pump == self) {
                output.drain();
                reachedEnd();
            }
        } catch (IOException | UnsupportedAudioFileException | LineUnavailableException e) {
            if (pump == self) {
                reachedEnd();
            }
        } finally {
            if (output != null) {
                output.stop();
                output.close();
            }
            if (line == output) {
                line = null;
            }
        }
    }

    private void reachedEnd() {
        playing = false;
        baseMicros = durationMicros < 0 ? 0 : durationMicros;
        levels.silence();
        Runnable listener = finished;
        java.awt.EventQueue.invokeLater(listener);
    }

    private int bufferBytes() {
        int frames = (int) (playFormat.getFrameRate() * BUFFER_SECONDS);
        return Math.max(4096, frames * Math.max(1, playFormat.getFrameSize()));
    }

    /** Reopens the file and skips to {@code micros}; the only way to seek a stream. */
    private AudioInputStream openAt(long micros)
            throws IOException, UnsupportedAudioFileException {
        AudioInputStream raw = AudioSystem.getAudioInputStream(file);
        AudioInputStream pcm = raw.getFormat().matches(playFormat)
                ? raw
                : AudioSystem.getAudioInputStream(playFormat, raw);
        if (micros > 0) {
            long skip = (long) (micros / 1e6 * playFormat.getFrameRate())
                    * playFormat.getFrameSize();
            long left = skip;
            while (left > 0) {
                long moved = pcm.skip(left);
                if (moved <= 0) {
                    break;
                }
                left -= moved;
            }
        }
        return pcm;
    }

    /** Peak per channel over one buffer of signed 16-bit little-endian frames. */
    private void meter(byte[] buffer, int length) {
        int channels = playFormat.getChannels();
        float[] peak = new float[channels];
        for (int i = 0; i + 1 < length; i += 2) {
            int sample = (short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8));
            int channel = (i / 2) % channels;
            peak[channel] = Math.max(peak[channel], Math.abs(sample) / 32768f);
        }
        for (int c = 0; c < channels; c++) {
            levels.hit(c, peak[c]);
        }
    }
}

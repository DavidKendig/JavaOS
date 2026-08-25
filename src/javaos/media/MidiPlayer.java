package javaos.media;

import java.io.File;
import java.io.IOException;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaEventListener;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiChannel;
import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Synthesizer;
import javax.sound.midi.Transmitter;

/**
 * Plays MIDI files through the synthesiser the JDK already carries.
 *
 * <p>The meter is not an approximation here: JavaOS sits its own
 * {@link Receiver} between the sequencer and the synthesiser and watches the
 * note-on velocities go past, so the sixteen bars are the sixteen MIDI channels
 * doing exactly what the file asked for.
 */
public final class MidiPlayer implements Player {

    /** The General MIDI channel count, and so the number of meter bars. */
    private static final int CHANNELS = 16;

    private final Sequencer sequencer;
    private final Synthesizer synthesizer;
    private final long durationMicros;
    private final String description;
    private final Levels levels = new Levels(CHANNELS);

    /** Notes currently down per channel, and the loudest of them. */
    private final int[] held = new int[CHANNELS];
    private final float[] loudest = new float[CHANNELS];

    private volatile float volume = 1f;
    private volatile Runnable finished = () -> { };
    private volatile boolean closed;

    public MidiPlayer(File file) throws IOException, InvalidMidiDataException,
            MidiUnavailableException {
        Sequence sequence = MidiSystem.getSequence(file);

        sequencer = MidiSystem.getSequencer(false);
        sequencer.open();
        sequencer.setSequence(sequence);

        synthesizer = openSynthesizer();
        wire(sequencer, synthesizer);

        sequencer.addMetaEventListener(new MetaEventListener() {
            /** 0x2F is End of Track: the only reliable end-of-sequence signal. */
            @Override public void meta(MetaMessage message) {
                if (message.getType() == 0x2F) {
                    reachedEnd();
                }
            }
        });

        durationMicros = sequencer.getMicrosecondLength();
        description = describe(sequence);
        setVolume(1f);
    }

    private static Synthesizer openSynthesizer() throws MidiUnavailableException {
        Synthesizer synth = MidiSystem.getSynthesizer();
        synth.open();
        return synth;
    }

    /**
     * Sequencer to synthesiser, through a receiver that reads the traffic on the
     * way past. Nothing is altered; the messages are forwarded untouched.
     */
    private void wire(Sequencer source, Synthesizer target) throws MidiUnavailableException {
        Receiver destination = target.getReceiver();
        Transmitter transmitter = source.getTransmitter();
        transmitter.setReceiver(new Receiver() {
            @Override public void send(MidiMessage message, long timeStamp) {
                observe(message);
                destination.send(message, timeStamp);
            }

            @Override public void close() {
                destination.close();
            }
        });
    }

    /**
     * Counts what each channel currently has down. The bar jumps to the
     * velocity of a new note and stays up until the channel's last note lifts,
     * so the meter shows notes ringing rather than the instants they began.
     */
    private void observe(MidiMessage message) {
        if (!(message instanceof ShortMessage note)) {
            return;
        }
        int channel = note.getChannel();
        switch (note.getCommand()) {
            case ShortMessage.NOTE_ON -> {
                // Velocity zero is a note-off wearing a note-on's clothes.
                if (note.getData2() > 0) {
                    held[channel]++;
                    loudest[channel] = Math.max(loudest[channel], note.getData2() / 127f);
                    levels.hit(channel, note.getData2() / 127f);
                    levels.hold(channel, loudest[channel]);
                } else {
                    lift(channel);
                }
            }
            case ShortMessage.NOTE_OFF -> lift(channel);
            case ShortMessage.CONTROL_CHANGE -> {
                // 120 is All Sound Off, 123 All Notes Off; both empty the channel.
                if (note.getData1() == 120 || note.getData1() == 123) {
                    held[channel] = 0;
                    loudest[channel] = 0f;
                    levels.hold(channel, 0f);
                }
            }
            default -> { }
        }
    }

    private void lift(int channel) {
        held[channel] = Math.max(0, held[channel] - 1);
        if (held[channel] == 0) {
            loudest[channel] = 0f;
            levels.hold(channel, 0f);
        }
    }

    private static String describe(Sequence sequence) {
        int tracks = sequence.getTracks().length;
        String division = sequence.getDivisionType() == Sequence.PPQ
                ? sequence.getResolution() + " ticks per beat"
                : sequence.getResolution() + " ticks per frame";
        return "MIDI  %d track%s  %s".formatted(tracks, tracks == 1 ? "" : "s", division);
    }

    // ---- transport -----------------------------------------------------

    @Override public void play() {
        if (!closed && !sequencer.isRunning()) {
            sequencer.start();
        }
    }

    @Override public void pause() {
        if (sequencer.isRunning()) {
            sequencer.stop();
            allNotesOff();
        }
    }

    @Override public void stop() {
        if (sequencer.isOpen()) {
            sequencer.stop();
            sequencer.setMicrosecondPosition(0);
        }
        allNotesOff();
        levels.silence();
    }

    @Override public boolean isPlaying() {
        return sequencer.isRunning();
    }

    @Override public long position() {
        return sequencer.isOpen() ? sequencer.getMicrosecondPosition() : 0;
    }

    @Override public long duration() {
        return durationMicros;
    }

    @Override public void seek(long micros) {
        if (!sequencer.isOpen()) {
            return;
        }
        sequencer.setMicrosecondPosition(Math.max(0, Math.min(micros, durationMicros)));
        // A jump leaves whatever was sounding stuck on until its note-off,
        // which the sequencer has now skipped past.
        allNotesOff();
    }

    /**
     * General MIDI has no master volume, so channel volume (controller 7) is set
     * on all sixteen at once. A file that sets its own volume will overrule
     * this the next time it does so, which is the usual bargain with GM.
     */
    @Override public void setVolume(float fraction) {
        volume = Math.max(0f, Math.min(1f, fraction));
        int value = Math.round(volume * 127f);
        for (MidiChannel channel : synthesizer.getChannels()) {
            if (channel != null) {
                channel.controlChange(7, value);
            }
        }
    }

    private void allNotesOff() {
        for (MidiChannel channel : synthesizer.getChannels()) {
            if (channel != null) {
                channel.allNotesOff();
                channel.allSoundOff();
            }
        }
        java.util.Arrays.fill(held, 0);
        java.util.Arrays.fill(loudest, 0f);
        levels.silence();
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

    private void reachedEnd() {
        allNotesOff();
        java.awt.EventQueue.invokeLater(finished);
    }

    @Override public void close() {
        closed = true;
        try {
            if (sequencer.isOpen()) {
                sequencer.stop();
            }
            allNotesOff();
        } catch (RuntimeException ignored) {
            // Closing a device that has already gone away is not worth reporting.
        }
        closeQuietly(sequencer);
        closeQuietly(synthesizer);
    }

    private static void closeQuietly(MidiDevice device) {
        try {
            if (device != null && device.isOpen()) {
                device.close();
            }
        } catch (RuntimeException ignored) {
            // Same.
        }
    }
}

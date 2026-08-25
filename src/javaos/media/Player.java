package javaos.media;

/**
 * One piece of media, opened and playing. Two implementations back it:
 * {@link SampledPlayer} pushes PCM at a {@code SourceDataLine},
 * {@link MidiPlayer} drives the JDK sequencer.
 *
 * <p>Every method is safe to call from the event thread; the engines do their
 * own work on a private thread and never block the caller.
 */
public interface Player extends AutoCloseable {

    /** Starts, or resumes from wherever {@link #pause()} left off. */
    void play();

    void pause();

    /** Stops and rewinds to the beginning. */
    void stop();

    boolean isPlaying();

    /** Microseconds into the piece. */
    long position();

    /** Total length in microseconds, or -1 when the format will not say. */
    long duration();

    void seek(long micros);

    /** Volume as a fraction from 0 to 1; engines map it to their own scale. */
    void setVolume(float fraction);

    /** How this piece describes itself, for the status bar. */
    String describe();

    /** Live signal for the visualiser. Never null. */
    Levels levels();

    /** Called on the event thread when the piece reaches its end. */
    void onFinished(Runnable listener);

    @Override void close();

    /**
     * What the visualiser draws. Sampled audio fills {@link #peaks} with one
     * value per channel; MIDI fills it with one value per channel of the
     * sixteen, from real note-on velocities. Values decay towards zero so the
     * meter falls back rather than snapping.
     */
    final class Levels {

        private final float[] peaks;
        private final float[] floors;
        private final Object lock = new Object();

        public Levels(int channels) {
            this.peaks = new float[Math.max(1, channels)];
            this.floors = new float[peaks.length];
        }

        public int channels() {
            return peaks.length;
        }

        /** Raises a channel to {@code value} if the signal is louder than the decay. */
        public void hit(int channel, float value) {
            if (channel < 0 || channel >= peaks.length) {
                return;
            }
            synchronized (lock) {
                peaks[channel] = Math.max(peaks[channel], Math.min(1f, value));
            }
        }

        /**
         * Holds a channel at {@code value} until it is held at something else.
         *
         * <p>Sampled audio never needs this: every buffer carries a fresh peak,
         * so the meter has something new to say twenty times a second. MIDI is
         * the opposite -- a note-on is one instant and then silence on the
         * wire, however long the note actually rings -- so the sequenced engine
         * holds a channel up for as long as it has notes down, and the bar
         * falls when the last of them lifts.
         */
        public void hold(int channel, float value) {
            if (channel < 0 || channel >= floors.length) {
                return;
            }
            synchronized (lock) {
                floors[channel] = Math.max(0f, Math.min(1f, value));
            }
        }

        /** Reads the meter and applies one step of decay, down to any held floor. */
        public float[] read(float decay) {
            synchronized (lock) {
                float[] copy = peaks.clone();
                for (int i = 0; i < peaks.length; i++) {
                    peaks[i] = Math.max(floors[i], peaks[i] - decay);
                }
                return copy;
            }
        }

        public void silence() {
            synchronized (lock) {
                java.util.Arrays.fill(peaks, 0f);
                java.util.Arrays.fill(floors, 0f);
            }
        }
    }
}

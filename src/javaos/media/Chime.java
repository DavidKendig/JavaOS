package javaos.media;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/**
 * Generates the short MIDI file seeded into {@code /home/duke/Music} on first
 * boot, so the Media Player has something to sound the moment it opens.
 *
 * <p>Written by {@code javax.sound.midi} rather than carried as bytes, for the
 * same reason no icon in JavaOS is a PNG. What it plays is a C major arpeggio
 * over a held fifth -- a scale figure, not a tune.
 */
public final class Chime {

    /** Ticks per quarter note. */
    private static final int RESOLUTION = 96;

    /** Vibraphone, in the General MIDI program list. */
    private static final int VIBRAPHONE = 11;

    /** Warm pad, for the drone underneath. */
    private static final int PAD = 89;

    private Chime() {
    }

    /** The chime as a standard MIDI file, ready to be written to the volume. */
    public static byte[] bytes() {
        try {
            Sequence sequence = new Sequence(Sequence.PPQ, RESOLUTION);
            melody(sequence.createTrack());
            drone(sequence.createTrack());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MidiSystem.write(sequence, 1, out);
            return out.toByteArray();
        } catch (InvalidMidiDataException | IOException e) {
            // Every value below is a constant this class chose, so a failure
            // here would mean the JDK sequencer itself is broken.
            throw new IllegalStateException("cannot build the seeded chime", e);
        }
    }

    /** Up the arpeggio and back down, an eighth note apart. */
    private static void melody(Track track) throws InvalidMidiDataException {
        program(track, 0, VIBRAPHONE);
        int[] pitches = {60, 64, 67, 72, 79, 72, 67, 64, 60};
        int eighth = RESOLUTION / 2;
        for (int i = 0; i < pitches.length; i++) {
            int start = i * eighth;
            int length = i == pitches.length - 1 ? RESOLUTION * 3 : eighth * 2;
            int velocity = 92 - Math.abs(i - 4) * 4;
            note(track, 0, pitches[i], velocity, start, length);
        }
    }

    /** A low C and G held under the whole figure. */
    private static void drone(Track track) throws InvalidMidiDataException {
        program(track, 1, PAD);
        int whole = RESOLUTION * 8;
        note(track, 1, 36, 54, 0, whole);
        note(track, 1, 43, 46, 0, whole);
    }

    private static void program(Track track, int channel, int patch)
            throws InvalidMidiDataException {
        track.add(new MidiEvent(
                new ShortMessage(ShortMessage.PROGRAM_CHANGE, channel, patch, 0), 0));
    }

    private static void note(Track track, int channel, int pitch, int velocity,
            long start, long length) throws InvalidMidiDataException {
        track.add(new MidiEvent(
                new ShortMessage(ShortMessage.NOTE_ON, channel, pitch, velocity), start));
        track.add(new MidiEvent(
                new ShortMessage(ShortMessage.NOTE_OFF, channel, pitch, 0), start + length));
    }
}

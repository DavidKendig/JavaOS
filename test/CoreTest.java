import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

import javaos.Settings;
import javaos.apps.SheetModel;
import javaos.media.Media;
import javaos.media.Player;
import javaos.media.SampledPlayer;
import javaos.vfs.Vfs;

/** Exercises the formula engine and the volume without opening a window. */
public final class CoreTest {

    private static int failures;

    public static void main(String[] args) throws Exception {
        sheet();
        volume();
        paths();
        media();
        screenLock();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void sheet() {
        SheetModel m = new SheetModel(20, 10);
        m.setValueAt("10", 0, 0);          // A1
        m.setValueAt("20", 1, 0);          // A2
        m.setValueAt("30", 2, 0);          // A3
        m.setValueAt("=SUM(A1:A3)", 3, 0); // A4
        m.setValueAt("=A4/2", 4, 0);
        m.setValueAt("=A1+A2*2", 5, 0);
        m.setValueAt("=(A1+A2)*2", 6, 0);
        m.setValueAt("=2^10", 7, 0);
        m.setValueAt("=AVG(A1:A3)", 8, 0);
        m.setValueAt("=MAX(A1:A3)-MIN(A1:A3)", 9, 0);
        m.setValueAt("=ROUND(PI(), 3)", 10, 0);
        m.setValueAt("=SQRT(16)", 11, 0);
        m.setValueAt("=COUNT(A1:A3)", 12, 0);
        m.setValueAt("=A1/0", 13, 0);
        m.setValueAt("=A15", 14, 0);       // A15 refers to itself
        m.setValueAt("=NOPE(1)", 15, 0);
        m.setValueAt("hello", 16, 0);
        m.setValueAt("=-A1", 17, 0);

        eq("SUM", "60", m.display(3, 0));
        eq("divide", "30", m.display(4, 0));
        eq("precedence", "50", m.display(5, 0));
        eq("parens", "60", m.display(6, 0));
        eq("power", "1,024", m.display(7, 0));
        eq("avg", "20", m.display(8, 0));
        eq("max-min", "20", m.display(9, 0));
        eq("round pi", "3.142", m.display(10, 0));
        eq("sqrt", "4", m.display(11, 0));
        eq("count", "3", m.display(12, 0));
        eq("div zero", "#DIV/0!", m.display(13, 0));
        eq("circular", "#CIRC!", m.display(14, 0));
        eq("bad function", "#ERR!", m.display(15, 0));
        eq("text passthrough", "hello", m.display(16, 0));
        eq("unary minus", "-10", m.display(17, 0));
        eq("text is zero", 0.0, m.numberAt(16, 0));

        eq("column A", "A", SheetModel.columnName(0));
        eq("column Z", "Z", SheetModel.columnName(25));
        eq("column AA", "AA", SheetModel.columnName(26));
        eq("index AA", 26.0, (double) SheetModel.columnIndex("AA"));

        String csv = m.toCsv();
        SheetModel round = new SheetModel(20, 10);
        round.fromCsv(csv);
        eq("csv keeps formulas", "60", round.display(3, 0));

        SheetModel quoted = new SheetModel(5, 5);
        quoted.fromCsv("a,\"b,c\",d\n1,2,3\n");
        eq("quoted csv", "b,c", quoted.display(0, 1));
    }

    private static void volume() throws Exception {
        Path root = Files.createTempDirectory("javaos-check");
        Vfs vfs = new Vfs(root);
        vfs.mount();
        eq("home exists", true, vfs.isDirectory(Vfs.HOME));
        eq("motd seeded", true, vfs.exists("/etc/motd"));

        vfs.write("/home/duke/a.txt", "one");
        eq("read back", "one", vfs.read("/home/duke/a.txt"));
        vfs.copy("/home/duke/a.txt", "/home/duke/b.txt");
        eq("copy", "one", vfs.read("/home/duke/b.txt"));
        vfs.move("/home/duke/b.txt", "/tmp/c.txt");
        eq("move source gone", false, vfs.exists("/home/duke/b.txt"));
        eq("move target", "one", vfs.read("/tmp/c.txt"));
        vfs.delete("/tmp/c.txt");
        eq("delete", false, vfs.exists("/tmp/c.txt"));

        vfs.mkdirs("/tmp/deep/nested");
        vfs.write("/tmp/deep/nested/x.txt", "x");
        vfs.delete("/tmp/deep");
        eq("recursive delete", false, vfs.exists("/tmp/deep"));

        String unique = vfs.uniqueName("/home/duke", "a.txt");
        eq("unique name", "/home/duke/a (2).txt", unique);

        try {
            vfs.read("/../../../etc/passwd");
            fail("escape was not blocked");
        } catch (IllegalArgumentException expected) {
            // Normalisation collapses the escape before it reaches the host.
        } catch (RuntimeException expected) {
            // Missing file after normalisation is also an acceptable outcome.
        }
        eq("normalised escape stays inside", "/etc/passwd",
                Vfs.normalize("/../../../etc/passwd"));
    }

    private static void paths() {
        eq("resolve relative", "/home/duke/Documents",
                Vfs.resolve(Vfs.HOME, "Documents"));
        eq("resolve dotdot", "/home", Vfs.resolve(Vfs.HOME, ".."));
        eq("resolve tilde", "/home/duke/x", Vfs.resolve("/tmp", "~/x"));
        eq("resolve absolute", "/etc", Vfs.resolve("/tmp", "/etc"));
        eq("parent", "/home", Vfs.parent(Vfs.HOME));
        eq("name", "duke", Vfs.name(Vfs.HOME));
        eq("extension", "csv", Vfs.extension("/a/b/c.CSV"));
        eq("human size", "1.0 KB", Vfs.humanSize(1024));
    }

    /**
     * The media split: what the JDK decodes, what VLC has to. Playback itself
     * needs an audio device, so the parts that open one skip themselves when
     * the machine has none, the way the LibreOffice interop checks do.
     */
    private static void media() throws Exception {
        eq("wav is sampled", Media.Kind.SAMPLED, Media.kindOf("/home/duke/Music/a.wav"));
        eq("aiff is sampled", Media.Kind.SAMPLED, Media.kindOf("/x/a.AIFF"));
        eq("mid is midi", Media.Kind.MIDI, Media.kindOf("/x/chime.mid"));
        eq("mp3 needs vlc", Media.Kind.HANDOVER, Media.kindOf("/x/track.mp3"));
        eq("mkv needs vlc", Media.Kind.HANDOVER, Media.kindOf("/x/film.mkv"));
        eq("odt is not media", Media.Kind.OTHER, Media.kindOf("/x/letter.odt"));
        eq("no extension is not media", Media.Kind.OTHER, Media.kindOf("/x/README"));
        eq("wav is playable", true, Media.isPlayable("/x/a.wav"));
        eq("mp3 is not playable", false, Media.isPlayable("/x/a.mp3"));
        eq("mp3 is still media", true, Media.isMedia("/x/a.mp3"));
        eq("midi label", "MIDI", Media.label("/x/a.kar"));
        eq("mp3 label", "MP3", Media.label("/x/a.mp3"));

        // The seeded chime is generated, so it must parse as a real MIDI file.
        byte[] chime = javaos.media.Chime.bytes();
        Sequence sequence = MidiSystem.getSequence(new ByteArrayInputStream(chime));
        eq("chime has two tracks", 2, sequence.getTracks().length);
        eq("chime is PPQ", Sequence.PPQ, sequence.getDivisionType());
        eq("chime lasts a few seconds", true,
                sequence.getMicrosecondLength() > 1_000_000L
                        && sequence.getMicrosecondLength() < 20_000_000L);

        // A one-second 8 kHz tone, written and read back by the sampled engine.
        AudioFormat format = new AudioFormat(8000f, 16, 1, true, false);
        byte[] pcm = new byte[16000];
        for (int i = 0; i < pcm.length; i += 2) {
            short value = (short) (Math.sin(i / 2 * 2 * Math.PI * 440 / 8000) * 12000);
            pcm[i] = (byte) (value & 0xFF);
            pcm[i + 1] = (byte) (value >> 8);
        }
        Path wav = Files.createTempFile("javaos-tone", ".wav");
        try (AudioInputStream in = new AudioInputStream(
                new java.io.ByteArrayInputStream(pcm), format, pcm.length / 2)) {
            AudioSystem.write(in, AudioFileFormat.Type.WAVE, wav.toFile());
        }
        eq("tone was written", true, Files.size(wav) > 16000);

        if (!AudioSystem.isLineSupported(
                new DataLine.Info(SourceDataLine.class, format))) {
            System.out.println("skip media playback: no audio output line on this machine");
        } else {
            try (Player player = new SampledPlayer(wav.toFile())) {
                eq("tone is one second", 1_000_000L, player.duration());
                eq("engine reports the format", true, player.describe().contains("WAVE"));
                eq("meter has one channel", 1, player.levels().channels());
                eq("starts at the beginning", 0L, player.position());
                player.seek(500_000L);
                eq("seek moves the position", 500_000L, player.position());
                eq("not playing until asked", false, player.isPlaying());
            }
        }
        Files.deleteIfExists(wav);

        // The lookup must answer without an installation, and without throwing.
        eq("vlc lookup is consistent", javaos.vlc.Vlc.find().isPresent(),
                javaos.vlc.Vlc.isAvailable());
    }

    /** The lock screen's passphrase: stored as a derivation, never as text. */
    private static void screenLock() throws Exception {
        Path file = Files.createTempFile("javaos-lock", ".properties");
        Files.delete(file);
        Settings settings = new Settings(file);

        eq("no passphrase by default", false, settings.hasLockPassphrase());
        eq("an unset lock opens to anything", true, settings.unlocks("whatever".toCharArray()));

        settings.setLockPassphrase("correct horse".toCharArray());
        eq("passphrase is set", true, settings.hasLockPassphrase());
        eq("right passphrase opens", true, settings.unlocks("correct horse".toCharArray()));
        eq("wrong passphrase does not", false, settings.unlocks("correct hors".toCharArray()));
        eq("empty guess does not", false, settings.unlocks(new char[0]));

        settings.save();
        String saved = Files.readString(file);
        eq("the passphrase itself is not stored", false, saved.contains("correct horse"));
        eq("a salt is stored", true, saved.contains("lock.salt"));
        eq("re-read still opens", true,
                new Settings(file).unlocks("correct horse".toCharArray()));

        javaos.Passphrase.Stored a = javaos.Passphrase.of("same".toCharArray());
        javaos.Passphrase.Stored b = javaos.Passphrase.of("same".toCharArray());
        eq("each passphrase gets its own salt", false, a.salt().equals(b.salt()));
        eq("so two hashes of one passphrase differ", false, a.hash().equals(b.hash()));
        eq("and both still verify", true,
                javaos.Passphrase.matches("same".toCharArray(), a)
                        && javaos.Passphrase.matches("same".toCharArray(), b));

        settings.clearLockPassphrase();
        eq("cleared", false, settings.hasLockPassphrase());
        Files.deleteIfExists(file);
    }

    private static void eq(String label, Object expected, Object actual) {
        if (!String.valueOf(expected).equals(String.valueOf(actual))) {
            System.out.println("FAIL " + label + ": expected <" + expected
                    + "> but was <" + actual + ">");
            failures++;
        } else {
            System.out.println("ok   " + label + " = " + actual);
        }
    }

    private static void fail(String message) {
        System.out.println("FAIL " + message);
        failures++;
    }
}

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

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
import javaos.interop.OfficeSuite;
import javaos.msoffice.MsOffice;
import javaos.sys.HostShell;
import javaos.media.Media;
import javaos.media.Player;
import javaos.media.SampledPlayer;
import javaos.ui.Wallpapers;
import javaos.vfs.Vfs;

/** Exercises the formula engine and the volume without opening a window. */
public final class CoreTest {

    private static int failures;

    public static void main(String[] args) throws Exception {
        sheet();
        volume();
        paths();
        media();
        officeSuite();
        wallpapers();
        screenLock();
        hostShell();
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

    /**
     * The file system, which is now the host's own. Everything happens inside a
     * temporary directory: the operations are the same ones the file manager
     * calls, and there is no sandbox left to stop them reaching anywhere else,
     * so the test has to be careful where it points them.
     */
    private static void volume() throws Exception {
        Vfs vfs = new Vfs();
        Path temp = Files.createTempDirectory("javaos-check");
        String work = Vfs.toVirtual(temp);
        try {
            eq("a real directory is seen", true, vfs.isDirectory(work));
            eq("and maps back to itself", temp.toRealPath(),
                    vfs.host(work).toRealPath());
            eq("the user home is a real directory", true, vfs.isDirectory(Vfs.HOME));

            vfs.write(work + "/a.txt", "one");
            eq("read back", "one", vfs.read(work + "/a.txt"));
            eq("and the host sees the same file", "one",
                    Files.readString(temp.resolve("a.txt")));

            vfs.copy(work + "/a.txt", work + "/b.txt");
            eq("copy", "one", vfs.read(work + "/b.txt"));
            vfs.move(work + "/b.txt", work + "/sub/c.txt");
            eq("move source gone", false, vfs.exists(work + "/b.txt"));
            eq("move target", "one", vfs.read(work + "/sub/c.txt"));

            vfs.mkdirs(work + "/deep/nested");
            vfs.write(work + "/deep/nested/x.txt", "x");
            vfs.delete(work + "/deep");
            eq("recursive delete", false, vfs.exists(work + "/deep"));

            eq("unique name", work + "/a (2).txt", vfs.uniqueName(work, "a.txt"));

            // Directories first, then files -- "zzz-dir" sorts after "a.txt"
            // alphabetically, so it can only come first if the kind wins.
            vfs.mkdirs(work + "/zzz-dir");
            List<String> listed = vfs.list(work);
            int lastDirectory = -1;
            int firstFile = listed.size();
            for (int i = 0; i < listed.size(); i++) {
                if (vfs.isDirectory(listed.get(i))) {
                    lastDirectory = i;
                } else if (i < firstFile) {
                    firstFile = i;
                }
            }
            eq("every directory sorts before every file", true, lastDirectory < firstFile);
            eq("and the listing found both kinds", true,
                    lastDirectory >= 0 && firstFile < listed.size());

            eq("the drive is reported", true, vfs.totalSpace(work) > 0);
            eq("with some of it free", true, vfs.freeSpace(work) > 0);
            eq("and used is the difference", vfs.totalSpace(work) - vfs.freeSpace(work),
                    vfs.usedSpace(work));
        } finally {
            deleteTree(temp);
        }

        // My Computer: a directory that exists only in the path vocabulary.
        eq("the root is a directory", true, vfs.isDirectory(Vfs.ROOT));
        eq("the root exists", true, vfs.exists(Vfs.ROOT));
        eq("the root is the root", true, Vfs.isRoot("/"));
        eq("and lists at least one drive", true, !vfs.roots().isEmpty());
        for (String drive : vfs.roots()) {
            eq(drive + " reads as a drive", true, Vfs.isDrive(drive));
            eq(drive + " has the root as its parent", Vfs.ROOT, Vfs.parent(drive));
        }
        eq("listing the root gives the drives", vfs.roots(), vfs.list(Vfs.ROOT));

        // An unreadable directory is an ordinary event on a real machine, and
        // must come back empty rather than throwing into the file manager.
        eq("a missing directory lists empty", 0,
                vfs.list("/no-such-drive-here/nothing").size());
        eq("a missing file has no size", 0L, vfs.size("/no-such-drive-here/nothing"));
        eq("and no timestamp", 0L, vfs.modified("/no-such-drive-here/nothing"));
        eq("and does not exist", false, vfs.exists("/no-such-drive-here/nothing"));
    }

    /** Removes a tree without going through the wastebasket, for cleanup. */
    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static void paths() {
        eq("resolve relative", Vfs.HOME + "/Documents",
                Vfs.resolve(Vfs.HOME, "Documents"));
        eq("resolve dotdot", Vfs.parent(Vfs.HOME), Vfs.resolve(Vfs.HOME, ".."));
        eq("resolve tilde", Vfs.HOME + "/x", Vfs.resolve("/tmp", "~/x"));
        eq("resolve absolute", "/etc", Vfs.resolve("/tmp", "/etc"));
        eq("resolve keeps the root", "/", Vfs.resolve("/tmp", "/"));
        eq("name", "c.CSV", Vfs.name("/a/b/c.CSV"));
        eq("parent", "/a/b", Vfs.parent("/a/b/c.CSV"));
        eq("extension", "csv", Vfs.extension("/a/b/c.CSV"));
        eq("human size", "1.0 KB", Vfs.humanSize(1024));

        // The home directory is the real one, wherever the host keeps it.
        eq("home is absolute", true, Vfs.HOME.startsWith("/"));
        eq("home is the user home", Paths.get(System.getProperty("user.home"))
                .toAbsolutePath().normalize(), new Vfs().host(Vfs.HOME));

        // A host path in, the same host path out, whatever the separators.
        Path sample = Paths.get(System.getProperty("user.home"), "sample.txt")
                .toAbsolutePath().normalize();
        eq("a host path round-trips", sample, new Vfs().host(Vfs.toVirtual(sample)));
        eq("backslashes are accepted", Vfs.HOME + "/x",
                Vfs.resolve(Vfs.HOME, "~\\x"));
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

    /**
     * The backdrops. Five are images bundled in the jar and five are drawn in
     * code; the drawn ones cannot go missing, but the images can, if a build
     * compiles the sources and forgets to copy the resources beside them. That
     * is exactly the failure this catches, because the desktop swallows it --
     * an unreadable wallpaper falls back to a plain backdrop rather than
     * throwing, so nothing else would ever say a word about it.
     */
    private static void wallpapers() throws Exception {
        eq("chrome is the default backdrop", Settings.Wallpaper.CHROME,
                new Settings(Paths.get("no-such-settings.properties")).wallpaper());

        int images = 0;
        for (Settings.Wallpaper style : Settings.Wallpaper.values()) {
            eq(style + " has a label", true, !style.label.isBlank());
            if (!style.isImage()) {
                eq(style + " is drawn, so it names no resource", null, style.resource);
                continue;
            }
            images++;

            // Painting is the check that the build actually shipped the file:
            // it has to put something down, at a size that is neither the
            // image's own nor its aspect ratio.
            BufferedImage target = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = (Graphics2D) target.getGraphics();
            boolean painted = Wallpapers.paint(g, 320, 200, style.resource);
            g.dispose();
            eq(style + " paints", true, painted);

            Set<Integer> colours = new HashSet<>();
            for (int y = 0; y < 200; y += 8) {
                for (int x = 0; x < 320; x += 8) {
                    colours.add(target.getRGB(x, y));
                }
            }
            eq(style + " paints more than one colour", true, colours.size() > 1);
        }
        eq("all five bundled images are accounted for", 5, images);
        eq("only the low-poly backdrop is still generated", 1,
                Settings.Wallpaper.values().length - images);

        // A settings file written by a build with more backdrops than this one
        // names styles that are gone. Those have to land on the default rather
        // than throw out of a getter the whole desktop calls on every repaint.
        Path saved = Files.createTempFile("javaos-backdrop", ".properties");
        try {
            for (String retired : new String[] {"HORIZON", "RAYS", "GRID", "WEAVE", "FLAT"}) {
                Files.writeString(saved, "wallpaper=" + retired + "\n");
                eq("a saved " + retired + " falls back to the default",
                        Settings.Wallpaper.CHROME, new Settings(saved).wallpaper());
            }
            Files.writeString(saved, "wallpaper=FACETS\n");
            eq("a saved backdrop that still exists is kept", Settings.Wallpaper.FACETS,
                    new Settings(saved).wallpaper());
        } finally {
            Files.deleteIfExists(saved);
        }

        eq("a missing image paints nothing rather than throwing", false, Wallpapers.paint(
                (Graphics2D) new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB).getGraphics(),
                8, 8, "NoSuchWallpaper.png"));
        // The fitted image is cached by size, so a second size must re-fit
        // rather than hand back the first one.
        BufferedImage wide = new BufferedImage(400, 120, BufferedImage.TYPE_INT_RGB);
        Graphics2D wg = (Graphics2D) wide.getGraphics();
        boolean refitted = Wallpapers.paint(wg, 400, 120, Settings.Wallpaper.CHROME.resource);
        wg.dispose();
        eq("a second size re-fits", true, refitted);
    }

    /**
     * The office hand-over order: Microsoft Office, then LibreOffice, then the
     * JavaOS editors. The lookups have to answer on a machine with neither
     * installed, and without throwing, so most of this checks the mapping and
     * the shape of the answer rather than what happens to be on this machine.
     */
    private static void officeSuite() throws Exception {
        // Extensions land on the right role, whatever suite ends up opening them.
        eq("docx is a Word document", OfficeSuite.Program.WORD,
                OfficeSuite.programFor("docx"));
        eq("odt is a Word document", OfficeSuite.Program.WORD, OfficeSuite.programFor("ODT"));
        eq("xlsx is an Excel document", OfficeSuite.Program.EXCEL,
                OfficeSuite.programFor("xlsx"));
        eq("pptx is a PowerPoint document", OfficeSuite.Program.POWERPOINT,
                OfficeSuite.programFor("pptx"));
        eq("accdb is an Access document", OfficeSuite.Program.ACCESS,
                OfficeSuite.programFor("accdb"));
        eq("odb is an Access document", OfficeSuite.Program.ACCESS,
                OfficeSuite.programFor("odb"));
        eq("txt is not an office document", null, OfficeSuite.programFor("txt"));
        eq("mp3 is not an office document", null, OfficeSuite.programFor("mp3"));

        // Word and Calc have a pure Java editor behind them; the other two do not.
        eq("Word falls back to JavaOS Writer", "writer",
                OfficeSuite.Program.WORD.nativeAppId);
        eq("Excel falls back to JavaOS Calc", "calc", OfficeSuite.Program.EXCEL.nativeAppId);
        eq("PowerPoint has no JavaOS editor", false,
                OfficeSuite.Program.POWERPOINT.hasNativeApp());
        eq("Access has no JavaOS editor", false, OfficeSuite.Program.ACCESS.hasNativeApp());

        // Every role reaches both suites, so a hand-over always has a target.
        for (OfficeSuite.Program program : OfficeSuite.Program.values()) {
            eq(program.label + " maps to an Office program", true, program.microsoft != null);
            eq(program.label + " maps to a LibreOffice module", true, program.libre != null);
            eq(program.label + " describes itself", true,
                    !OfficeSuite.describe(program).isBlank());
        }
        eq("LibreOffice Base takes the database role",
                javaos.soffice.LibreOffice.Module.BASE, OfficeSuite.Program.ACCESS.libre);

        // The lookups must answer without an installation, and without throwing.
        for (MsOffice.Program program : MsOffice.Program.values()) {
            eq(program.label + " lookup is consistent", MsOffice.find(program).isPresent(),
                    MsOffice.isAvailable(program));
        }
        eq("installed() agrees with isAvailable()", MsOffice.isAvailable(),
                !MsOffice.installed().isEmpty());
        eq("a handler is found only when a suite is", MsOffice.isAvailable()
                        || javaos.soffice.LibreOffice.isAvailable(),
                OfficeSuite.isAvailable(OfficeSuite.Program.WORD));

        // Microsoft Office wins when both are installed. Neither may be here, so
        // this stands a stub in for Word and checks the order, not the machine.
        Path stub = Files.createTempFile("javaos-winword", ".exe");
        try {
            System.setProperty("javaos.msoffice.word", stub.toString());
            MsOffice.refresh(MsOffice.Program.WORD);
            eq("the override is found", true, MsOffice.isAvailable(MsOffice.Program.WORD));
            eq("Office outranks LibreOffice for Word", OfficeSuite.Vendor.MICROSOFT,
                    OfficeSuite.handlerFor(OfficeSuite.Program.WORD)
                            .map(OfficeSuite.Handler::vendor).orElse(null));
            eq("and only for the program it names", true,
                    OfficeSuite.handlerFor(OfficeSuite.Program.EXCEL)
                            .map(OfficeSuite.Handler::vendor)
                            .orElse(OfficeSuite.Vendor.JAVAOS) != OfficeSuite.Vendor.MICROSOFT
                            || MsOffice.isAvailable(MsOffice.Program.EXCEL));
        } finally {
            System.clearProperty("javaos.msoffice.word");
            MsOffice.refresh(MsOffice.Program.WORD);
            Files.deleteIfExists(stub);
        }
        eq("the stub is gone again", false, MsOffice.find(MsOffice.Program.WORD)
                .map(install -> install.executable().toString().contains("javaos-winword"))
                .orElse(false));

        // The hand-over is on by default, and the switch is remembered.
        Path file = Files.createTempFile("javaos-office", ".properties");
        Files.delete(file);
        Settings settings = new Settings(file);
        eq("the installed suite is preferred by default", true,
                settings.preferInstalledOffice());
        settings.setPreferInstalledOffice(false);
        settings.save();
        eq("and the choice survives a reload", false,
                new Settings(file).preferInstalledOffice());
        Files.deleteIfExists(file);
    }

    /**
     * The terminal's interpreter, driven the way the terminal drives it.
     *
     * <p>Every check is about the pipe rather than about the shell: that a
     * command is seen to finish, that its output has arrived by the time the
     * prompt comes back, that the session remembers what the last command did
     * to it, and that the awkward cases -- a trailing comment, an accent, a
     * program that reads standard input, a line that prints something
     * marker-shaped -- derail none of it. Each of those has hung this pipe at
     * some point during its writing.
     */
    private static void hostShell() throws Exception {
        StringBuilder seen = new StringBuilder();
        BlockingQueue<String> prompts = new ArrayBlockingQueue<>(32);
        HostShell shell;
        try {
            shell = new HostShell(new File(System.getProperty("java.io.tmpdir")),
                    new HostShell.Listener() {
                        @Override public void output(String text) {
                            synchronized (seen) {
                                seen.append(text);
                            }
                        }

                        @Override public void ready(String dir, boolean ok, int code) {
                            prompts.add(dir + "\u0000" + ok + "\u0000" + code);
                        }

                        @Override public void ended(String reason) {
                            prompts.add("\u0000false\u00000");
                        }
                    });
        } catch (IOException e) {
            System.out.println("skip host shell: " + e.getMessage());
            return;
        }
        boolean ps = shell.name().toLowerCase(java.util.Locale.ROOT).startsWith("pwsh")
                || shell.name().toLowerCase(java.util.Locale.ROOT).startsWith("powershell");
        String echo = ps ? "Write-Output 'alpha'" : "echo alpha";
        try {
            eq("the shell announces itself before the first command", true,
                    prompts.poll(60, TimeUnit.SECONDS) != null);

            seen.setLength(0);
            eq("a command is seen to finish", "0", ask(shell, prompts, echo)[2]);
            eq("and its output arrived before the prompt", true, text(seen).contains("alpha"));

            String set = ps ? "$kept = 'remembered'" : "kept=remembered";
            ask(shell, prompts, set);
            seen.setLength(0);
            ask(shell, prompts, ps ? "Write-Output $kept" : "echo \"$kept\"");
            eq("the session remembers a variable", true, text(seen).contains("remembered"));

            String tmp = new File(System.getProperty("java.io.tmpdir")).getCanonicalPath();
            ask(shell, prompts, "cd '" + tmp + "'");
            eq("and remembers where it was told to go", true,
                    ask(shell, prompts, echo)[0].equalsIgnoreCase(tmp));

            // A trailing comment used to swallow whatever asks for the prompt.
            seen.setLength(0);
            eq("a trailing comment does not hang the prompt", "0",
                    ask(shell, prompts, echo + "   # a note to self")[2]);
            eq("and the command still ran", true, text(seen).contains("alpha"));

            // The marker is unguessable, or output could impersonate a prompt.
            seen.setLength(0);
            String decoy = "@@JAVAOS:0:ok:0:nowhere";
            ask(shell, prompts, ps ? "Write-Output '" + decoy + "'" : "echo '" + decoy + "'");
            eq("marker-shaped output is passed through, not obeyed", true,
                    text(seen).contains(decoy));

            // Output long enough to be read in several chunks, whose boundaries
            // land between a carriage return and its newline. A reader that
            // cuts there turns one line ending into two, and a listing comes
            // out double spaced -- 396 spurious blank lines in 400 rows, when
            // this was written.
            seen.setLength(0);
            ask(shell, prompts, ps
                    ? "1..400 | ForEach-Object { 'row {0:d4} ' -f $_ }"
                    : "for i in $(seq 1 400); do echo \"row $i \"; done");
            String[] lines = text(seen).split("\n", -1);
            int rows = 0;
            int blanks = 0;
            for (String each : lines) {
                if (each.startsWith("row ")) {
                    rows++;
                } else if (each.isEmpty()) {
                    blanks++;
                }
            }
            eq("every line of a long output arrives", 400, rows);
            eq("and none of them is doubled", 1, blanks);
            eq("with no carriage returns left in it", false, text(seen).contains("\r"));

            if (ps) {
                // Table-formatted output is the case that catches a prompt
                // arriving early: PowerShell renders it at the end of the
                // statement, which is after the marker unless the pipeline is
                // made to render as it goes.
                seen.setLength(0);
                ask(shell, prompts, "Get-ChildItem $env:SystemRoot\\*.ini");
                eq("formatted output arrives before the prompt does", true,
                        text(seen).contains("Mode") && text(seen).contains(".ini"));

                eq("a terminating error does not strand the prompt", "false",
                        ask(shell, prompts, "throw 'thrown on purpose'")[1]);
                eq("and the session is still there afterwards", "true",
                        ask(shell, prompts, echo)[1]);

                eq("a failing program is reported as one", "false",
                        ask(shell, prompts, "cmd /c exit 3")[1]);
                eq("with the code it failed by", "3",
                        ask(shell, prompts, "cmd /c exit 3")[2]);
                eq("a failing cmdlet is reported too", "false",
                        ask(shell, prompts, "Get-Item C:\\javaos-no-such-thing")[1]);
                eq("and the next success clears the verdict", "true",
                        ask(shell, prompts, echo)[1]);

                seen.setLength(0);
                ask(shell, prompts, "Write-Output 'caf\u00e9 \u65e5\u672c'");
                eq("non-ascii survives the round trip", true,
                        text(seen).contains("caf\u00e9 \u65e5\u672c"));

                // The classic way to hang a shell down a pipe: the prompting
                // command eats the line that was meant to ask for the prompt.
                seen.setLength(0);
                shell.send("$who = Read-Host 'name'; Write-Output \"hello $who\"");
                Thread.sleep(1500);
                eq("a prompting command holds the terminal", true, shell.isBusy());
                shell.write("Duke\n");
                eq("and answering it releases the prompt", true,
                        prompts.poll(60, TimeUnit.SECONDS) != null);
                eq("with what it was told", true, text(seen).contains("hello Duke"));
            }
        } finally {
            shell.close();
        }
        Thread.sleep(500);
        eq("closing stops the interpreter", false, shell.isAlive());
    }

    /** Runs one command and reports the prompt it came back with. */
    private static String[] ask(HostShell shell, BlockingQueue<String> prompts, String command)
            throws Exception {
        shell.send(command);
        String reply = prompts.poll(60, TimeUnit.SECONDS);
        return reply == null ? new String[] {"", "timed out", "timed out"}
                : reply.split("\u0000", -1);
    }

    private static String text(StringBuilder seen) {
        synchronized (seen) {
            return seen.toString();
        }
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

}

package javaos.vfs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The system disk. Virtual paths look like {@code /home/duke/Documents} and are
 * mapped onto a sandbox directory under the real user home, so nothing the
 * desktop does can wander outside its own volume.
 */
public final class Vfs {

    public static final String HOME = "/home/duke";

    private final Path root;

    public Vfs(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public static Vfs defaultVolume() {
        Path base = Paths.get(System.getProperty("user.home"), ".javaos", "volume");
        Vfs vfs = new Vfs(base);
        vfs.mount();
        return vfs;
    }

    public Path realRoot() {
        return root;
    }

    /** Creates the volume and seeds the standard directory tree on first boot. */
    public void mount() {
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create volume at " + root, e);
        }
        boolean fresh = !exists(HOME);
        for (String dir : new String[] {"/etc", "/tmp", "/apps", HOME,
                HOME + "/Documents", HOME + "/Spreadsheets", HOME + "/Pictures",
                HOME + "/Music"}) {
            mkdirs(dir);
        }
        if (fresh) {
            seed();
        }
    }

    /** The heading of the seeded release notes, so its underline always matches. */
    private static String releaseTitle() {
        return javaos.Version.FULL + " -- Release Notes";
    }

    private void seed() {
        write("/etc/motd", """
                Welcome to JavaOS.

                Everything you see is Swing: the desktop, the window manager,
                the applications, and the icons. Type 'help' for a command list.
                """);
        write(HOME + "/Documents/readme.txt", """
                %s
                %s

                Included with this release:

                  * Writer      styled text, saves .odt, .docx, .rtf and .txt
                  * Calc        a spreadsheet with real formulas, saves .ods,
                                .xlsx and .csv
                  * Terminal    a shell over the virtual volume
                  * Paint       bitmap editor, saves .png
                  * Media       plays .wav, .au, .aiff and .mid
                  * Calculator, System Monitor, Mines, Control Panel

                The office formats are read and written in pure Java; no
                LibreOffice and no third-party library is involved. The same
                goes for playback: the JDK decodes PCM and sounds MIDI, and
                anything needing a real codec is handed to VLC if you have it.

                Your files live under /home/duke and persist between sessions
                in ~/.javaos/volume on the host machine.
                """.formatted(releaseTitle(), "=".repeat(releaseTitle().length())));
        write(HOME + "/Documents/notes.txt",
                "Meeting notes\n-------------\n\n- Ship it\n- Then ship it again\n");
        // Generated, not carried: the same rule the icons and the wallpaper follow.
        writeBytes(HOME + "/Music/chime.mid", javaos.media.Chime.bytes());
        write(HOME + "/Spreadsheets/budget.csv", """
                Item,Qty,Unit,Total
                Workstation,4,2400,=B2*C2
                Monitor,8,600,=B3*C3
                Coffee,999,3,=B4*C4
                ,,Grand total,=SUM(D2:D4)
                """);
    }

    // ---- path handling -------------------------------------------------

    public static String normalize(String virtualPath) {
        String p = virtualPath.replace('\\', '/');
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        List<String> parts = new ArrayList<>();
        for (String segment : p.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (!parts.isEmpty()) {
                    parts.remove(parts.size() - 1);
                }
                continue;
            }
            parts.add(segment);
        }
        return "/" + String.join("/", parts);
    }

    /** Resolves a path against a working directory, honouring {@code ~} and {@code ..}. */
    public static String resolve(String cwd, String path) {
        if (path == null || path.isEmpty()) {
            return normalize(cwd);
        }
        if (path.equals("~")) {
            return HOME;
        }
        if (path.startsWith("~/")) {
            return normalize(HOME + "/" + path.substring(2));
        }
        if (path.startsWith("/")) {
            return normalize(path);
        }
        return normalize(cwd + "/" + path);
    }

    public static String parent(String virtualPath) {
        String p = normalize(virtualPath);
        int slash = p.lastIndexOf('/');
        return slash <= 0 ? "/" : p.substring(0, slash);
    }

    public static String name(String virtualPath) {
        String p = normalize(virtualPath);
        if (p.equals("/")) {
            return "/";
        }
        return p.substring(p.lastIndexOf('/') + 1);
    }

    public static String extension(String virtualPath) {
        String n = name(virtualPath);
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1).toLowerCase();
    }

    public static String join(String dir, String child) {
        return normalize(dir + "/" + child);
    }

    /** Maps a virtual path onto the host, refusing anything that escapes the volume. */
    public Path host(String virtualPath) {
        String p = normalize(virtualPath);
        Path real = root.resolve(p.substring(1)).normalize();
        if (!real.startsWith(root)) {
            throw new IllegalArgumentException("path escapes the volume: " + virtualPath);
        }
        return real;
    }

    // ---- operations ----------------------------------------------------

    public boolean exists(String path) {
        return Files.exists(host(path));
    }

    public boolean isDirectory(String path) {
        return Files.isDirectory(host(path));
    }

    public long size(String path) {
        try {
            return Files.isDirectory(host(path)) ? 0 : Files.size(host(path));
        } catch (IOException e) {
            return 0;
        }
    }

    public long modified(String path) {
        try {
            return Files.getLastModifiedTime(host(path)).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    /** Directories first, then files, each alphabetically -- the classic sort. */
    public List<String> list(String dir) {
        Path h = host(dir);
        if (!Files.isDirectory(h)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(h)) {
            return children
                    .map(p -> join(dir, p.getFileName().toString()))
                    .sorted(Comparator
                            .comparing((String p) -> isDirectory(p) ? 0 : 1)
                            .thenComparing(p -> name(p).toLowerCase()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void mkdirs(String dir) {
        try {
            Files.createDirectories(host(dir));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String read(String path) {
        try {
            return Files.readString(host(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public byte[] readBytes(String path) {
        try {
            return Files.readAllBytes(host(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void write(String path, String content) {
        writeBytes(path, content.getBytes(StandardCharsets.UTF_8));
    }

    public void writeBytes(String path, byte[] content) {
        try {
            Path h = host(path);
            Files.createDirectories(h.getParent());
            Files.write(h, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void touch(String path) {
        if (!exists(path)) {
            write(path, "");
        }
    }

    public void delete(String path) {
        Path h = host(path);
        try {
            if (Files.isDirectory(h)) {
                Files.walkFileTree(h, new SimpleFileVisitor<Path>() {
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes a)
                            throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override public FileVisitResult postVisitDirectory(Path d, IOException e)
                            throws IOException {
                        Files.delete(d);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } else {
                Files.deleteIfExists(h);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void move(String from, String to) {
        try {
            Path target = host(to);
            Files.createDirectories(target.getParent());
            Files.move(host(from), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void copy(String from, String to) {
        try {
            Path src = host(from);
            Path dst = host(to);
            Files.createDirectories(dst.getParent());
            if (Files.isDirectory(src)) {
                try (Stream<Path> walk = Files.walk(src)) {
                    for (Path p : walk.toList()) {
                        Path rel = src.relativize(p);
                        Path out = dst.resolve(rel);
                        if (Files.isDirectory(p)) {
                            Files.createDirectories(out);
                        } else {
                            Files.copy(p, out, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            } else {
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A name like "copy of report.txt" that does not collide in the target directory. */
    public String uniqueName(String dir, String preferred) {
        String candidate = join(dir, preferred);
        if (!exists(candidate)) {
            return candidate;
        }
        String base = preferred;
        String ext = "";
        int dot = preferred.lastIndexOf('.');
        if (dot > 0) {
            base = preferred.substring(0, dot);
            ext = preferred.substring(dot);
        }
        for (int i = 2; ; i++) {
            candidate = join(dir, base + " (" + i + ")" + ext);
            if (!exists(candidate)) {
                return candidate;
            }
        }
    }

    /** Total bytes stored on the volume, for the status bars that insist on knowing. */
    public long usedBytes() {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    public static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " bytes";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        }
        if (bytes < 1024L * 1024 * 1024 * 1024) {
            return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
        }
        return String.format("%.2f TB", bytes / (1024.0 * 1024 * 1024 * 1024));
    }
}

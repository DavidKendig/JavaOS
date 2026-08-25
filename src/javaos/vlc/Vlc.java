package javaos.vlc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Detects a VLC that the user installed themselves, and hands media to it on
 * request. JavaOS never bundles, installs or updates VLC.
 *
 * <p>This is the same bargain JavaOS strikes with LibreOffice, and for the same
 * reason. {@link javaos.media} plays what the JDK can decode on its own -- PCM
 * in WAV, AU and AIFF, and MIDI through the built-in synthesiser -- and that is
 * the whole of what pure Java can honestly claim. Everything past that is
 * codecs: H.264, HEVC, AAC, Vorbis and the rest, hundreds of thousands of lines
 * of C that no Swing desktop is going to reimplement. When the user has VLC,
 * those files get handed to it; when they do not, JavaOS says so plainly rather
 * than opening a window that cannot play anything.
 *
 * <p>Volume files are handed over by their real host path, so VLC reads them
 * straight out of {@code /home/duke}.
 */
public final class Vlc {

    /** A located installation. */
    public record Install(Path executable, String version) {

        public Path directory() {
            return executable.getParent();
        }
    }

    private static volatile Install cached;
    private static volatile boolean searched;

    private Vlc() {
    }

    // ---- location ------------------------------------------------------

    /** The cached lookup; call {@link #refresh()} to look again. */
    public static Optional<Install> find() {
        if (!searched) {
            refresh();
        }
        return Optional.ofNullable(cached);
    }

    public static synchronized Install refresh() {
        searched = true;
        cached = null;
        for (Path candidate : candidates()) {
            if (candidate != null && Files.isRegularFile(candidate)) {
                cached = new Install(candidate, readVersion(candidate));
                return cached;
            }
        }
        return null;
    }

    public static boolean isAvailable() {
        return find().isPresent();
    }

    private static List<Path> candidates() {
        List<Path> found = new ArrayList<>();
        String override = System.getProperty("javaos.vlc");
        if (override != null && !override.isBlank()) {
            found.add(Paths.get(override));
        }

        String os = osName();
        if (os.contains("win")) {
            found.addAll(windowsRegistryCandidates());
            for (String base : new String[] {System.getenv("ProgramFiles"),
                    System.getenv("ProgramFiles(x86)"), System.getenv("LOCALAPPDATA")}) {
                if (base != null) {
                    found.add(Paths.get(base, "VideoLAN", "VLC", "vlc.exe"));
                }
            }
        } else if (os.contains("mac")) {
            found.add(Paths.get("/Applications/VLC.app/Contents/MacOS/VLC"));
            found.add(Paths.get(System.getProperty("user.home"),
                    "Applications/VLC.app/Contents/MacOS/VLC"));
        } else {
            for (String path : new String[] {"/usr/bin/vlc", "/usr/local/bin/vlc",
                    "/snap/bin/vlc", "/var/lib/flatpak/exports/bin/org.videolan.VLC",
                    "/usr/lib/vlc/vlc"}) {
                found.add(Paths.get(path));
            }
            found.add(Paths.get(System.getProperty("user.home"),
                    ".local/share/flatpak/exports/bin/org.videolan.VLC"));
        }
        found.add(onPath(os.contains("win") ? "vlc.exe" : "vlc"));
        return found;
    }

    private static List<Path> windowsRegistryCandidates() {
        List<Path> found = new ArrayList<>();
        // The default value of the VideoLAN key is the full path to vlc.exe.
        for (String key : new String[] {
            "HKLM\\SOFTWARE\\VideoLAN\\VLC",
            "HKLM\\SOFTWARE\\WOW6432Node\\VideoLAN\\VLC",
            "HKCU\\SOFTWARE\\VideoLAN\\VLC"}) {
            String value = registryValue(key, null);
            if (value != null) {
                found.add(Paths.get(value));
            }
            String directory = registryValue(key, "InstallDir");
            if (directory != null) {
                found.add(Paths.get(directory, "vlc.exe"));
            }
        }
        String appPath = registryValue(
                "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\vlc.exe", null);
        if (appPath != null) {
            found.add(Paths.get(appPath));
        }
        return found;
    }

    /** Reads a registry value, or the key's default when {@code name} is null. */
    private static String registryValue(String key, String name) {
        try {
            List<String> command = new ArrayList<>(List.of("reg", "query", key));
            if (name == null) {
                command.add("/ve");
            } else {
                command.add("/v");
                command.add(name);
            }
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = read(process, 5);
            for (String line : output.split("\\R")) {
                int marker = line.indexOf("REG_SZ");
                if (marker >= 0) {
                    String value = line.substring(marker + "REG_SZ".length()).trim();
                    return value.isEmpty() ? null : value;
                }
            }
        } catch (IOException | RuntimeException e) {
            // No registry, no reg.exe, or no such key: try the next candidate.
        }
        return null;
    }

    private static Path onPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String entry : path.split(java.io.File.pathSeparator)) {
            Path candidate = Paths.get(entry, executable);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * On Windows {@code vlc.exe --version} puts its answer in a message box, so
     * asking costs the user a dialog they did not open. The installer records
     * the version in the registry, which is free to read; everywhere else VLC
     * is a console citizen and answers on stdout.
     */
    private static String readVersion(Path executable) {
        if (osName().contains("win")) {
            for (String key : new String[] {
                "HKLM\\SOFTWARE\\VideoLAN\\VLC",
                "HKLM\\SOFTWARE\\WOW6432Node\\VideoLAN\\VLC",
                "HKCU\\SOFTWARE\\VideoLAN\\VLC"}) {
                String version = registryValue(key, "Version");
                if (version != null) {
                    return version;
                }
            }
            return "unknown";
        }
        try {
            Process process = new ProcessBuilder(executable.toString(), "--version",
                    "--intf", "dummy").redirectErrorStream(true).start();
            for (String line : read(process, 20).split("\\R")) {
                if (line.contains("VLC")) {
                    for (String word : line.trim().split("\\s+")) {
                        if (!word.isEmpty() && Character.isDigit(word.charAt(0))) {
                            return word;
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // Fall through to unknown; the version is only ever displayed.
        }
        return "unknown";
    }

    private static String osName() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    }

    private static String read(Process process, int timeoutSeconds) throws IOException {
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line).append('\n');
            }
            process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            process.destroy();
        }
        return text.toString();
    }

    // ---- launching -----------------------------------------------------

    /** Opens one file in VLC. */
    public static void open(Path file) throws IOException {
        openAll(List.of(file));
    }

    /**
     * Opens several files as one VLC playlist, in the order given. JavaOS uses
     * this to hand over a whole queue rather than starting a window per track.
     */
    public static void openAll(List<Path> files) throws IOException {
        if (files.isEmpty()) {
            return;
        }
        Install install = find().orElseThrow(() -> new IOException("VLC is not installed"));
        List<String> command = new ArrayList<>();
        command.add(install.executable().toString());
        // Everything after this is a file name, never an option, whatever the
        // file happens to be called.
        command.add("--");
        for (Path file : files) {
            command.add(file.toAbsolutePath().toString());
        }
        new ProcessBuilder(command).directory(install.directory().toFile()).start();
    }
}

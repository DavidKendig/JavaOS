package javaos.msoffice;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Detects a Microsoft Office that the user installed themselves, and hands
 * documents to it on request. JavaOS never bundles, installs or updates Office,
 * and nothing here is required: {@link javaos.office} reads and writes ODF and
 * OOXML in pure Java whether or not a single one of these executables exists.
 *
 * <p>This is the same bargain JavaOS already strikes with LibreOffice and VLC,
 * one rung higher. When the machine has the real Word, a user asking for a word
 * processor almost certainly wants that rather than a Swing text pane, so
 * {@link javaos.interop.OfficeSuite} asks here first, falls back to LibreOffice,
 * and only then opens the JavaOS editor.
 *
 * <p>Office is Windows-only in any form JavaOS can start as a local process, so
 * the search finds nothing everywhere else rather than pretending otherwise.
 * Volume files are handed over by their real host path, so Office edits and
 * saves them in place.
 */
public final class MsOffice {

    /** The Office programs JavaOS offers to hand a document to. */
    public enum Program {
        WORD("Word", "winword.exe"),
        EXCEL("Excel", "excel.exe"),
        POWERPOINT("PowerPoint", "powerpnt.exe"),
        ACCESS("Access", "msaccess.exe");

        public final String label;
        public final String executable;

        Program(String label, String executable) {
            this.label = label;
            this.executable = executable;
        }
    }

    /** A located program. */
    public record Install(Program program, Path executable, String version) {

        /** The directory holding the executable, i.e. the Office program directory. */
        public Path directory() {
            return executable.getParent();
        }
    }

    /**
     * The Office layouts seen in the wild, newest first: Click-to-Run puts the
     * executables under {@code root\OfficeNN}, the older MSI installers put them
     * straight under {@code OfficeNN}.
     */
    private static final String[] VERSION_DIRECTORIES =
            {"Office16", "Office15", "Office14", "Office12", "Office11"};

    private static final Map<Program, Install> CACHE = new EnumMap<>(Program.class);
    private static final Map<Program, Boolean> SEARCHED = new EnumMap<>(Program.class);

    private MsOffice() {
    }

    // ---- location ------------------------------------------------------

    /** The cached lookup; call {@link #refresh(Program)} to look again. */
    public static synchronized Optional<Install> find(Program program) {
        if (!Boolean.TRUE.equals(SEARCHED.get(program))) {
            refresh(program);
        }
        return Optional.ofNullable(CACHE.get(program));
    }

    public static synchronized Install refresh(Program program) {
        SEARCHED.put(program, Boolean.TRUE);
        CACHE.remove(program);
        for (Path candidate : candidates(program)) {
            if (candidate != null && Files.isRegularFile(candidate)) {
                Install install = new Install(program, candidate, readVersion(candidate));
                CACHE.put(program, install);
                return install;
            }
        }
        return null;
    }

    /** Looks every program up again, for the Control Panel check-again button. */
    public static synchronized void refreshAll() {
        for (Program program : Program.values()) {
            refresh(program);
        }
    }

    public static boolean isAvailable(Program program) {
        return find(program).isPresent();
    }

    /** True when any one of the four is installed. */
    public static boolean isAvailable() {
        for (Program program : Program.values()) {
            if (isAvailable(program)) {
                return true;
            }
        }
        return false;
    }

    /** Every located program, in enum order. Empty when Office is not installed. */
    public static List<Install> installed() {
        List<Install> found = new ArrayList<>();
        for (Program program : Program.values()) {
            find(program).ifPresent(found::add);
        }
        return found;
    }

    private static List<Path> candidates(Program program) {
        List<Path> found = new ArrayList<>();
        // An explicit path always wins, on any platform, so anyone with an
        // unusual install can point JavaOS straight at it.
        String override = System.getProperty(
                "javaos.msoffice." + program.name().toLowerCase(Locale.ROOT));
        if (override != null && !override.isBlank()) {
            found.add(Paths.get(override));
        }
        if (!isWindows()) {
            return found;
        }

        // The App Paths key is the shortcut: Office registers the full path to
        // each executable there, whatever drive and edition it landed on.
        for (String root : new String[] {
            "HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\",
            "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\",
            "HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\App Paths\\"}) {
            String value = registryValue(root + program.executable, null);
            if (value != null) {
                found.add(Paths.get(unquote(value)));
            }
        }

        // Click-to-Run records where it unpacked the shared install.
        for (String key : new String[] {
            "HKLM\\SOFTWARE\\Microsoft\\Office\\ClickToRun\\Configuration",
            "HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\Office\\ClickToRun\\Configuration"}) {
            String path = registryValue(key, "InstallationPath");
            if (path != null) {
                addLayouts(found, Paths.get(unquote(path)), program);
            }
        }

        for (String base : new String[] {System.getenv("ProgramFiles"),
                System.getenv("ProgramFiles(x86)")}) {
            if (base != null) {
                addLayouts(found, Paths.get(base, "Microsoft Office"), program);
            }
        }
        found.add(onPath(program.executable));
        return found;
    }

    /** Adds every known {@code OfficeNN} arrangement under one install root. */
    private static void addLayouts(List<Path> found, Path root, Program program) {
        for (String version : VERSION_DIRECTORIES) {
            found.add(root.resolve(Paths.get("root", version, program.executable)));
            found.add(root.resolve(Paths.get(version, program.executable)));
        }
    }

    /** Reads a registry value, or the key default when {@code name} is null. */
    private static String registryValue(String key, String name) {
        if (!isWindows()) {
            return null;
        }
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

    /** App Paths values are sometimes quoted; the quotes are not part of the path. */
    private static String unquote(String value) {
        String trimmed = value.trim();
        if (trimmed.length() > 1 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
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
     * Office executables answer a version switch with a dialog rather than a
     * line of stdout, so asking directly would cost the user a window they did
     * not open. The Click-to-Run configuration carries the version for nothing,
     * and the {@code OfficeNN} directory names the release when it does not.
     */
    private static String readVersion(Path executable) {
        for (String key : new String[] {
            "HKLM\\SOFTWARE\\Microsoft\\Office\\ClickToRun\\Configuration",
            "HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\Office\\ClickToRun\\Configuration"}) {
            String version = registryValue(key, "VersionToReport");
            if (version != null) {
                return version;
            }
        }
        for (Path part : executable) {
            String name = part.toString();
            for (String directory : VERSION_DIRECTORIES) {
                if (name.equalsIgnoreCase(directory)) {
                    return name.substring("Office".length()) + ".0";
                }
            }
        }
        return "unknown";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
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

    /** Opens a document in its Office program. The file is edited and saved in place. */
    public static void open(Program program, Path file) throws IOException {
        launch(program, List.of(file.toAbsolutePath().toString()));
    }

    /** Starts an Office program with nothing open. */
    public static void start(Program program) throws IOException {
        launch(program, List.of());
    }

    private static void launch(Program program, List<String> arguments) throws IOException {
        Install install = find(program).orElseThrow(
                () -> new IOException("Microsoft " + program.label + " is not installed"));
        List<String> command = new ArrayList<>();
        command.add(install.executable().toString());
        command.addAll(arguments);
        new ProcessBuilder(command).directory(install.directory().toFile()).start();
    }

    // ---- file types ----------------------------------------------------

    /** The program Office would use for a file extension, or null when it has none. */
    public static Program programFor(String extension) {
        return switch (extension.toLowerCase(Locale.ROOT)) {
            case "doc", "docx", "docm", "dot", "dotx", "odt", "rtf" -> Program.WORD;
            case "xls", "xlsx", "xlsm", "xlt", "xltx", "ods", "csv" -> Program.EXCEL;
            case "ppt", "pptx", "pptm", "pot", "potx", "odp" -> Program.POWERPOINT;
            case "mdb", "accdb", "accde", "odb" -> Program.ACCESS;
            default -> null;
        };
    }
}

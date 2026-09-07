package javaos.soffice;

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
import java.util.stream.Stream;

/**
 * Detects a LibreOffice that the user installed themselves, and hands documents
 * to it on request. JavaOS never installs, bundles or updates LibreOffice; the
 * office formats are read and written by {@link javaos.office}, in pure Java,
 * whether or not any of this is present.
 *
 * <p>Volume files are handed over by their real host path: the JavaOS volume is
 * an ordinary directory, so LibreOffice edits and saves them in place.
 */
public final class LibreOffice {

    /** A located installation. */
    public record Install(Path executable, Path console, String version) {

        /** The directory holding soffice, i.e. the {@code program} directory. */
        public Path programDirectory() {
            return executable.getParent();
        }
    }

    private static volatile Install cached;
    private static volatile boolean searched;

    private LibreOffice() {
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
                Path console = consoleVariant(candidate);
                cached = new Install(candidate, console, readVersion(console, candidate));
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
        String override = System.getProperty("javaos.soffice");
        if (override != null && !override.isBlank()) {
            found.add(Paths.get(override));
        }

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            found.addAll(windowsRegistryCandidates());
            for (String base : new String[] {System.getenv("ProgramFiles"),
                    System.getenv("ProgramFiles(x86)"), System.getenv("LOCALAPPDATA")}) {
                if (base != null) {
                    found.add(Paths.get(base, "LibreOffice", "program", "soffice.exe"));
                }
            }
        } else if (os.contains("mac")) {
            found.add(Paths.get("/Applications/LibreOffice.app/Contents/MacOS/soffice"));
            found.add(Paths.get(System.getProperty("user.home"),
                    "Applications/LibreOffice.app/Contents/MacOS/soffice"));
        } else {
            for (String path : new String[] {"/usr/bin/soffice", "/usr/local/bin/soffice",
                    "/usr/lib/libreoffice/program/soffice", "/snap/bin/libreoffice",
                    "/opt/libreoffice/program/soffice"}) {
                found.add(Paths.get(path));
            }
            found.addAll(globProgramDirectories(Paths.get("/opt")));
        }
        found.add(onPath(os.contains("win") ? "soffice.exe" : "soffice"));
        return found;
    }

    private static List<Path> globProgramDirectories(Path parent) {
        if (!Files.isDirectory(parent)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(parent)) {
            return children
                    .filter(p -> p.getFileName().toString().startsWith("libreoffice"))
                    .map(p -> p.resolve("program/soffice"))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static List<Path> windowsRegistryCandidates() {
        List<Path> found = new ArrayList<>();
        for (String key : new String[] {
            "HKLM\\SOFTWARE\\LibreOffice\\UNO\\InstallPath",
            "HKLM\\SOFTWARE\\WOW6432Node\\LibreOffice\\UNO\\InstallPath",
            "HKCU\\SOFTWARE\\LibreOffice\\UNO\\InstallPath"}) {
            String value = registryDefault(key);
            if (value != null) {
                // The UNO key already points at the program directory.
                found.add(Paths.get(value, "soffice.exe"));
            }
        }
        String appPath = registryDefault(
                "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\soffice.exe");
        if (appPath != null) {
            found.add(Paths.get(appPath));
        }
        return found;
    }

    private static String registryDefault(String key) {
        try {
            Process process = new ProcessBuilder("reg", "query", key, "/ve")
                    .redirectErrorStream(true).start();
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
     * On Windows soffice.exe detaches from the console and prints nothing, so
     * version queries and any other output must go through soffice.com.
     */
    private static Path consoleVariant(Path executable) {
        if (executable.getFileName().toString().equalsIgnoreCase("soffice.exe")) {
            Path console = executable.resolveSibling("soffice.com");
            if (Files.isRegularFile(console)) {
                return console;
            }
        }
        return executable;
    }

    private static String readVersion(Path console, Path executable) {
        try {
            Process process = new ProcessBuilder(console.toString(), "--version")
                    .redirectErrorStream(true).start();
            for (String line : read(process, 20).split("\\R")) {
                if (line.contains("LibreOffice")) {
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

    /** Opens a document in LibreOffice. The file is edited and saved in place. */
    public static void open(Path file) throws IOException {
        launch(List.of(file.toAbsolutePath().toString()));
    }

    /** Starts one of the LibreOffice modules with an empty document. */
    public static void start(Module module) throws IOException {
        launch(List.of(module.flag));
    }

    private static void launch(List<String> arguments) throws IOException {
        Install install = find().orElseThrow(
                () -> new IOException("LibreOffice is not installed"));
        List<String> command = new ArrayList<>();
        command.add(install.executable().toString());
        command.addAll(arguments);
        new ProcessBuilder(command).directory(install.programDirectory().toFile()).start();
    }

    /**
     * Converts a file with {@code --convert-to}, returning the converted file.
     * JavaOS only reaches for this with legacy binary formats its own engine
     * does not read, such as {@code .doc} and {@code .ppt}.
     *
     * <p>Runs on a private profile: a headless conversion sharing a profile with
     * a LibreOffice window the user already has open silently does nothing.
     */
    public static Path convert(Path source, String filter, Path outputDirectory)
            throws IOException {
        Install install = find().orElseThrow(
                () -> new IOException("LibreOffice is not installed"));
        Files.createDirectories(outputDirectory);

        Path profile = Paths.get(System.getProperty("user.home"), ".javaos", "lo-profile");
        Files.createDirectories(profile);

        Process process = new ProcessBuilder(
                install.console().toString(),
                "--headless", "--norestore", "--invisible", "--nolockcheck",
                "-env:UserInstallation=" + profile.toUri(),
                "--convert-to", filter,
                "--outdir", outputDirectory.toAbsolutePath().toString(),
                source.toAbsolutePath().toString())
                .redirectErrorStream(true).start();
        String output = read(process, 120);

        String base = source.getFileName().toString();
        int dot = base.lastIndexOf('.');
        String stem = dot < 0 ? base : base.substring(0, dot);
        String extension = filter.contains(":") ? filter.substring(0, filter.indexOf(':')) : filter;
        Path produced = outputDirectory.resolve(stem + "." + extension);
        if (!Files.isRegularFile(produced)) {
            throw new IOException("LibreOffice did not produce " + produced.getFileName()
                    + (output.isBlank() ? "" : "\n\n" + output.strip()));
        }
        return produced;
    }

    // ---- modules and file types ----------------------------------------

    /** The LibreOffice modules JavaOS offers to hand a document to. */
    public enum Module {
        WRITER("Writer", "--writer"),
        CALC("Calc", "--calc"),
        IMPRESS("Impress", "--impress"),
        DRAW("Draw", "--draw"),
        BASE("Base", "--base");

        public final String label;
        public final String flag;

        Module(String label, String flag) {
            this.label = label;
            this.flag = flag;
        }
    }

    /** Formats JavaOS hands straight to LibreOffice because it cannot read them. */
    public static final List<String> HANDOVER_ONLY = List.of(
            "doc", "ppt", "pptx", "odp", "otp", "odg", "otg", "vsd", "vsdx", "pdf",
            "mdb", "accdb", "odb");
}

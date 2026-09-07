package javaos.sys;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A real command interpreter, kept alive as a child process and fed one line at
 * a time. On Windows that is PowerShell; elsewhere it is pwsh where it is
 * installed and the login shell where it is not.
 *
 * <p>The session is persistent, which is the whole point. {@code cd},
 * variables, functions, imported modules and the dot-sourced profile all
 * survive from one command to the next, exactly as they would in a console
 * window.
 *
 * <h2>Knowing when a command has finished</h2>
 *
 * <p>A pipe has no prompt to wait for -- PowerShell only draws one when its
 * input is a console -- so each command is followed on the same line by a
 * synthetic one that prints a marker carrying the verdict, the exit code and
 * the working directory. The reader thread scans for that marker: everything
 * before it is output, and its arrival is the prompt coming back. The marker
 * carries a per-session UUID, so a command that happens to print the word
 * cannot be mistaken for one.
 *
 * <h2>Why the command travels as base64</h2>
 *
 * <p>Three things go wrong if the typed line is written to the pipe as it
 * stands. {@code powershell -Command -} reads strictly one line at a time and
 * discards anything that does not parse on its own, so the marker cannot go on
 * a line of its own: a command that reads standard input -- {@code Read-Host},
 * {@code git commit}, any prompt -- would swallow it and hang the terminal
 * forever. Put on the same line after a semicolon, the marker is instead eaten
 * by a trailing {@code # comment}. And PowerShell 5.1 decodes a redirected
 * standard input with the OEM code page whatever {@code [Console]::InputEncoding}
 * is set to afterwards, so an accent in a path arrives as mojibake.
 *
 * <p>Wrapping the line in base64 and handing it to {@code Invoke-Expression}
 * settles all three: the wire stays single-line and pure ASCII, the marker sits
 * safely outside the quoted text, and standard input past that line belongs to
 * the command. {@code Invoke-Expression} runs in the caller's scope, so
 * assignments, {@code cd} and function definitions still persist. What the user
 * sees is unchanged, because PowerShell re-parses the decoded text and reports
 * errors against it.
 *
 * <p>Callbacks arrive on the reader thread, not the event thread. Swing callers
 * are expected to hop across themselves.
 */
public final class HostShell implements AutoCloseable {

    /** True when the host speaks PowerShell and backslashes. */
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /** The character that starts every escape sequence. */
    private static final String ESCAPE = "\u001B";

    /** Colour and cursor control that a text area would render as mojibake. */
    private static final Pattern ANSI =
            Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B[@-Z\\\\-_]");

    /**
     * Where the verdict is parked between the command finishing and the marker
     * printing. It cannot simply be {@code $?}, because that would report on
     * {@code Invoke-Expression} rather than on what it ran.
     */
    private static final String VERDICT = "$global:JavaOsCommandSucceeded";

    /** What the terminal wants to hear about. */
    public interface Listener {

        /** A chunk of shell output. Not necessarily a whole line. */
        void output(String text);

        /**
         * The command finished and the prompt is free again.
         *
         * @param succeeded the shell's own verdict -- PowerShell's {@code $?}
         * @param exitCode the last exit code, which on PowerShell belongs to
         *        the last native program and may predate this command
         */
        void ready(String workingDirectory, boolean succeeded, int exitCode);

        /** The interpreter itself went away. */
        void ended(String reason);
    }

    private final Listener listener;
    private final String marker = "@@JAVAOS:" + UUID.randomUUID() + ":";
    private final Process process;
    private final Writer stdin;
    private final String name;
    private final boolean powerShell;

    private volatile boolean busy;
    private volatile boolean closing;

    /**
     * Starts an interpreter in {@code workingDirectory}.
     *
     * @throws IOException when none could be started, which the terminal
     *         reports rather than pretending it has a shell
     */
    public HostShell(File workingDirectory, Listener listener) throws IOException {
        this.listener = listener;
        List<String> command = interpreter();
        this.name = new File(command.get(0)).getName();
        String lower = name.toLowerCase(Locale.ROOT);
        this.powerShell = lower.startsWith("pwsh") || lower.startsWith("powershell");

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        if (workingDirectory != null && workingDirectory.isDirectory()) {
            builder.directory(workingDirectory);
        }
        this.process = builder.start();
        this.stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);

        Thread reader = new Thread(this::pump, "host-shell-reader");
        reader.setDaemon(true);
        reader.start();

        initialise();
    }

    /** The interpreter's file name, for the banner. */
    public String name() {
        return name;
    }

    /** True while a command is still running and the prompt is not free. */
    public boolean isBusy() {
        return busy;
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    /** Runs one command line and asks for the marker once it is done. */
    public void send(String commandLine) {
        busy = true;
        write(framed(commandLine) + "\n");
    }

    /** Feeds a line to a command already running, for Read-Host and its kind. */
    public void write(String text) {
        try {
            stdin.write(text);
            stdin.flush();
        } catch (IOException e) {
            // Nothing will ever answer now, so the command cannot still be
            // running: leaving busy set would wedge the prompt for good.
            busy = false;
            if (!closing) {
                listener.ended("could not write to " + name + ": " + e.getMessage());
            }
        }
    }

    /**
     * Ends the session and silences it. Nothing more reaches the listener after
     * this returns, which matters because a terminal that restarts its shell
     * has already pointed the listener at a replacement, and a last callback
     * from the old one would report its directory over the new one's.
     */
    @Override public void close() {
        closing = true;
        try {
            stdin.close();
        } catch (IOException ignored) {
            // The interpreter is going away regardless.
        }
        process.destroy();
    }

    // ---- the interpreter -----------------------------------------------

    /**
     * PowerShell where there is one, and the user's own login shell where there
     * is not. The profile is deliberately loaded: a terminal whose aliases and
     * functions are missing is not the user's shell, only an imitation of it.
     */
    private static List<String> interpreter() throws IOException {
        if (WINDOWS) {
            String pwsh = onPath("pwsh.exe");
            if (pwsh != null) {
                return List.of(pwsh, "-NoLogo", "-Command", "-");
            }
            String root = System.getenv("SystemRoot");
            String bundled = (root == null ? "C:\\Windows" : root)
                    + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe";
            String powershell = Files.isExecutable(Paths.get(bundled))
                    ? bundled : onPath("powershell.exe");
            if (powershell == null) {
                throw new IOException("no PowerShell found on this machine");
            }
            return List.of(powershell, "-NoLogo", "-Command", "-");
        }
        String pwsh = onPath("pwsh");
        if (pwsh != null) {
            return List.of(pwsh, "-NoLogo", "-Command", "-");
        }
        String login = System.getenv("SHELL");
        return List.of(login == null || login.isBlank() ? "/bin/sh" : login, "-s");
    }

    private static String onPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String entry : path.split(File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            try {
                Path candidate = Paths.get(entry, executable);
                if (Files.isExecutable(candidate) && !Files.isDirectory(candidate)) {
                    return candidate.toString();
                }
            } catch (RuntimeException ignored) {
                // A malformed PATH entry is not worth failing the launch over.
            }
        }
        return null;
    }

    /**
     * Settles the session before the user's first command: UTF-8 out, so
     * accented file names survive the pipe, and no progress bars, which are
     * drawn with cursor control a text area cannot honour. The empty command
     * that follows is what produces the opening prompt.
     */
    private void initialise() {
        if (powerShell) {
            write("try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch { }\n"
                    + "try { [Console]::InputEncoding = [Text.Encoding]::UTF8 } catch { }\n"
                    + "$OutputEncoding = [Text.Encoding]::UTF8\n"
                    + "$ProgressPreference = 'SilentlyContinue'\n");
        }
        busy = true;
        write(framed("") + "\n");
    }

    /** One command and its marker, as the single line that goes down the pipe. */
    private String framed(String commandLine) {
        if (powerShell) {
            // The command is the first line of the encoded script and nothing
            // else is, so PowerShell's "At line:1 char:1" points where the user
            // would expect. The verdict is cleared outside the encoding, for
            // the same reason.
            String script = commandLine + "\n" + VERDICT + " = $?";
            String encoded = Base64.getEncoder().encodeToString(
                    script.getBytes(StandardCharsets.UTF_8));
            // The catch is what keeps a terminating error from taking the
            // marker down with it and leaving the terminal busy for good.
            // Out-Default is not decoration. Without it PowerShell hands the
            // whole try statement's objects to the formatter only once the
            // statement is over, which is to say after the finally block has
            // already announced the prompt -- and a listing would then arrive
            // underneath the prompt that was meant to follow it.
            return "try { " + VERDICT + " = $false; "
                    + "Invoke-Expression ([Text.Encoding]::UTF8.GetString("
                    + "[Convert]::FromBase64String('" + encoded + "'))) | Out-Default } "
                    // Rendered here too, and for the same reason: an error left
                    // to the statement's end would print below its own prompt.
                    + "catch { ($_ | Out-String) | Out-Default } "
                    // Write-Host rather than [Console]::Out, so the marker goes
                    // through the same writer as everything else the shell
                    // prints. The two buffer separately, and a listing flushed
                    // in halves around a marker written past them is how the
                    // prompt ends up in the middle of a table.
                    + "finally { Write-Host (\"" + marker + "\" + "
                    + "$(if (" + VERDICT + ") { 'ok' } else { 'no' }) + \":\" + "
                    + "$LASTEXITCODE + \":\" + (Get-Location).Path) }";
        }
        // Quoting rather than base64: it costs nothing on a shell that reads
        // standard input the same way, and keeps a # from eating the marker.
        return "eval " + singleQuoted(commandLine)
                + "; __javaos=$?; printf '" + marker + "%s:%s:%s\\n' "
                + "\"$(if [ $__javaos -eq 0 ]; then echo ok; else echo no; fi)\" "
                + "\"$__javaos\" \"$PWD\"";
    }

    /** Bourne single-quoting, which ends the quote to spell a quote. */
    private static String singleQuoted(String text) {
        return "'" + text.replace("'", "'\\''") + "'";
    }

    // ---- reading -------------------------------------------------------

    /**
     * Drains the interpreter's output, splitting it at every marker.
     *
     * <p>Reads are chunks, not lines, so a marker can straddle two of them.
     * Text is therefore only released once no marker can begin in it:
     * everything up to the last {@code marker.length() - 1} characters, which
     * are held back until the next read proves them innocent.
     */
    private void pump() {
        StringBuilder buffer = new StringBuilder();
        char[] chunk = new char[4096];
        try (Reader out = new InputStreamReader(process.getInputStream(),
                StandardCharsets.UTF_8)) {
            int read;
            while ((read = out.read(chunk)) >= 0) {
                buffer.append(chunk, 0, read);
                drain(buffer, false);
            }
        } catch (IOException e) {
            busy = false;
            if (!closing) {
                listener.ended(name + " stopped: " + e.getMessage());
            }
            return;
        }
        drain(buffer, true);
        busy = false;
        if (closing) {
            return;
        }
        // Standard output can reach end of file a moment before the process
        // itself is reaped, and exitValue() throws while that is true.
        String how;
        try {
            how = "exited with code " + process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            how = "stopped";
        }
        listener.ended(name + " " + how);
    }

    /** Emits everything in {@code buffer} that is safely past a marker boundary. */
    private void drain(StringBuilder buffer, boolean atEnd) {
        while (true) {
            int at = buffer.indexOf(marker);
            if (at < 0) {
                break;
            }
            int newline = buffer.indexOf("\n", at);
            if (newline < 0 && !atEnd) {
                // The marker line is still arriving; emit only what precedes it.
                emit(buffer, at);
                return;
            }
            emit(buffer, at);
            int end = newline < 0 ? buffer.length() : newline - at + 1;
            String line = buffer.substring(marker.length(), end);
            buffer.delete(0, end);
            announce(line.strip());
        }
        emit(buffer, atEnd ? buffer.length() : safeEnd(buffer));
    }

    /**
     * How much of the buffer can be released without cutting through something
     * that has to be read whole.
     *
     * <p>Three things straddle a read. A marker, which is why all but its
     * length is held back. An escape sequence, which would otherwise lose its
     * escape and print as {@code [32m}. And a carriage return, which is not yet
     * known to be a lone one: cut there and the newline completing it arrives
     * on its own next time, so one line ending becomes two and a long listing
     * comes out double spaced.
     */
    private int safeEnd(StringBuilder buffer) {
        int safe = Math.max(0, buffer.length() - marker.length() + 1);
        int escape = safe == 0 ? -1 : buffer.lastIndexOf(ESCAPE, safe - 1);
        if (escape >= 0 && safe - escape <= 24
                && !ANSI.matcher(buffer.substring(escape, safe)).lookingAt()) {
            safe = escape;
        }
        if (safe > 0 && buffer.charAt(safe - 1) == '\r') {
            safe--;
        }
        return safe;
    }

    /** Hands the first {@code count} characters of the buffer to the listener. */
    private void emit(StringBuilder buffer, int count) {
        if (count <= 0) {
            return;
        }
        String text = buffer.substring(0, count);
        buffer.delete(0, count);
        if (closing) {
            return;
        }
        text = ANSI.matcher(text).replaceAll("").replace("\r\n", "\n").replace('\r', '\n');
        if (!text.isEmpty()) {
            listener.output(text);
        }
    }

    /** Parses {@code <ok|no>:<exit code>:<working directory>} and frees the prompt. */
    private void announce(String tail) {
        String[] parts = tail.split(":", 3);
        boolean succeeded = !parts[0].equals("no");
        int exit = 0;
        try {
            // PowerShell leaves $LASTEXITCODE unset until a native program runs.
            exit = parts.length < 2 || parts[1].isBlank()
                    ? 0 : Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException ignored) {
            // A shell that reported something unparseable is not itself a failure.
        }
        busy = false;
        if (!closing) {
            listener.ready(parts.length < 3 ? "" : parts[2], succeeded, exit);
        }
    }

    /** The directory to start in, falling back to home when the path is unusable. */
    public static File directoryOrHome(Path candidate) {
        try {
            if (candidate != null && Files.isDirectory(candidate)) {
                return candidate.toFile();
            }
        } catch (RuntimeException ignored) {
            // My Computer and its like have no host directory; use home.
        }
        return new File(System.getProperty("user.home", "."));
    }
}

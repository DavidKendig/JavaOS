package javaos.apps;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import javaos.desktop.Shell;
import javaos.sys.HostShell;
import javaos.ui.Icons;
import javaos.vfs.Vfs;

/**
 * A terminal: green on black, and a real PowerShell underneath.
 *
 * <p>This window used to be a simulation -- forty commands reimplemented in
 * Java over the file system, which looked like a shell and behaved like one
 * only for as long as you stayed inside the forty. It now runs a persistent
 * {@link HostShell}, so what you type is what the host's own interpreter
 * receives, and everything the machine can do it can do: pipelines, modules,
 * {@code git}, {@code winget}, the user's own profile functions.
 *
 * <p>Six commands are still answered here rather than sent, and all six are
 * about this window rather than the machine: {@code clear} and {@code exit},
 * whose console meanings do not survive a pipe; {@code edit}, {@code apps} and
 * {@code launch}, which reach into the desktop; and {@code javaos}, which
 * explains the arrangement. A line beginning with a backslash skips the lot and
 * goes straight to the shell.
 */
public class TerminalApp extends AppWindow {

    private static final Color SCREEN = new Color(0x10, 0x14, 0x10);
    private static final Color PHOSPHOR = new Color(0x7C, 0xE8, 0x7C);

    /** Enough scrollback to read, little enough that a recursive listing cannot eat the heap. */
    private static final int SCROLLBACK = 400_000;

    private final JTextArea output = new JTextArea();
    private final JTextField input = new JTextField();
    private final JLabel prompt = new JLabel();
    private final List<String> history = new ArrayList<>();

    /**
     * Lines typed before the shell drew its first prompt. An interpreter takes
     * a fraction of a second to start, and anything sent in that window would
     * reach it as bare standard input rather than as a command -- it would run,
     * but unframed, so its output would arrive around the prompt instead of
     * before it. They wait here instead of being dropped.
     */
    private final Deque<String> queued = new ArrayDeque<>();

    private HostShell host;
    private String hostCwd;
    private boolean started;
    private int historyCursor;

    /** Counts the shells this window has started, so stale callbacks can be told apart. */
    private int generation;

    public TerminalApp(Shell shell, String argument) {
        super(shell, "Terminal", Icons.terminal(16));
        setSize(760, 470);

        Font mono = new Font("Monospaced", Font.PLAIN, 13);
        output.setEditable(false);
        output.setBackground(SCREEN);
        output.setForeground(PHOSPHOR);
        output.setFont(mono);
        output.setCaretColor(PHOSPHOR);
        output.setLineWrap(true);
        output.setWrapStyleWord(true);
        output.setBorder(BorderFactory.createEmptyBorder(6, 8, 2, 8));

        prompt.setFont(mono);
        prompt.setForeground(PHOSPHOR);
        prompt.setBackground(SCREEN);
        prompt.setOpaque(true);
        prompt.setBorder(BorderFactory.createEmptyBorder(2, 8, 6, 2));

        input.setFont(mono);
        input.setBackground(SCREEN);
        input.setForeground(Color.WHITE);
        input.setCaretColor(PHOSPHOR);
        input.setBorder(BorderFactory.createEmptyBorder(2, 0, 6, 8));
        input.addActionListener(e -> submit());
        input.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_UP) {
                    recall(-1);
                } else if (e.getKeyCode() == KeyEvent.VK_DOWN) {
                    recall(1);
                } else if (e.getKeyCode() == KeyEvent.VK_L && e.isControlDown()) {
                    output.setText("");
                } else if (e.getKeyCode() == KeyEvent.VK_C && e.isControlDown()
                        && input.getSelectedText() == null) {
                    e.consume();
                    cancel();
                }
            }
        });

        JPanel line = new JPanel(new BorderLayout());
        line.setBackground(SCREEN);
        line.add(prompt, BorderLayout.WEST);
        line.add(input, BorderLayout.CENTER);

        JPanel screen = new JPanel(new BorderLayout());
        screen.setBackground(SCREEN);
        JScrollPane scroll = new JScrollPane(output);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(SCREEN);
        screen.add(scroll, BorderLayout.CENTER);
        screen.add(line, BorderLayout.SOUTH);
        screen.setBorder(javaos.ui.Ui.sunkenPanel());
        setBody(screen);

        print(javaos.Version.NAME + " Terminal " + javaos.Version.NUMBER
                + "   (type 'javaos' for the window's own commands)\n");
        start(startingDirectory(argument));
        EventQueue.invokeLater(input::requestFocusInWindow);
    }

    /** Where "Terminal Here" wants us, or the user's home when it asked for nowhere. */
    private File startingDirectory(String argument) {
        Path candidate = null;
        if (argument != null && vfs().isDirectory(argument)) {
            try {
                candidate = vfs().host(argument);
            } catch (RuntimeException ignored) {
                // My Computer has no host directory; home will do.
            }
        }
        return HostShell.directoryOrHome(candidate);
    }

    // ---- the shell underneath ------------------------------------------

    /**
     * Starts an interpreter, replacing any that is already running.
     *
     * <p>The directory is checked rather than trusted, because the shell's own
     * idea of where it is need not be a folder at all: {@code cd HKLM:} leaves
     * PowerShell somewhere perfectly valid that nothing can be launched in.
     */
    private void start(File directory) {
        if (host != null) {
            host.close();
        }
        File where = directory != null && directory.isDirectory()
                ? directory : new File(System.getProperty("user.home", "."));
        hostCwd = where.getPath();
        started = false;
        queued.clear();
        refreshPrompt();
        // Every callback carries the number of the shell that raised it. The
        // outgoing shell's last words are already on the event queue by the
        // time its replacement starts, and without this they would land on the
        // new one -- reporting the old directory, or freeing a prompt that the
        // new shell has not offered yet.
        int mine = ++generation;
        try {
            host = new HostShell(where, new HostShell.Listener() {
                @Override public void output(String text) {
                    EventQueue.invokeLater(() -> {
                        if (mine == generation) {
                            append(text);
                        }
                    });
                }

                // Qualified, because an unqualified ready() here would find
                // this listener's own method rather than the window's.
                @Override public void ready(String at, boolean ok, int exitCode) {
                    EventQueue.invokeLater(() -> {
                        if (mine == generation) {
                            TerminalApp.this.ready(at, ok, exitCode);
                        }
                    });
                }

                @Override public void ended(String reason) {
                    EventQueue.invokeLater(() -> {
                        if (mine != generation) {
                            return;
                        }
                        print("\n[" + reason + " -- Ctrl+C starts a new one]");
                        prompt.setText("");
                        status("the shell has stopped");
                    });
                }
            });
            print("Connected to " + host.name() + ". Commands go straight to it.\n");
        } catch (IOException e) {
            host = null;
            print("Could not start a shell: " + e.getMessage());
            status("no shell");
        }
    }

    /** The prompt has come back: note where the shell now is, and how it fared. */
    private void ready(String workingDirectory, boolean succeeded, int exitCode) {
        started = true;
        if (workingDirectory != null && !workingDirectory.isBlank()) {
            hostCwd = workingDirectory;
        }
        refreshPrompt();
        if (!succeeded) {
            // The exit code belongs to the last program, not necessarily to
            // this command, so it is only worth showing when there is one.
            status(hostCwd + "    failed" + (exitCode == 0 ? "" : " (exit " + exitCode + ")"));
        }
        if (!queued.isEmpty() && host != null && !host.isBusy()) {
            run(queued.poll());
            refreshPrompt();
        }
    }

    /**
     * Stops whatever is running. There is no way to deliver a console Ctrl+C to
     * a child whose standard input is a pipe, so this ends the interpreter and
     * starts a fresh one in the same folder -- the command dies, at the cost of
     * the session's variables. It is also the way back from a shell that has
     * stopped on its own, after {@code exit} or a crash. When there is a live
     * shell sitting idle, it only clears the line, as a console would.
     */
    private void cancel() {
        if (host != null && host.isBusy()) {
            print("^C");
            start(new File(hostCwd));
        } else if (host == null || !host.isAlive()) {
            start(new File(hostCwd));
        } else {
            input.setText("");
        }
    }

    private void refreshPrompt() {
        boolean busy = host != null && host.isBusy();
        prompt.setText(busy ? "" : "PS " + hostCwd + "> ");
        status(hostCwd);
        setTitle(new File(hostCwd).getName() + " - Terminal");
    }

    // ---- the screen ----------------------------------------------------

    /** Appends a chunk of shell output, trimming the scrollback when it grows. */
    private void append(String text) {
        output.append(text);
        int length = output.getDocument().getLength();
        if (length > SCROLLBACK) {
            output.replaceRange("", 0, length - SCROLLBACK);
        }
        output.setCaretPosition(output.getDocument().getLength());
    }

    private void print(String text) {
        append(text + "\n");
    }

    /** Adds a command to the history Up and Down walk, ignoring empty lines. */
    private void remember(String line) {
        if (!line.isBlank()) {
            history.add(line);
        }
        historyCursor = history.size();
    }

    private void recall(int direction) {
        if (history.isEmpty()) {
            return;
        }
        historyCursor = Math.max(0, Math.min(history.size(), historyCursor + direction));
        input.setText(historyCursor < history.size() ? history.get(historyCursor) : "");
    }

    /**
     * Enter. While a command is running the line is stdin -- what Read-Host and
     * every prompting program is waiting for -- and only when the prompt is
     * free is it a command.
     */
    private void submit() {
        String line = input.getText();
        input.setText("");
        if (host == null || !host.isAlive()) {
            print("no shell is running. Ctrl+C starts one.");
            return;
        }
        if (!started) {
            print(line);
            remember(line);
            queued.add(line);
            return;
        }
        if (host.isBusy()) {
            print(line);
            host.write(line + "\n");
            return;
        }
        print(prompt.getText() + line);
        remember(line);
        run(line);
        refreshPrompt();
    }

    // ---- the six commands this window keeps ----------------------------

    /**
     * A guard around the built-ins. They reach into the file system and the
     * desktop, either of which can refuse -- an unwritable folder, a working
     * directory the shell is happy with but Java cannot spell, such as the one
     * {@code cd Cert:} leaves behind. A terminal reports that on its own screen;
     * letting it out would put a stack trace on the desktop instead, and skip
     * the prompt that should follow.
     */
    private void run(String line) {
        try {
            dispatch(line);
        } catch (RuntimeException e) {
            print("error: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    private void dispatch(String line) {
        String trimmed = line.strip();
        if (trimmed.startsWith("\\")) {
            // The escape hatch: send it even if it names one of ours.
            host.send(trimmed.substring(1));
            return;
        }
        List<String> parts = tokenize(trimmed);
        String command = parts.get(0).toLowerCase();
        String first = parts.size() > 1 ? parts.get(1) : null;

        switch (command) {
            case "clear", "cls" -> output.setText("");
            case "exit", "logout" -> doDefaultCloseAction();
            case "javaos" -> printHelp();
            case "apps" -> {
                for (App app : shell.apps()) {
                    print(String.format("  %-14s %s", app.id(), app.description()));
                }
            }
            case "launch" -> {
                if (first == null) {
                    print("usage: launch <application-id>   (see 'apps')");
                } else if (shell.launch(first) == null) {
                    print("no such application: " + first);
                }
            }
            case "edit" -> edit(first);
            default -> host.send(line);
        }
    }

    /** Opens a file in whichever JavaOS application claims it. */
    private void edit(String argument) {
        if (argument == null) {
            print("usage: edit <path>");
            return;
        }
        String here;
        try {
            here = Vfs.toVirtual(new File(hostCwd).toPath());
        } catch (RuntimeException e) {
            // The shell is somewhere with no file system behind it, after a
            // cd into the registry or the certificate store.
            print("edit: " + hostCwd + " is not a folder; give a full path");
            return;
        }
        String path = Vfs.resolve(here, argument);
        if (!vfs().exists(path)) {
            vfs().write(path, "");
        }
        shell.openFile(path);
        print("opening " + Vfs.name(path) + "...");
    }

    private void printHelp() {
        print("""
              Everything you type goes to the host shell. These six do not:

                clear, cls    clear this screen
                exit, logout  close the window
                javaos        this list
                apps          the applications the desktop can launch
                launch <id>   launch one of them
                edit <path>   open a file in a JavaOS application

              Prefix a line with \\ to send it to the shell regardless.
              Up and Down recall history, Ctrl+L clears the screen, and
              Ctrl+C stops a running command by restarting the shell.
              While a command runs, what you type is fed to its input.
              """.stripTrailing());
    }

    // ---- helpers -------------------------------------------------------

    @Override public void dispose() {
        if (host != null) {
            host.close();
            host = null;
        }
        super.dispose();
    }

    /** Splits on whitespace, honouring double quotes around arguments with spaces. */
    private static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean quoted = false;
        for (char ch : line.toCharArray()) {
            if (ch == '"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(ch) && !quoted) {
                if (token.length() > 0) {
                    tokens.add(token.toString());
                    token.setLength(0);
                }
            } else {
                token.append(ch);
            }
        }
        if (token.length() > 0) {
            tokens.add(token.toString());
        }
        return tokens.isEmpty() ? Arrays.asList("") : tokens;
    }
}

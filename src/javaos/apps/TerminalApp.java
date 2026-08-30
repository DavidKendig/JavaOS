package javaos.apps;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import javaos.desktop.Shell;
import javaos.ui.Icons;
import javaos.vfs.Vfs;

/** A shell over the volume: green on black, forty commands, no actual kernel. */
public class TerminalApp extends AppWindow {

    private static final Color SCREEN = new Color(0x10, 0x14, 0x10);
    private static final Color PHOSPHOR = new Color(0x7C, 0xE8, 0x7C);

    private final JTextArea output = new JTextArea();
    private final JTextField input = new JTextField();
    private final JLabel prompt = new JLabel();
    private final List<String> history = new ArrayList<>();

    private String cwd;
    private int historyCursor;

    public TerminalApp(Shell shell, String argument) {
        super(shell, "Terminal", Icons.terminal(16));
        setSize(680, 430);
        this.cwd = argument != null && vfs().isDirectory(argument)
                ? Vfs.normalize(argument) : Vfs.HOME;

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

        print(javaos.Version.NAME + " Terminal " + javaos.Version.NUMBER + "   (type 'help' for commands)");
        print("");
        if (vfs().exists("/etc/motd")) {
            print(vfs().read("/etc/motd").strip());
            print("");
        }
        refreshPrompt();
        java.awt.EventQueue.invokeLater(input::requestFocusInWindow);
    }

    private void refreshPrompt() {
        prompt.setText(shell.settings().userName() + "@javaos:" + shortCwd() + "$ ");
        status(cwd);
        setTitle(Vfs.name(cwd) + " - Terminal");
    }

    private String shortCwd() {
        return cwd.startsWith(Vfs.HOME) ? "~" + cwd.substring(Vfs.HOME.length()) : cwd;
    }

    private void print(String text) {
        output.append(text + "\n");
        output.setCaretPosition(output.getDocument().getLength());
    }

    private void recall(int direction) {
        if (history.isEmpty()) {
            return;
        }
        historyCursor = Math.max(0, Math.min(history.size(), historyCursor + direction));
        input.setText(historyCursor < history.size() ? history.get(historyCursor) : "");
    }

    private void submit() {
        String line = input.getText();
        input.setText("");
        print(prompt.getText() + line);
        if (!line.isBlank()) {
            history.add(line);
            historyCursor = history.size();
            try {
                run(line.trim());
            } catch (RuntimeException e) {
                print("error: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }
        refreshPrompt();
    }

    // ---- commands ------------------------------------------------------

    private void run(String line) {
        List<String> parts = tokenize(line);
        String command = parts.get(0);
        List<String> args = parts.subList(1, parts.size());
        String first = args.isEmpty() ? null : args.get(0);

        switch (command) {
            case "help" -> printHelp();
            case "ls", "dir" -> list(first == null ? cwd : resolve(first));
            case "cd" -> changeDirectory(first);
            case "pwd" -> print(cwd);
            case "cat", "type" -> requireFile(first, path -> print(vfs().read(path).stripTrailing()));
            case "head" -> requireFile(first, path -> lines(path, 10, true));
            case "tail" -> requireFile(first, path -> lines(path, 10, false));
            case "wc" -> requireFile(first, path -> {
                String text = vfs().read(path);
                print(text.lines().count() + " lines, "
                        + text.split("\\s+").length + " words, "
                        + text.length() + " chars   " + Vfs.name(path));
            });
            case "grep" -> grep(args);
            case "echo" -> print(String.join(" ", args));
            case "mkdir" -> {
                require(first, "mkdir <folder>");
                vfs().mkdirs(resolve(first));
                print("created " + resolve(first));
            }
            case "touch" -> {
                require(first, "touch <file>");
                vfs().touch(resolve(first));
            }
            case "rm", "del" -> {
                require(first, "rm <path>");
                String path = resolve(first);
                if (!vfs().exists(path)) {
                    print("rm: no such file: " + first);
                } else {
                    vfs().delete(path);
                    print("removed " + path);
                }
            }
            case "cp" -> {
                requireTwo(args, "cp <source> <target>");
                vfs().copy(resolve(args.get(0)), resolve(args.get(1)));
                print("copied");
            }
            case "mv" -> {
                requireTwo(args, "mv <source> <target>");
                vfs().move(resolve(args.get(0)), resolve(args.get(1)));
                print("moved");
            }
            case "tree" -> tree(first == null ? cwd : resolve(first), "", 0);
            case "edit", "open" -> {
                require(first, command + " <path>");
                String path = resolve(first);
                if (!vfs().exists(path)) {
                    vfs().write(path, "");
                }
                shell.openFile(path);
                print("opening " + Vfs.name(path) + "...");
            }
            case "run" -> {
                require(first, "run <application-id>");
                if (shell.launch(first) == null) {
                    print("no such application: " + first);
                }
            }
            case "apps" -> {
                for (App app : shell.apps()) {
                    print(String.format("  %-14s %s", app.id(), app.description()));
                }
            }
            case "ps" -> {
                javax.swing.JInternalFrame[] frames = shell.pane().getAllFrames();
                print(String.format("  %-5s %-28s %s", "PID", "WINDOW", "STATE"));
                int pid = 100;
                for (javax.swing.JInternalFrame frame : frames) {
                    print(String.format("  %-5d %-28s %s", pid++, frame.getTitle(),
                            frame.isIcon() ? "minimised" : "running"));
                }
            }
            case "df" -> printDiskUsage();
            case "free" -> {
                Runtime rt = Runtime.getRuntime();
                print("  heap used  " + Vfs.humanSize(rt.totalMemory() - rt.freeMemory()));
                print("  heap total " + Vfs.humanSize(rt.totalMemory()));
                print("  heap max   " + Vfs.humanSize(rt.maxMemory()));
            }
            case "date" -> print(new java.util.Date().toString());
            case "whoami" -> print(shell.settings().userName());
            case "uname" -> print(javaos.Version.FULL + " (Swing " + System.getProperty("java.version")
                    + ") on " + System.getProperty("os.name"));
            case "neofetch" -> neofetch();
            case "motd" -> print(vfs().exists("/etc/motd") ? vfs().read("/etc/motd").strip()
                    : "no message today");
            case "history" -> {
                for (int i = 0; i < history.size(); i++) {
                    print(String.format("  %3d  %s", i + 1, history.get(i)));
                }
            }
            case "clear", "cls" -> output.setText("");
            case "sudo" -> print("You are already the only user on this machine.");
            case "java" -> print("Write once, run anywhere. Even here.");
            case "exit", "logout" -> doDefaultCloseAction();
            default -> print(command + ": command not found. Try 'help'.");
        }
    }

    private void printHelp() {
        print("""
              Files      ls  cd  pwd  cat  head  tail  wc  grep  tree
                         mkdir  touch  rm  cp  mv  edit  open
              System     apps  run  ps  df  free  date  whoami  uname
                         neofetch  motd  history  clear  exit
              Shortcuts  Up/Down recall history, Ctrl+L clears the screen
              """.stripTrailing());
    }

    /** Free and used space on the drive holding the working directory. */
    private void printDiskUsage() {
        long total = vfs().totalSpace(cwd);
        if (total == 0) {
            print("  no disk information for " + cwd);
            return;
        }
        long used = vfs().usedSpace(cwd);
        print(String.format("  %-12s %10s %10s %10s  %s", "FILESYSTEM", "SIZE", "USED",
                "FREE", "MOUNTED ON"));
        print(String.format("  %-12s %10s %10s %10s  %s",
                Vfs.name(driveOf(cwd)), Vfs.humanSize(total), Vfs.humanSize(used),
                Vfs.humanSize(vfs().freeSpace(cwd)), driveOf(cwd)));
    }

    /** The drive a path sits on, for df to name. */
    private static String driveOf(String path) {
        String p = Vfs.normalize(path);
        while (!Vfs.isDrive(p) && !Vfs.isRoot(p)) {
            p = Vfs.parent(p);
        }
        return p;
    }

    private void neofetch() {
        String[] facts = {
            "  " + shell.settings().userName() + "@javaos",
            "  ------------------",
            "  OS       " + javaos.Version.FULL + " (Metal, " + shell.settings().theme().label + " theme)",
            "  Kernel   " + System.getProperty("java.vm.name"),
            "  Java     " + System.getProperty("java.version"),
            "  Host     " + System.getProperty("os.name") + " "
                    + System.getProperty("os.arch"),
            "  Shell    javash",
            "  WM       JDesktopPane",
            "  Disk     " + Vfs.humanSize(vfs().freeSpace(cwd)) + " free",
            "  Windows  " + shell.pane().getAllFrames().length + " open",
        };
        String[] art = {
            "      .-\"\"\"-.  ",
            "     /  o o  \\ ",
            "    |    ^    |",
            "    |  \\___/  |",
            "     \\       / ",
            "      '.___.'  ",
            "     duke says ",
            "     hello     ",
        };
        int lines = Math.max(facts.length, art.length);
        for (int i = 0; i < lines; i++) {
            String left = i < art.length ? art[i] : "               ";
            String right = i < facts.length ? facts[i] : "";
            print(String.format("%-16s%s", left, right));
        }
    }

    private void list(String dir) {
        if (!vfs().isDirectory(dir)) {
            print("ls: not a folder: " + dir);
            return;
        }
        List<String> entries = vfs().list(dir);
        if (entries.isEmpty()) {
            print("  (empty)");
            return;
        }
        for (String entry : entries) {
            boolean isDir = vfs().isDirectory(entry);
            print(String.format("  %s  %10s  %s",
                    isDir ? "d" : "-",
                    isDir ? "" : Vfs.humanSize(vfs().size(entry)),
                    Vfs.name(entry) + (isDir ? "/" : "")));
        }
    }

    private void tree(String dir, String indent, int depth) {
        if (depth == 0) {
            print(dir);
        }
        if (depth > 4) {
            print(indent + "...");
            return;
        }
        List<String> entries = vfs().list(dir);
        for (int i = 0; i < entries.size(); i++) {
            String entry = entries.get(i);
            boolean last = i == entries.size() - 1;
            print(indent + (last ? "`-- " : "|-- ") + Vfs.name(entry));
            if (vfs().isDirectory(entry)) {
                tree(entry, indent + (last ? "    " : "|   "), depth + 1);
            }
        }
    }

    private void grep(List<String> args) {
        if (args.size() < 2) {
            print("usage: grep <text> <file>");
            return;
        }
        String needle = args.get(0);
        String path = resolve(args.get(1));
        if (!vfs().exists(path)) {
            print("grep: no such file: " + args.get(1));
            return;
        }
        int number = 0;
        int hits = 0;
        for (String line : vfs().read(path).split("\n")) {
            number++;
            if (line.toLowerCase().contains(needle.toLowerCase())) {
                print(String.format("  %4d: %s", number, line));
                hits++;
            }
        }
        if (hits == 0) {
            print("  no matches");
        }
    }

    private void lines(String path, int count, boolean fromStart) {
        String[] all = vfs().read(path).split("\n");
        int from = fromStart ? 0 : Math.max(0, all.length - count);
        int to = fromStart ? Math.min(count, all.length) : all.length;
        for (int i = from; i < to; i++) {
            print(all[i]);
        }
    }

    private void changeDirectory(String target) {
        String path = target == null ? Vfs.HOME : resolve(target);
        if (!vfs().isDirectory(path)) {
            print("cd: no such folder: " + (target == null ? path : target));
            return;
        }
        cwd = path;
    }

    // ---- helpers -------------------------------------------------------

    private String resolve(String path) {
        return Vfs.resolve(cwd, path);
    }

    private void require(String argument, String usage) {
        if (argument == null) {
            throw new IllegalArgumentException("usage: " + usage);
        }
    }

    private void requireTwo(List<String> args, String usage) {
        if (args.size() < 2) {
            throw new IllegalArgumentException("usage: " + usage);
        }
    }

    private void requireFile(String argument, java.util.function.Consumer<String> body) {
        require(argument, "expects a file name");
        String path = resolve(argument);
        if (!vfs().exists(path) || vfs().isDirectory(path)) {
            print("no such file: " + argument);
            return;
        }
        body.accept(path);
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

package javaos.desktop;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.PropertyVetoException;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JDesktopPane;
import javax.swing.JFrame;
import javax.swing.JInternalFrame;
import javax.swing.JLayeredPane;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import javaos.Settings;
import javaos.apps.App;
import javaos.apps.AppWindow;
import javaos.apps.Apps;
import javaos.ui.Icons;
import javaos.ui.SunTheme;
import javaos.vfs.Vfs;

/** The session: one frame, one desktop pane, one taskbar, and everything running inside. */
public class JavaOsDesktop implements Shell {

    private final Vfs vfs;
    private final Settings settings;
    private final List<App> apps = Apps.installed();

    private final JFrame frame = new JFrame("JavaOS");
    private final DesktopPane pane;
    private final LaunchMenu launchMenu;
    private final Taskbar taskbar;

    private int cascade;
    private LockScreen lockScreen;
    private boolean locked;

    public JavaOsDesktop(Vfs vfs, Settings settings) {
        this.vfs = vfs;
        this.settings = settings;
        this.pane = new DesktopPane(settings);
        this.launchMenu = new LaunchMenu(this);
        this.taskbar = new Taskbar(this, settings, launchMenu);

        JPanel root = new JPanel(new BorderLayout());
        root.add(pane, BorderLayout.CENTER);
        root.add(taskbar, BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                shutDown();
            }
        });
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        frame.setSize(Math.min(1280, screen.width - 60), Math.min(820, screen.height - 60));
        frame.setLocationRelativeTo(null);
        frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
        frame.setIconImage(frameIcon());

        installDesktopMenu();
        installShortcuts();
        settings.onChange(this::applySettings);
    }

    private java.awt.Image frameIcon() {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        javaos.ui.JavaLogo.paint(g, 2, 2, 60);
        g.dispose();
        return img;
    }

    public void start() {
        frame.setVisible(true);
        installDesktopIcons();
        status("JavaOS ready. " + apps.size() + " applications installed.");
    }

    // ---- Shell ---------------------------------------------------------

    @Override public Vfs vfs() {
        return vfs;
    }

    @Override public Settings settings() {
        return settings;
    }

    @Override public JDesktopPane pane() {
        return pane;
    }

    @Override public JFrame frame() {
        return frame;
    }

    @Override public List<App> apps() {
        return apps;
    }

    @Override public AppWindow launch(String appId) {
        return launch(appId, null);
    }

    @Override public AppWindow launch(String appId, String argument) {
        App app = apps.stream().filter(a -> a.id().equals(appId)).findFirst().orElse(null);
        if (app == null) {
            status("No such application: " + appId);
            return null;
        }
        // A second launch of a single-document app with the same argument just refocuses it.
        try {
            AppWindow window = app.create(this, argument);
            // The office applications hand documents to an installed Office or
            // LibreOffice when there is one, and build no JavaOS window at all.
            // They have already said so in the status bar.
            if (window == null) {
                return null;
            }
            show(window);
            status("Started " + app.name() + ".");
            return window;
        } catch (RuntimeException e) {
            JOptionPane.showMessageDialog(frame,
                    app.name() + " could not start.\n\n" + e,
                    "JavaOS", JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    @Override public AppWindow openFile(String path) {
        if (vfs.isDirectory(path)) {
            return launch("filemanager", path);
        }
        String ext = Vfs.extension(path);
        // Compressed audio and every video format need codecs the JDK does not
        // carry. Hand those to VLC when it is here; when it is not, the Media
        // Player opens and says so, which beats a dialog with nowhere to go.
        if (javaos.media.Media.kindOf(path) == javaos.media.Media.Kind.HANDOVER
                && javaos.vlc.Vlc.isAvailable()) {
            try {
                javaos.vlc.Vlc.open(vfs.host(path));
                status("Opened " + Vfs.name(path) + " in VLC.");
                return null;
            } catch (java.io.IOException e) {
                JOptionPane.showMessageDialog(frame,
                        "Could not start VLC.\n\n" + e.getMessage(),
                        "JavaOS", JOptionPane.ERROR_MESSAGE);
                return null;
            }
        }
        for (App app : apps) {
            for (String candidate : app.extensions()) {
                if (candidate.equals(ext)) {
                    return launch(app.id(), path);
                }
            }
        }
        // Presentations, drawings and the pre-2007 binary formats are the ones
        // JavaOS does not read itself. Hand those over when LibreOffice is here.
        if (javaos.soffice.LibreOffice.HANDOVER_ONLY.contains(ext)) {
            if (javaos.soffice.LibreOffice.isAvailable()) {
                try {
                    javaos.soffice.LibreOffice.open(vfs.host(path));
                    status("Opened " + Vfs.name(path) + " in LibreOffice.");
                    return null;
                } catch (java.io.IOException e) {
                    JOptionPane.showMessageDialog(frame,
                            "Could not start LibreOffice.\n\n" + e.getMessage(),
                            "JavaOS", JOptionPane.ERROR_MESSAGE);
                    return null;
                }
            }
            JOptionPane.showMessageDialog(frame,
                    Vfs.name(path) + " is a " + ext.toUpperCase() + " file.\n\n"
                            + "JavaOS reads ODF and OOXML text and spreadsheets on its own,\n"
                            + "but this format needs LibreOffice, which is not installed.",
                    "JavaOS", JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        return launch("writer", path);
    }

    @Override public void show(AppWindow window) {
        if (window.getParent() == null) {
            place(window);
            pane.add(window);
            taskbar.track(window);
        }
        window.setVisible(true);
        try {
            window.setSelected(true);
        } catch (PropertyVetoException ignored) {
            // Fine: the window simply opens unfocused.
        }
        window.toFront();
    }

    private void place(JInternalFrame window) {
        int step = 26;
        int max = Math.max(1, (pane.getHeight() - window.getHeight() - 40) / step);
        int slot = cascade++ % max;
        window.setLocation(30 + slot * step, 20 + slot * step);
    }

    @Override public void status(String message) {
        taskbar.message(message);
    }

    /**
     * Seals the session. The lock screen goes up as the frame's glass pane, so
     * every window underneath keeps running but nothing can reach it.
     */
    @Override public void lockScreen() {
        if (locked) {
            return;
        }
        if (lockScreen == null) {
            lockScreen = new LockScreen(settings, this::unlockScreen);
        }
        locked = true;
        frame.setGlassPane(lockScreen);
        lockScreen.setVisible(true);
        lockScreen.engage();
        status("Session locked.");
    }

    /** Releases the session, handed to the lock screen to call when it is satisfied. */
    public void unlockScreen() {
        if (!locked) {
            return;
        }
        locked = false;
        lockScreen.release();
        lockScreen.setVisible(false);
        frame.requestFocus();
        status("Welcome back, " + settings.userName() + ".");
    }

    @Override public void shutDown() {
        if (locked) {
            return;         // nothing gets past the lock screen, the frame's X included
        }
        Object[] options = {"Shut Down", "Restart", "Cancel"};
        int choice = JOptionPane.showOptionDialog(frame,
                "End the JavaOS session?\n\nUnsaved documents will prompt before closing.",
                "Shut Down", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                Icons.power(32), options, options[2]);
        if (choice == 2 || choice == JOptionPane.CLOSED_OPTION) {
            return;
        }
        for (JInternalFrame f : pane.getAllFrames()) {
            f.doDefaultCloseAction();
            if (f.isVisible() && f.getParent() != null) {
                return; // Somebody cancelled at a save prompt.
            }
        }
        settings.save();
        if (choice == 1) {
            frame.dispose();
            SwingUtilities.invokeLater(() -> {
                SunTheme.install(settings.theme());
                JavaOsDesktop restarted = new JavaOsDesktop(vfs, Settings.defaults());
                restarted.start();
            });
        } else {
            frame.dispose();
            System.exit(0);
        }
    }

    // ---- desktop furniture ---------------------------------------------

    /**
     * Two shortcuts, and no application launcher among them. Everything else
     * lives in the Launch menu; My Computer opens the host file system at its
     * drives, and the Terminal is the other way in.
     */
    private void installDesktopIcons() {
        addIcon(Icons.computer(32), "My Computer", 20, 20,
                () -> launch("filemanager", Vfs.ROOT));
        addIcon(Icons.terminal(32), "Terminal", 20, 110, () -> launch("terminal"));
    }

    private void addIcon(Icon icon, String label, int x, int y, Runnable action) {
        DesktopIcon desktopIcon = new DesktopIcon(icon, label, action);
        desktopIcon.setLocation(x, y);
        pane.add(desktopIcon, JLayeredPane.FRAME_CONTENT_LAYER);
    }

    private void installDesktopMenu() {
        JPopupMenu menu = new JPopupMenu();
        menu.add(item("New Terminal Here", Icons.terminal(16), () -> launch("terminal")));
        menu.add(item("New Document", Icons.newDoc(16), () -> launch("writer")));
        menu.addSeparator();
        menu.add(item("Arrange Windows", Icons.computer(16), this::tileWindows));
        menu.add(item("Minimise All", Icons.arrow(16, 1), this::minimiseAll));
        menu.addSeparator();
        menu.add(item("Change Backdrop...", Icons.paint(16),
                () -> launch("settings", "desktop")));
        menu.add(item("Control Panel", Icons.settings(16), () -> launch("settings")));
        menu.addSeparator();
        menu.add(item("About JavaOS", Icons.info(16), () -> launch("about")));

        pane.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                maybeShow(e);
            }

            @Override public void mouseReleased(MouseEvent e) {
                maybeShow(e);
            }

            @Override public void mouseClicked(MouseEvent e) {
                for (java.awt.Component c : pane.getComponents()) {
                    if (c instanceof DesktopIcon icon) {
                        icon.setSelected(false);
                    }
                }
            }

            private void maybeShow(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    menu.show(pane, e.getX(), e.getY());
                }
            }
        });
    }

    private JMenuItem item(String text, Icon icon, Runnable action) {
        JMenuItem menuItem = new JMenuItem(text, icon);
        menuItem.addActionListener(e -> action.run());
        return menuItem;
    }

    private void tileWindows() {
        JInternalFrame[] frames = pane.getAllFrames();
        int visible = 0;
        for (JInternalFrame f : frames) {
            if (!f.isIcon()) {
                visible++;
            }
        }
        if (visible == 0) {
            return;
        }
        int cols = (int) Math.ceil(Math.sqrt(visible));
        int rows = (int) Math.ceil(visible / (double) cols);
        int w = pane.getWidth() / cols;
        int h = pane.getHeight() / rows;
        int index = 0;
        for (JInternalFrame f : frames) {
            if (f.isIcon()) {
                continue;
            }
            try {
                f.setMaximum(false);
            } catch (PropertyVetoException ignored) {
                // Leave maximised windows alone.
            }
            f.setBounds((index % cols) * w, (index / cols) * h, w, h);
            index++;
        }
    }

    private void minimiseAll() {
        for (JInternalFrame f : pane.getAllFrames()) {
            try {
                f.setIcon(true);
            } catch (PropertyVetoException ignored) {
                // Some windows refuse; that is their prerogative.
            }
        }
    }

    private void installShortcuts() {
        JComponent root = frame.getRootPane();
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_T,
                InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK),
                "terminal", () -> launch("terminal"));
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, InputEvent.CTRL_DOWN_MASK),
                "launch", () -> launchMenu.show(taskbar, 4, -launchMenu.height()));
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0), "about", () -> launch("about"));
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_D,
                InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK),
                "minimise", this::minimiseAll);
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_L,
                InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK),
                "lock", this::lockScreen);
    }

    private void bind(JComponent c, KeyStroke stroke, String name, Runnable action) {
        c.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(stroke, name);
        c.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (locked) {
                    return;     // the lock screen is the only thing listening
                }
                action.run();
            }
        });
    }

    /** Re-themes every live window after the Control Panel changes the look. */
    public void applySettings() {
        SunTheme.install(settings.theme());
        SwingUtilities.updateComponentTreeUI(frame);
        for (java.awt.Window w : JFrame.getWindows()) {
            SwingUtilities.updateComponentTreeUI(w);
        }
        SwingUtilities.updateComponentTreeUI(launchMenu);
        pane.applySettings();
        taskbar.repaint();
        settings.save();
    }
}

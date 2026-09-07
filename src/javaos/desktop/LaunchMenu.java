package javaos.desktop;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.geom.AffineTransform;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.border.AbstractBorder;

import javaos.apps.App;
import javaos.ui.Icons;
import javaos.ui.JavaLogo;
import javaos.ui.Ui;
import javaos.vfs.Vfs;

/** The Launch menu, banner stripe and all. Rebuilt each time it is shown. */
public class LaunchMenu extends JPopupMenu {

    private final Shell shell;

    public LaunchMenu(Shell shell) {
        this.shell = shell;
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createRaisedBevelBorder(),
                new BannerBorder()));
        rebuild();
    }

    public int height() {
        return getPreferredSize().height;
    }

    /** Groups the installed applications by category, in menu order. */
    public void rebuild() {
        removeAll();

        Map<String, java.util.List<App>> byCategory = new LinkedHashMap<>();
        for (String category : List.of("Office", "Accessories", "Games", "System")) {
            byCategory.put(category, new java.util.ArrayList<>());
        }
        for (App app : shell.apps()) {
            if (app.inLaunchMenu()) {
                byCategory.computeIfAbsent(app.category(),
                        k -> new java.util.ArrayList<>()).add(app);
            }
        }

        for (Map.Entry<String, java.util.List<App>> entry : byCategory.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            JMenu submenu = new JMenu(entry.getKey());
            submenu.setFont(new Font("Dialog", Font.BOLD, 12));
            submenu.setIcon(categoryIcon(entry.getKey()));
            for (App app : entry.getValue()) {
                submenu.add(appItem(app));
            }
            add(submenu);
        }

        addSeparator();
        add(documentsMenu());

        JMenuItem computer = new JMenuItem("My Computer", Icons.computer(16));
        computer.addActionListener(e -> shell.launch("filemanager", Vfs.ROOT));
        add(computer);

        JMenuItem home = new JMenuItem("Home Folder", Icons.folderOpen(16));
        home.addActionListener(e -> shell.launch("filemanager", Vfs.HOME));
        add(home);

        addSeparator();
        JMenuItem about = new JMenuItem("About JavaOS", Icons.info(16));
        about.addActionListener(e -> shell.launch("about"));
        add(about);

        JMenuItem lock = new JMenuItem("Lock Screen", Icons.lock(16));
        lock.addActionListener(e -> shell.lockScreen());
        add(lock);

        JMenuItem shutdown = new JMenuItem("Shut Down...", Icons.power(16));
        shutdown.setFont(new Font("Dialog", Font.BOLD, 12));
        shutdown.addActionListener(e -> shell.shutDown());
        add(shutdown);
    }

    private JMenuItem appItem(App app) {
        JMenuItem item = new JMenuItem(app.name(), app.icon(16));
        item.setToolTipText(app.description());
        item.addActionListener(e -> shell.launch(app.id()));
        return item;
    }

    private JMenu documentsMenu() {
        JMenu documents = new JMenu("Documents");
        documents.setIcon(Icons.document(16));
        String dir = shell.vfs().firstDirectory(Vfs.HOME + "/Documents");
        List<String> files = shell.vfs().exists(dir) ? shell.vfs().list(dir) : List.of();
        if (files.isEmpty()) {
            JMenuItem empty = new JMenuItem("(empty)");
            empty.setEnabled(false);
            documents.add(empty);
        } else {
            for (String path : files) {
                JMenuItem item = new JMenuItem(Vfs.name(path),
                        shell.vfs().isDirectory(path) ? Icons.folder(16) : Icons.document(16));
                item.addActionListener(e -> shell.openFile(path));
                documents.add(item);
            }
        }
        documents.addSeparator();
        JMenuItem browse = new JMenuItem("Browse...", Icons.folderOpen(16));
        browse.addActionListener(e -> shell.launch("filemanager", dir));
        documents.add(browse);
        return documents;
    }

    private javax.swing.Icon categoryIcon(String category) {
        return switch (category) {
            case "Office" -> Icons.textDocument(16);
            case "Accessories" -> Icons.calculator(16);
            case "Games" -> Icons.mine(16);
            case "System" -> Icons.settings(16);
            default -> Icons.document(16);
        };
    }

    @Override public void show(Component invoker, int x, int y) {
        rebuild();
        super.show(invoker, x, y);
    }

    /** The vertical product stripe down the left edge -- pure 1998, and worth every pixel. */
    private static class BannerBorder extends AbstractBorder {

        private static final int STRIPE = 28;

        @Override public Insets getBorderInsets(Component c) {
            return new Insets(3, STRIPE + 3, 3, 3);
        }

        @Override public Insets getBorderInsets(Component c, Insets insets) {
            insets.set(3, STRIPE + 3, 3, 3);
            return insets;
        }

        @Override public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            Graphics2D g2 = Ui.smooth(g);
            g2.translate(x, y);
            Ui.vGradient(g2, 2, 2, STRIPE, h - 4,
                    Ui.mix(Ui.accent(), Color.BLACK, 0.25), Ui.accentLight());
            g2.setColor(Ui.darker(Ui.accent(), 0.4));
            g2.drawRect(2, 2, STRIPE - 1, h - 5);

            // A white halo under the logo: the stripe is too dark for blue on its own.
            JavaLogo.paintAt(g2, 6, h - 29, 24, new Color(255, 255, 255, 190));
            JavaLogo.paintAt(g2, 7, h - 28, 22);

            AffineTransform old = g2.getTransform();
            g2.rotate(-Math.PI / 2, 16, h - 40);
            g2.setFont(new Font("Dialog", Font.BOLD, 15));
            g2.setColor(new Color(0, 0, 0, 90));
            g2.drawString(javaos.Version.FULL, 25, h - 34);
            g2.setColor(Color.WHITE);
            g2.drawString(javaos.Version.FULL, 24, h - 35);
            g2.setTransform(old);
            g2.dispose();
        }
    }
}

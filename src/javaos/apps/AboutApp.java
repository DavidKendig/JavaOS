package javaos.apps;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextArea;

import javaos.desktop.Shell;
import javaos.ui.Icons;
import javaos.ui.JavaLogo;
import javaos.ui.Ui;

/** The About box, styled after the splash screens that made you wait in 2002. */
public class AboutApp extends AppWindow {

    public AboutApp(Shell shell, String argument) {
        super(shell, "About JavaOS", Icons.info(16));
        setSize(470, 480);
        setResizable(false);
        setMaximizable(false);
        setTitle("About JavaOS");

        Runtime rt = Runtime.getRuntime();
        JTextArea details = new JTextArea("""
                JavaOS Desktop Environment, %s

                A desktop written entirely in Swing: the window manager,
                the file system, the applications and every icon on screen
                are drawn by the toolkit, with no images on disk.

                  Java        %s (%s)
                  Toolkit     Metal look and feel, %s theme
                  Host        %s %s
                  Heap        %s of %s
                  Volume      %s
                  User        %s

                Duke is drawn in Java2D; the Java logo is the official
                artwork, rendered here from its own vector paths.
                """.formatted(
                javaos.Version.RELEASE,
                System.getProperty("java.version"), System.getProperty("java.vm.name"),
                shell.settings().theme().label,
                System.getProperty("os.name"), System.getProperty("os.arch"),
                javaos.vfs.Vfs.humanSize(rt.totalMemory() - rt.freeMemory()),
                javaos.vfs.Vfs.humanSize(rt.maxMemory()),
                shell.vfs().realRoot().toString(),
                shell.settings().userName()));
        details.setEditable(false);
        details.setFont(new Font("Monospaced", Font.PLAIN, 11));
        details.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        details.setBackground(Ui.control());

        JButton ok = new JButton("OK");
        ok.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 6));
        buttons.add(ok);

        JPanel root = new JPanel(new BorderLayout());
        root.add(new Banner(), BorderLayout.NORTH);
        root.add(details, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        setBody(root);
        status("Press F1 anywhere on the desktop to see this again.");
    }

    /** The splash banner: gradient, cup, wordmark, hairline rule. */
    static class Banner extends JComponent {

        Banner() {
            setPreferredSize(new Dimension(440, 118));
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = Ui.smooth(g);
            int w = getWidth();
            int h = getHeight();
            Ui.hGradient(g2, 0, 0, w, h,
                    Ui.darker(Ui.accent(), 0.45), Ui.mix(Ui.accentLight(), Color.WHITE, 0.35));
            g2.setColor(new Color(255, 255, 255, 40));
            for (int i = 0; i < h; i += 4) {
                g2.drawLine(0, i, w, i);
            }

            JavaLogo.paint(g2, 20, 10, 92);

            g2.setFont(new Font("Dialog", Font.BOLD, 34));
            Ui.drawOutlinedText(g2, "JavaOS", 124, 58, Color.WHITE, new Color(0, 0, 0, 110));
            g2.setFont(new Font("Dialog", Font.BOLD, 12));
            g2.setColor(new Color(255, 255, 255, 230));
            g2.drawString("Desktop Environment  ·  " + javaos.Version.RELEASE, 126, 78);
            g2.setFont(new Font("Dialog", Font.PLAIN, 11));
            g2.setColor(new Color(255, 255, 255, 190));
            g2.drawString("Written in Swing, in the manner of the age", 126, 94);

            g2.setColor(Ui.darkShadow());
            g2.drawLine(0, h - 1, w, h - 1);
            g2.dispose();
        }
    }
}

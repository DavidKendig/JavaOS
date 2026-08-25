package javaos.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;

/** The start-up splash: logo, progress bar, and a list of things to pretend to load. */
public final class SplashScreen extends JWindow {

    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JLabel step = new JLabel("Starting...");

    public SplashScreen() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Ui.darkShadow()),
                BorderFactory.createRaisedBevelBorder()));

        step.setFont(new Font("Dialog", Font.PLAIN, 11));
        step.setBorder(BorderFactory.createEmptyBorder(4, 8, 2, 8));
        progress.setPreferredSize(new Dimension(0, 14));
        progress.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

        JPanel south = new JPanel(new BorderLayout());
        south.add(step, BorderLayout.NORTH);
        south.add(progress, BorderLayout.SOUTH);

        root.add(new Art(), BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);
        pack();
        setSize(420, 280);
        setLocationRelativeTo(null);
    }

    /** Advances the bar and the caption; safe to call from the boot thread. */
    public void progress(int percent, String message) {
        SwingUtilities.invokeLater(() -> {
            progress.setValue(percent);
            step.setText(message);
        });
    }

    /** Runs the staged boot messages, then closes. Blocks the calling thread. */
    public void runBootSequence() {
        String[][] stages = {
            {"12", "Mounting volume..."},
            {"28", "Loading Metal look and feel..."},
            {"44", "Starting window manager..."},
            {"62", "Registering applications..."},
            {"78", "Reading desktop preferences..."},
            {"92", "Painting the backdrop..."},
            {"100", "Ready."},
        };
        for (String[] stage : stages) {
            progress(Integer.parseInt(stage[0]), stage[1]);
            try {
                Thread.sleep(190);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** The artwork panel: gradient, cup, wordmark, and the mandatory legal line. */
    private static class Art extends JComponent {
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = Ui.smooth(g);
            int w = getWidth();
            int h = getHeight();
            Ui.vGradient(g2, 0, 0, w, h, new Color(0x24, 0x46, 0x78), new Color(0x8F, 0xA8, 0xC8));
            g2.setColor(new Color(255, 255, 255, 30));
            for (int i = 0; i < h; i += 3) {
                g2.drawLine(0, i, w, i);
            }

            JavaLogo.paint(g2, 30, 26, 120);

            g2.setFont(new Font("Dialog", Font.BOLD, 42));
            Ui.drawOutlinedText(g2, "JavaOS", 158, 92, Color.WHITE, new Color(0, 0, 0, 120));
            g2.setFont(new Font("Dialog", Font.BOLD, 13));
            g2.setColor(Color.WHITE);
            g2.drawString("Desktop Environment", 162, 114);
            g2.setFont(new Font("Dialog", Font.PLAIN, 11));
            g2.setColor(new Color(255, 255, 255, 200));
            g2.drawString(javaos.Version.RELEASE, 162, 132);
            g2.setFont(new Font("Dialog", Font.PLAIN, 10));
            g2.setColor(new Color(255, 255, 255, 170));
            g2.drawString("A Swing desktop. Duke appears with affection.", 24, h - 12);
            g2.dispose();
        }
    }
}

package javaos.desktop;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.geom.Point2D;

import javax.swing.JDesktopPane;

import javaos.Settings;
import javaos.ui.JavaLogo;
import javaos.ui.Ui;

/** The backdrop: a painted wallpaper, a watermark cup, and a corner build stamp. */
public class DesktopPane extends JDesktopPane {

    private final Settings settings;

    public DesktopPane(Settings settings) {
        this.settings = settings;
        setBackground(new Color(0x1B, 0x3A, 0x5C));
        setDesktopManager(new SnappingDesktopManager());
        setDragMode(settings.outlineDrag() ? OUTLINE_DRAG_MODE : LIVE_DRAG_MODE);
    }

    public void applySettings() {
        setDragMode(settings.outlineDrag() ? OUTLINE_DRAG_MODE : LIVE_DRAG_MODE);
        repaint();
    }

    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = Ui.smooth(g);
        int w = getWidth();
        int h = getHeight();
        paintWallpaper(g2, w, h, settings.wallpaper());

        if (settings.showLogo()) {
            // Logo, wordmark and release number share one centre line, stacked
            // in that order and anchored to the bottom-right of the backdrop.
            Font wordFont = new Font("Dialog", Font.BOLD, 26);
            Font releaseFont = new Font("Dialog", Font.PLAIN, 11);
            int wordWidth = g2.getFontMetrics(wordFont).stringWidth("JavaOS");
            int releaseWidth = g2.getFontMetrics(releaseFont).stringWidth(javaos.Version.RELEASE);
            int logo = 200;
            int column = Math.max(logo, Math.max(wordWidth, releaseWidth));
            int centre = w - 40 - column / 2;
            int releaseBase = h - 30;
            int wordBase = releaseBase - 20;

            java.awt.Composite old = g2.getComposite();
            g2.setComposite(java.awt.AlphaComposite.getInstance(
                    java.awt.AlphaComposite.SRC_OVER, 0.20f));
            JavaLogo.paintAt(g2, centre - JavaLogo.width(logo) / 2,
                    wordBase - 26 - JavaLogo.height(logo), logo);
            g2.setComposite(old);

            g2.setFont(wordFont);
            Ui.drawOutlinedText(g2, "JavaOS", centre - wordWidth / 2, wordBase,
                    new Color(255, 255, 255, 120), new Color(0, 0, 0, 90));
            g2.setFont(releaseFont);
            Ui.drawOutlinedText(g2, javaos.Version.RELEASE, centre - releaseWidth / 2, releaseBase,
                    new Color(255, 255, 255, 90), new Color(0, 0, 0, 70));
        }
        g2.dispose();
    }

    /**
     * Paints one backdrop style. Extracted so the lock screen can lay down the
     * same wallpaper the session is using, dimmed, instead of a flat colour.
     */
    static void paintWallpaper(Graphics2D g2, int w, int h, Settings.Wallpaper style) {
        Color deep = Ui.darker(Ui.accent(), 0.55);
        Color mid = Ui.darker(Ui.accent(), 0.15);
        Color pale = Ui.mix(Ui.accentLight(), Ui.accent(), 0.35);

        switch (style) {
            case HORIZON -> {
                Ui.vGradient(g2, 0, 0, w, h, deep, mid);
                g2.setPaint(new RadialGradientPaint(new Point2D.Float(w * 0.5f, h * 1.05f),
                        Math.max(w, h) * 0.75f,
                        new float[] {0f, 1f},
                        new Color[] {new Color(pale.getRed(), pale.getGreen(), pale.getBlue(), 150),
                                new Color(pale.getRed(), pale.getGreen(), pale.getBlue(), 0)}));
                g2.fillRect(0, 0, w, h);
            }
            case RAYS -> {
                Ui.vGradient(g2, 0, 0, w, h, mid, deep);
                g2.setColor(new Color(255, 255, 255, 16));
                for (int i = 0; i < 24; i++) {
                    double a = Math.PI * 2 * i / 24 + 0.12;
                    int[] xs = {0, (int) (Math.cos(a) * w * 2), (int) (Math.cos(a + 0.06) * w * 2)};
                    int[] ys = {h, h - (int) (Math.sin(a) * h * 2),
                            h - (int) (Math.sin(a + 0.06) * h * 2)};
                    g2.fillPolygon(xs, ys, 3);
                }
            }
            case GRID -> {
                g2.setColor(deep);
                g2.fillRect(0, 0, w, h);
                g2.setColor(new Color(255, 255, 255, 22));
                for (int x = 0; x < w; x += 24) {
                    g2.drawLine(x, 0, x, h);
                }
                for (int y = 0; y < h; y += 24) {
                    g2.drawLine(0, y, w, y);
                }
                g2.setColor(new Color(255, 255, 255, 38));
                for (int x = 0; x < w; x += 120) {
                    g2.drawLine(x, 0, x, h);
                }
                for (int y = 0; y < h; y += 120) {
                    g2.drawLine(0, y, w, y);
                }
            }
            case WEAVE -> {
                Ui.vGradient(g2, 0, 0, w, h, mid, deep);
                g2.setColor(new Color(0, 0, 0, 26));
                for (int y = 0; y < h; y += 4) {
                    g2.drawLine(0, y, w, y);
                }
                g2.setColor(new Color(255, 255, 255, 12));
                for (int x = 0; x < w; x += 4) {
                    g2.drawLine(x, 0, x, h);
                }
            }
            case FLAT -> {
                g2.setColor(mid);
                g2.fillRect(0, 0, w, h);
            }
            default -> {
                g2.setColor(mid);
                g2.fillRect(0, 0, w, h);
            }
        }
    }
}

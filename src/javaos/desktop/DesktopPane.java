package javaos.desktop;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;

import javax.swing.JDesktopPane;

import javaos.Settings;
import javaos.ui.JavaLogo;
import javaos.ui.Ui;
import javaos.ui.Wallpapers;

/** The backdrop: a painted wallpaper, a watermark cup, and a corner build stamp. */
public class DesktopPane extends JDesktopPane {

    private final Settings settings;

    public DesktopPane(Settings settings) {
        this.settings = settings;
        setBackground(FACET_MID);
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
     * The facet palette, sampled off the Solaris 11 desktop this backdrop is
     * modelled on: a desaturated blue-grey, near enough to no hue at all, with
     * the whole range of the picture between the darkest and lightest shard.
     *
     * <p>These are fixed rather than mixed out of {@link Ui#accent()} the way
     * the other five backdrops are. The look is the point here, and a Solaris
     * wallpaper tinted ochre or emerald stops being the thing that was asked
     * for; the other styles still follow the colour scheme.
     */
    static final Color FACET_DARK = new Color(0x66, 0x80, 0x8F);
    static final Color FACET_MID = new Color(0x93, 0xAB, 0xB7);
    static final Color FACET_LIGHT = new Color(0xBA, 0xCA, 0xD2);

    /**
     * Paints one backdrop style. Extracted so the lock screen can lay down the
     * same wallpaper the session is using, dimmed, instead of a flat colour.
     */
    static void paintWallpaper(Graphics2D g2, int w, int h, Settings.Wallpaper style) {
        // A bundled image, when the style names one and it reads.
        if (style.isImage() && Wallpapers.paint(g2, w, h, style.resource)) {
            return;
        }
        if (style == Settings.Wallpaper.FACETS) {
            paintFacets(g2, w, h);
            return;
        }
        // Either the low-poly backdrop is not what was asked for, or a bundled
        // image would not load. Both land on a plain wash rather than nothing.
        g2.setColor(Ui.darker(Ui.accent(), 0.15));
        g2.fillRect(0, 0, w, h);
    }

    /**
     * The low-poly backdrop: a jittered grid of points cut into triangles, each
     * shaded flat, brightest under a soft light above and left of centre and
     * falling away to the corners.
     *
     * <p>Everything here comes from the cell indices by way of {@link #dither},
     * which is a hash rather than a generator. That matters: the backdrop is
     * repainted on every expose, and a {@code Random} would deal a different
     * wallpaper each time. The same cell always yields the same shard, so the
     * picture holds still, and it is regenerated rather than stored, so it fits
     * whatever size the window is dragged to.
     */
    private static void paintFacets(Graphics2D g2, int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        // A wash underneath, so any hairline between two shards shows a
        // plausible colour rather than the bare component.
        Ui.vGradient(g2, 0, 0, w, h, Ui.lighter(FACET_MID, 0.10), Ui.darker(FACET_MID, 0.12));

        // Shards about 190px across, the proportion the original uses, but never
        // so few that the pattern reads as a handful of wedges on a small pane.
        int columns = Math.max(6, Math.round(w / 190f));
        int rows = Math.max(4, Math.round(h / 190f));
        float cellWidth = w / (float) columns;
        float cellHeight = h / (float) rows;

        float[][] xs = new float[columns + 1][rows + 1];
        float[][] ys = new float[columns + 1][rows + 1];
        for (int i = 0; i <= columns; i++) {
            for (int j = 0; j <= rows; j++) {
                // Edge points stay pinned to the border; only the inner lattice
                // wanders, or the backdrop would not reach its own corners.
                float jitterX = i == 0 || i == columns ? 0f
                        : (float) (dither(i, j, 1) - 0.5) * cellWidth * 0.62f;
                float jitterY = j == 0 || j == rows ? 0f
                        : (float) (dither(i, j, 2) - 0.5) * cellHeight * 0.62f;
                xs[i][j] = i * cellWidth + jitterX;
                ys[i][j] = j * cellHeight + jitterY;
            }
        }

        for (int i = 0; i < columns; i++) {
            for (int j = 0; j < rows; j++) {
                // Which way each cell splits is dithered too, so the shards do
                // not all lean the same way and give the grid away.
                if (dither(i, j, 3) < 0.5) {
                    shard(g2, w, h, i, j, 0,
                            xs[i][j], ys[i][j], xs[i + 1][j], ys[i + 1][j],
                            xs[i][j + 1], ys[i][j + 1]);
                    shard(g2, w, h, i, j, 1,
                            xs[i + 1][j], ys[i + 1][j], xs[i + 1][j + 1], ys[i + 1][j + 1],
                            xs[i][j + 1], ys[i][j + 1]);
                } else {
                    shard(g2, w, h, i, j, 0,
                            xs[i][j], ys[i][j], xs[i + 1][j], ys[i + 1][j],
                            xs[i + 1][j + 1], ys[i + 1][j + 1]);
                    shard(g2, w, h, i, j, 1,
                            xs[i][j], ys[i][j], xs[i + 1][j + 1], ys[i + 1][j + 1],
                            xs[i][j + 1], ys[i][j + 1]);
                }
            }
        }
    }

    /**
     * One triangle, flat-filled and then stroked in its own colour. The stroke
     * is not decoration: an antialiased fill stops a hair short of its edge, and
     * without it every shared edge in the mesh shows as a lighter seam.
     */
    private static void shard(Graphics2D g2, int w, int h, int i, int j, int half,
            float x1, float y1, float x2, float y2, float x3, float y3) {
        Path2D.Float triangle = new Path2D.Float();
        triangle.moveTo(x1, y1);
        triangle.lineTo(x2, y2);
        triangle.lineTo(x3, y3);
        triangle.closePath();

        g2.setColor(facetColour(w, h, (x1 + x2 + x3) / 3f, (y1 + y2 + y3) / 3f,
                dither(i * 2 + half, j, 4)));
        g2.fill(triangle);
        g2.setStroke(new BasicStroke(1f));
        g2.draw(triangle);
    }

    /**
     * The shade of one shard: a broad falloff from a light source above and left
     * of centre, then a small per-shard step so neighbours never quite agree.
     * Those steps are what make it read as faceted rather than as a gradient.
     */
    private static Color facetColour(int w, int h, float cx, float cy, double jitter) {
        double dx = (cx - w * 0.52) / w;
        double dy = (cy - h * 0.30) / h;
        // Stretched down the vertical, so the light falls off faster towards the
        // foot of the screen than out to the sides, as it does in the original.
        double distance = Math.sqrt(dx * dx + dy * dy * 1.7);
        double level = 1.0 - Math.min(1.0, distance * 1.30);
        level = level * 0.78 + 0.16 + (jitter - 0.5) * 0.22;
        level = Math.max(0.0, Math.min(1.0, level));

        return level < 0.5
                ? Ui.mix(FACET_DARK, FACET_MID, level * 2)
                : Ui.mix(FACET_MID, FACET_LIGHT, (level - 0.5) * 2);
    }

    /**
     * A hash of the grid coordinates in {@code [0,1)}, standing in for a random
     * generator so that the same cell always yields the same number and the
     * wallpaper survives a repaint unchanged.
     */
    private static double dither(int x, int y, int salt) {
        int n = x * 374761393 + y * 668265263 + salt * 1274126177;
        n = (n ^ (n >>> 13)) * 1274126177;
        n = n ^ (n >>> 16);
        return (n & 0x7FFFFFFF) / (double) 0x7FFFFFFF;
    }
}

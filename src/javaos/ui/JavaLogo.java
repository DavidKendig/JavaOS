package javaos.ui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.List;

/**
 * The Java logo: the steaming cup, in its own blue and red, on a 128x128 grid.
 *
 * <p>The artwork is the official SVG, carried here as path data and parsed once
 * into {@link Path2D} when the class loads. Nothing is loaded from disk and no
 * image file ships with the desktop; Java2D still draws every pixel.
 */
public final class JavaLogo {

    public static final Color BLUE = new Color(0x00, 0x74, 0xBD);
    public static final Color RED = new Color(0xEA, 0x2D, 0x2E);

    /** One filled path of the artwork, in painting order. */
    private record Shape(Color color, Path2D path) {
        Shape(Color color, String d) {
            this(color, Svg.path(d));
        }
    }

    private static final List<Shape> ART = List.of(
            new Shape(BLUE,
                    "M47.617 98.12s-4.767 2.774 3.397 3.71"
                    + "c9.892 1.13 14.947.968 25.845-1.092 0 0 2.871 1.795 6.873 3.351-24.439 10.47-55.308-.607-36.115-5.969"
                    + "zm-2.988-13.665s-5.348 3.959 2.823 4.805"
                    + "c10.567 1.091 18.91 1.18 33.354-1.6 0 0 1.993 2.025 5.132 3.131-29.542 8.64-62.446.68-41.309-6.336"
                    + "z"),
            new Shape(RED,
                    "M69.802 61.271c6.025 6.935-1.58 13.17-1.58 13.17s15.289-7.891 8.269-17.777"
                    + "c-6.559-9.215-11.587-13.792 15.635-29.58 0 .001-42.731 10.67-22.324 34.187z"),
            new Shape(BLUE,
                    "M102.123 108.229s3.529 2.91-3.888 5.159"
                    + "c-14.102 4.272-58.706 5.56-71.094.171-4.451-1.938 3.899-4.625 6.526-5.192 2.739-.593 4.303-.485 4.303-.485-4.953-3.487-32.013 6.85-13.743 9.815 49.821 8.076 90.817-3.637 77.896-9.468"
                    + "zM49.912 70.294s-22.686 5.389-8.033 7.348"
                    + "c6.188.828 18.518.638 30.011-.326 9.39-.789 18.813-2.474 18.813-2.474"
                    + "s-3.308 1.419-5.704 3.053"
                    + "c-23.042 6.061-67.544 3.238-54.731-2.958 10.832-5.239 19.644-4.643 19.644-4.643"
                    + "zm40.697 22.747"
                    + "c23.421-12.167 12.591-23.86 5.032-22.285-1.848.385-2.677.72-2.677.72"
                    + "s.688-1.079 2-1.543"
                    + "c14.953-5.255 26.451 15.503-4.823 23.725 0-.002.359-.327.468-.617z"),
            new Shape(RED,
                    "M76.491 1.587S89.459 14.563 64.188 34.51"
                    + "c-20.266 16.006-4.621 25.13-.007 35.559-11.831-10.673-20.509-20.07-14.688-28.815"
                    + "C58.041 28.42 81.722 22.195 76.491 1.587z"),
            new Shape(BLUE,
                    "M52.214 126.021"
                    + "c22.476 1.437 57-.8 57.817-11.436 0 0-1.571 4.032-18.577 7.231-19.186 3.612-42.854 3.191-56.887.874 0 .001 2.875 2.381 17.647 3.331"
                    + "z"));

    /** The nominal side of the artwork grid, matching the source viewBox. */
    public static final double GRID = 128.0;

    /**
     * The tight box the artwork actually fills inside that grid. The cup does not
     * reach the edges of its viewBox, so anything that centres or abuts the logo
     * measures against this rather than against the grid.
     */
    private static final Rectangle2D BOX = tightBox();

    private JavaLogo() {
    }

    private static Rectangle2D tightBox() {
        Rectangle2D box = null;
        for (Shape shape : ART) {
            Rectangle2D b = shape.path().getBounds2D();
            box = box == null ? b : box.createUnion(b);
        }
        return box;
    }

    /** The visible width of the artwork when drawn at the given grid size. */
    public static double width(double size) {
        return BOX.getWidth() / GRID * size;
    }

    /** The visible height of the artwork when drawn at the given grid size. */
    public static double height(double size) {
        return BOX.getHeight() / GRID * size;
    }

    /** Draws the logo with the top-left corner of the visible artwork at (x, y). */
    public static void paintAt(Graphics2D g, double x, double y, double size) {
        paintAt(g, x, y, size, null);
    }

    /** Draws the logo, tinted, with the top-left corner of the artwork at (x, y). */
    public static void paintAt(Graphics2D g, double x, double y, double size, Color tint) {
        paint(g, x - BOX.getX() / GRID * size, y - BOX.getY() / GRID * size, size, tint);
    }

    /** Draws the logo in its own colours, in a square of the given size. */
    public static void paint(Graphics2D g0, double x, double y, double size) {
        paint(g0, x, y, size, null);
    }

    /**
     * Draws the logo, optionally flattened to a single colour. A tint is what
     * the drop shadows and the reversed-out glyphs use.
     */
    public static void paint(Graphics2D g0, double x, double y, double size, Color tint) {
        Graphics2D g = Ui.smooth(g0);
        g.translate(x, y);
        g.scale(size / GRID, size / GRID);
        for (Shape shape : ART) {
            g.setColor(tint == null ? shape.color() : tint);
            g.fill(shape.path());
        }
        g.dispose();
    }
}

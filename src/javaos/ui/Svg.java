package javaos.ui;

import java.awt.geom.Path2D;

/**
 * The sliver of SVG this desktop needs: the {@code d} attribute of a path,
 * turned into a {@link Path2D}. Moves, lines, cubics and quadratics, in both
 * their absolute and relative forms, plus the smooth shorthands. Elliptical
 * arcs are not implemented, because no artwork here uses one.
 *
 * <p>This keeps the rule the desktop has always kept: no image files ship with
 * it. Vector data is fine; it still ends up drawn by Java2D.
 */
public final class Svg {

    private final String d;
    private int i;

    private Svg(String d) {
        this.d = d;
    }

    /** Parses SVG path data into a non-zero-wind path. */
    public static Path2D.Double path(String d) {
        return new Svg(d).parse();
    }

    private Path2D.Double parse() {
        Path2D.Double p = new Path2D.Double(Path2D.WIND_NON_ZERO);
        double x = 0;
        double y = 0;
        double startX = 0;
        double startY = 0;
        double cubicX = 0;
        double cubicY = 0;
        double quadX = 0;
        double quadY = 0;
        char cmd = 0;
        char last = 0;

        while (true) {
            skipSeparators();
            if (i >= d.length()) {
                break;
            }
            char c = d.charAt(i);
            if (Character.isLetter(c)) {
                cmd = c;
                i++;
            } else if (cmd == 'M') {
                cmd = 'L';          // a moveto followed by more numbers means lineto
            } else if (cmd == 'm') {
                cmd = 'l';
            } else if (cmd == 0) {
                throw new IllegalArgumentException("Path data does not start with a command: " + d);
            }
            boolean rel = Character.isLowerCase(cmd);

            switch (Character.toUpperCase(cmd)) {
                case 'M' -> {
                    double nx = number();
                    double ny = number();
                    x = rel ? x + nx : nx;
                    y = rel ? y + ny : ny;
                    p.moveTo(x, y);
                    startX = x;
                    startY = y;
                }
                case 'L' -> {
                    double nx = number();
                    double ny = number();
                    x = rel ? x + nx : nx;
                    y = rel ? y + ny : ny;
                    p.lineTo(x, y);
                }
                case 'H' -> {
                    double nx = number();
                    x = rel ? x + nx : nx;
                    p.lineTo(x, y);
                }
                case 'V' -> {
                    double ny = number();
                    y = rel ? y + ny : ny;
                    p.lineTo(x, y);
                }
                case 'C' -> {
                    double x1 = number();
                    double y1 = number();
                    double x2 = number();
                    double y2 = number();
                    double nx = number();
                    double ny = number();
                    if (rel) {
                        x1 += x; y1 += y; x2 += x; y2 += y; nx += x; ny += y;
                    }
                    p.curveTo(x1, y1, x2, y2, nx, ny);
                    cubicX = x2;
                    cubicY = y2;
                    x = nx;
                    y = ny;
                }
                case 'S' -> {
                    double x2 = number();
                    double y2 = number();
                    double nx = number();
                    double ny = number();
                    if (rel) {
                        x2 += x; y2 += y; nx += x; ny += y;
                    }
                    // The first control point mirrors the previous one, if there was one.
                    boolean smooth = last == 'C' || last == 'c' || last == 'S' || last == 's';
                    double x1 = smooth ? 2 * x - cubicX : x;
                    double y1 = smooth ? 2 * y - cubicY : y;
                    p.curveTo(x1, y1, x2, y2, nx, ny);
                    cubicX = x2;
                    cubicY = y2;
                    x = nx;
                    y = ny;
                }
                case 'Q' -> {
                    double x1 = number();
                    double y1 = number();
                    double nx = number();
                    double ny = number();
                    if (rel) {
                        x1 += x; y1 += y; nx += x; ny += y;
                    }
                    p.quadTo(x1, y1, nx, ny);
                    quadX = x1;
                    quadY = y1;
                    x = nx;
                    y = ny;
                }
                case 'T' -> {
                    double nx = number();
                    double ny = number();
                    if (rel) {
                        nx += x; ny += y;
                    }
                    boolean smooth = last == 'Q' || last == 'q' || last == 'T' || last == 't';
                    double x1 = smooth ? 2 * x - quadX : x;
                    double y1 = smooth ? 2 * y - quadY : y;
                    p.quadTo(x1, y1, nx, ny);
                    quadX = x1;
                    quadY = y1;
                    x = nx;
                    y = ny;
                }
                case 'Z' -> {
                    p.closePath();
                    x = startX;
                    y = startY;
                }
                default -> throw new IllegalArgumentException(
                        "Unsupported path command '" + cmd + "' in: " + d);
            }
            last = cmd;
        }
        return p;
    }

    private void skipSeparators() {
        while (i < d.length() && (Character.isWhitespace(d.charAt(i)) || d.charAt(i) == ',')) {
            i++;
        }
    }

    /** Reads one number, tolerating the leading dots and signs SVG packs together. */
    private double number() {
        skipSeparators();
        int start = i;
        if (i < d.length() && (d.charAt(i) == '+' || d.charAt(i) == '-')) {
            i++;
        }
        while (i < d.length() && Character.isDigit(d.charAt(i))) {
            i++;
        }
        if (i < d.length() && d.charAt(i) == '.') {
            i++;
            while (i < d.length() && Character.isDigit(d.charAt(i))) {
                i++;
            }
        }
        if (i < d.length() && (d.charAt(i) == 'e' || d.charAt(i) == 'E')) {
            int mark = i;
            i++;
            if (i < d.length() && (d.charAt(i) == '+' || d.charAt(i) == '-')) {
                i++;
            }
            if (i < d.length() && Character.isDigit(d.charAt(i))) {
                while (i < d.length() && Character.isDigit(d.charAt(i))) {
                    i++;
                }
            } else {
                i = mark;       // not an exponent after all
            }
        }
        if (start == i) {
            throw new IllegalArgumentException("Expected a number at " + start + " in: " + d);
        }
        return Double.parseDouble(d.substring(start, i));
    }
}

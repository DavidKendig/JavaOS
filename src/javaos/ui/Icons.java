package javaos.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

import javax.swing.Icon;

/**
 * Every icon in the desktop, drawn as vectors on a nominal 32x32 grid and scaled
 * to whatever size the caller asks for. The palette is deliberately StarOffice:
 * a lot of manila, a lot of steel blue, black outlines on everything.
 */
public final class Icons {

    public static final Color MANILA = new Color(0xE8, 0xC4, 0x6A);
    public static final Color MANILA_DARK = new Color(0xC1, 0x96, 0x3C);
    public static final Color PAPER = new Color(0xFA, 0xFA, 0xF4);
    public static final Color INK = new Color(0x33, 0x33, 0x33);
    public static final Color STEEL = new Color(0x6B, 0x7C, 0x94);
    public static final Color STEEL_DARK = new Color(0x3D, 0x4A, 0x5C);
    public static final Color GLASS = new Color(0x9C, 0xC4, 0xE4);
    public static final Color GREEN = new Color(0x3E, 0x8E, 0x41);
    public static final Color RED = new Color(0xC0, 0x39, 0x2B);
    public static final Color BLUE = new Color(0x2E, 0x5C, 0x94);

    /** A painter drawing into a 32x32 space; the Icon handles the scaling. */
    public interface Art {
        void paint(Graphics2D g);
    }

    private Icons() {
    }

    public static Icon of(int size, Art art) {
        return new Icon() {
            @Override public int getIconWidth() { return size; }

            @Override public int getIconHeight() { return size; }

            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = Ui.smooth(g);
                g2.translate(x, y);
                g2.scale(size / 32.0, size / 32.0);
                art.paint(g2);
                g2.dispose();
            }
        };
    }

    private static void outline(Graphics2D g, java.awt.Shape s, Color fill, Color line) {
        g.setColor(fill);
        g.fill(s);
        g.setColor(line);
        g.setStroke(new BasicStroke(1.2f));
        g.draw(s);
    }

    private static void page(Graphics2D g, Color tint) {
        GeneralPath p = new GeneralPath(Path2D.WIND_NON_ZERO);
        p.moveTo(7, 2);
        p.lineTo(19, 2);
        p.lineTo(25, 8);
        p.lineTo(25, 30);
        p.lineTo(7, 30);
        p.closePath();
        outline(g, p, PAPER, INK);
        GeneralPath fold = new GeneralPath();
        fold.moveTo(19, 2);
        fold.lineTo(19, 8);
        fold.lineTo(25, 8);
        outline(g, fold, new Color(0xDD, 0xDD, 0xD0), INK);
        if (tint != null) {
            g.setColor(tint);
            g.setStroke(new BasicStroke(1.6f));
            for (int i = 0; i < 5; i++) {
                g.drawLine(10, 13 + i * 3, i == 4 ? 17 : 22, 13 + i * 3);
            }
        }
    }

    public static Icon folder(int size) {
        return of(size, g -> {
            GeneralPath tab = new GeneralPath();
            tab.moveTo(2, 8);
            tab.lineTo(12, 8);
            tab.lineTo(15, 11);
            tab.lineTo(2, 11);
            tab.closePath();
            outline(g, tab, MANILA_DARK, INK);
            RoundRectangle2D body = new RoundRectangle2D.Double(2, 10, 28, 18, 3, 3);
            g.setPaint(new java.awt.GradientPaint(0, 10, MANILA, 0, 28, MANILA_DARK));
            g.fill(body);
            g.setColor(INK);
            g.setStroke(new BasicStroke(1.2f));
            g.draw(body);
        });
    }

    public static Icon folderOpen(int size) {
        return of(size, g -> {
            GeneralPath back = new GeneralPath();
            back.moveTo(2, 9);
            back.lineTo(12, 9);
            back.lineTo(15, 12);
            back.lineTo(28, 12);
            back.lineTo(28, 27);
            back.lineTo(2, 27);
            back.closePath();
            outline(g, back, MANILA_DARK, INK);
            GeneralPath front = new GeneralPath();
            front.moveTo(5, 27);
            front.lineTo(9, 15);
            front.lineTo(31, 15);
            front.lineTo(27, 27);
            front.closePath();
            outline(g, front, MANILA, INK);
        });
    }

    public static Icon document(int size) {
        return of(size, g -> page(g, STEEL));
    }

    public static Icon textDocument(int size) {
        return of(size, g -> {
            page(g, null);
            g.setColor(BLUE);
            g.setFont(new Font("Serif", Font.BOLD, 13));
            g.drawString("W", 11, 24);
            g.setColor(STEEL);
            g.setStroke(new BasicStroke(1.4f));
            g.drawLine(10, 12, 22, 12);
        });
    }

    public static Icon spreadsheet(int size) {
        return of(size, g -> {
            page(g, null);
            g.setColor(GREEN);
            for (int r = 0; r < 4; r++) {
                for (int c = 0; c < 3; c++) {
                    g.fillRect(9 + c * 5, 12 + r * 4, 4, 3);
                }
            }
        });
    }

    public static Icon image(int size) {
        return of(size, g -> {
            outline(g, new java.awt.Rectangle(3, 6, 26, 20), GLASS, INK);
            g.setColor(new Color(0xF2, 0xC4, 0x4C));
            g.fill(new Ellipse2D.Double(7, 9, 6, 6));
            GeneralPath hills = new GeneralPath();
            hills.moveTo(4, 25);
            hills.lineTo(13, 15);
            hills.lineTo(19, 21);
            hills.lineTo(23, 17);
            hills.lineTo(28, 25);
            hills.closePath();
            outline(g, hills, GREEN, INK);
        });
    }

    public static Icon terminal(int size) {
        return of(size, g -> {
            outline(g, new RoundRectangle2D.Double(2, 4, 28, 24, 3, 3), STEEL_DARK, INK);
            g.setColor(Color.BLACK);
            g.fillRect(4, 8, 24, 18);
            g.setColor(new Color(0x5C, 0xE6, 0x5C));
            g.setFont(new Font("Monospaced", Font.BOLD, 9));
            g.drawString(">_", 6, 18);
        });
    }

    public static Icon calculator(int size) {
        return of(size, g -> {
            outline(g, new RoundRectangle2D.Double(5, 2, 22, 28, 3, 3), STEEL, INK);
            outline(g, new java.awt.Rectangle(8, 5, 16, 6), new Color(0xC8, 0xE6, 0xB4), INK);
            g.setColor(new Color(0xDD, 0xDD, 0xDD));
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 3; c++) {
                    g.fillRect(8 + c * 6, 14 + r * 5, 4, 4);
                }
            }
        });
    }

    public static Icon computer(int size) {
        return of(size, g -> {
            outline(g, new RoundRectangle2D.Double(3, 4, 26, 18, 3, 3), STEEL, INK);
            g.setPaint(new java.awt.GradientPaint(0, 6, new Color(0x28, 0x50, 0x8C),
                    0, 20, new Color(0x6E, 0x9E, 0xD4)));
            g.fillRect(6, 7, 20, 12);
            outline(g, new java.awt.Rectangle(11, 22, 10, 4), STEEL_DARK, INK);
            outline(g, new java.awt.Rectangle(7, 26, 18, 3), STEEL, INK);
        });
    }

    public static Icon trash(int size) {
        return of(size, g -> {
            outline(g, new java.awt.Rectangle(9, 3, 14, 3), STEEL_DARK, INK);
            GeneralPath can = new GeneralPath();
            can.moveTo(8, 7);
            can.lineTo(24, 7);
            can.lineTo(22, 29);
            can.lineTo(10, 29);
            can.closePath();
            outline(g, can, STEEL, INK);
            g.setColor(STEEL_DARK);
            g.setStroke(new BasicStroke(1.3f));
            g.drawLine(13, 11, 14, 25);
            g.drawLine(16, 11, 16, 25);
            g.drawLine(19, 11, 18, 25);
        });
    }

    public static Icon paint(int size) {
        return of(size, g -> {
            GeneralPath palette = new GeneralPath();
            palette.moveTo(16, 3);
            palette.curveTo(26, 3, 30, 10, 29, 17);
            palette.curveTo(28, 23, 21, 20, 20, 24);
            palette.curveTo(19, 28, 22, 29, 16, 29);
            palette.curveTo(7, 29, 3, 23, 3, 16);
            palette.curveTo(3, 8, 8, 3, 16, 3);
            palette.closePath();
            outline(g, palette, new Color(0xD8, 0xB8, 0x8C), INK);
            Color[] dots = {RED, BLUE, GREEN, new Color(0xF2, 0xC4, 0x4C)};
            int[][] at = {{9, 9}, {16, 7}, {22, 10}, {8, 17}};
            for (int i = 0; i < dots.length; i++) {
                outline(g, new Ellipse2D.Double(at[i][0], at[i][1], 5, 5), dots[i], INK);
            }
        });
    }

    public static Icon settings(int size) {
        return of(size, g -> {
            GeneralPath gear = new GeneralPath();
            for (int i = 0; i < 8; i++) {
                double a = Math.PI * 2 * i / 8;
                addArcPoint(gear, i == 0, 16, 16, 14, a);
                addArcPoint(gear, false, 16, 16, 14, a + Math.PI / 16);
                addArcPoint(gear, false, 16, 16, 10, a + Math.PI / 8 - Math.PI / 16);
                addArcPoint(gear, false, 16, 16, 10, a + Math.PI / 8);
            }
            gear.closePath();
            outline(g, gear, STEEL, INK);
            outline(g, new Ellipse2D.Double(11, 11, 10, 10), Ui.control(), INK);
        });
    }

    private static void addArcPoint(GeneralPath p, boolean start, double cx, double cy,
            double r, double angle) {
        double x = cx + Math.cos(angle) * r;
        double y = cy + Math.sin(angle) * r;
        if (start) {
            p.moveTo(x, y);
        } else {
            p.lineTo(x, y);
        }
    }

    public static Icon monitor(int size) {
        return of(size, g -> {
            outline(g, new RoundRectangle2D.Double(2, 5, 28, 22, 3, 3), STEEL_DARK, INK);
            g.setColor(Color.BLACK);
            g.fillRect(5, 8, 22, 16);
            g.setColor(new Color(0x5C, 0xE6, 0x5C));
            g.setStroke(new BasicStroke(1.4f));
            int[] ys = {20, 14, 18, 11, 16, 9, 13};
            for (int i = 0; i < ys.length - 1; i++) {
                g.drawLine(6 + i * 3, ys[i] + 2, 9 + i * 3, ys[i + 1] + 2);
            }
        });
    }

    public static Icon mine(int size) {
        return of(size, g -> {
            g.setColor(INK);
            g.setStroke(new BasicStroke(2.4f));
            g.drawLine(16, 3, 16, 29);
            g.drawLine(3, 16, 29, 16);
            g.drawLine(7, 7, 25, 25);
            g.drawLine(25, 7, 7, 25);
            outline(g, new Ellipse2D.Double(8, 8, 16, 16), new Color(0x30, 0x30, 0x30), INK);
            g.setColor(Color.WHITE);
            g.fillRect(12, 12, 3, 3);
        });
    }

    public static Icon disk(int size) {
        return of(size, g -> {
            outline(g, new java.awt.Rectangle(3, 3, 26, 26), STEEL, INK);
            outline(g, new java.awt.Rectangle(9, 3, 14, 10), PAPER, INK);
            g.setColor(STEEL_DARK);
            g.fillRect(18, 5, 3, 6);
            outline(g, new java.awt.Rectangle(7, 17, 18, 12), PAPER, INK);
        });
    }

    public static Icon newDoc(int size) {
        return of(size, g -> {
            page(g, STEEL);
            g.setColor(GREEN);
            g.setStroke(new BasicStroke(3f));
            g.drawLine(21, 24, 29, 24);
            g.drawLine(25, 20, 25, 28);
        });
    }

    public static Icon arrow(int size, int quarterTurns) {
        return of(size, g -> {
            GeneralPath a = new GeneralPath();
            a.moveTo(4, 16);
            a.lineTo(14, 6);
            a.lineTo(14, 12);
            a.lineTo(28, 12);
            a.lineTo(28, 20);
            a.lineTo(14, 20);
            a.lineTo(14, 26);
            a.closePath();
            java.awt.geom.AffineTransform t = java.awt.geom.AffineTransform
                    .getRotateInstance(Math.PI / 2 * quarterTurns, 16, 16);
            outline(g, t.createTransformedShape(a), STEEL, INK);
        });
    }

    public static Icon up(int size) { return arrow(size, 1); }

    public static Icon back(int size) { return arrow(size, 0); }

    public static Icon forward(int size) { return arrow(size, 2); }

    public static Icon refresh(int size) {
        return of(size, g -> {
            g.setColor(GREEN);
            g.setStroke(new BasicStroke(3.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
            g.draw(new java.awt.geom.Arc2D.Double(5, 5, 22, 22, 40, 280, java.awt.geom.Arc2D.OPEN));
            GeneralPath head = new GeneralPath();
            head.moveTo(24, 2);
            head.lineTo(30, 12);
            head.lineTo(18, 12);
            head.closePath();
            outline(g, head, GREEN, GREEN.darker());
        });
    }

    public static Icon letterGlyph(int size, String letter, Color color) {
        return styleGlyph(size, letter, color, Font.BOLD, false);
    }

    /** A typographic button face: the letter drawn in the style it applies. */
    public static Icon styleGlyph(int size, String letter, Color color, int style,
            boolean underlined) {
        return of(size, g -> {
            g.setColor(color);
            g.setFont(new Font("Serif", style, 24));
            java.awt.FontMetrics fm = g.getFontMetrics();
            int x = 16 - fm.stringWidth(letter) / 2;
            g.drawString(letter, x, 24);
            if (underlined) {
                g.fillRect(x - 1, 27, fm.stringWidth(letter) + 2, 2);
            }
        });
    }

    // ---- editing ---------------------------------------------------------

    public static Icon cut(int size) {
        return of(size, g -> {
            g.setColor(STEEL_DARK);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(9, 4, 21, 21);
            g.drawLine(23, 4, 11, 21);
            outline(g, new Ellipse2D.Double(6, 21, 8, 8), PAPER, INK);
            outline(g, new Ellipse2D.Double(18, 21, 8, 8), PAPER, INK);
        });
    }

    public static Icon copy(int size) {
        return of(size, g -> {
            outline(g, new java.awt.Rectangle(4, 3, 15, 20), PAPER, INK);
            outline(g, new java.awt.Rectangle(12, 9, 15, 20), PAPER, INK);
            g.setColor(STEEL);
            g.setStroke(new BasicStroke(1.4f));
            for (int i = 0; i < 3; i++) {
                g.drawLine(15, 14 + i * 4, 24, 14 + i * 4);
            }
        });
    }

    public static Icon paste(int size) {
        return of(size, g -> {
            outline(g, new RoundRectangle2D.Double(4, 4, 24, 25, 2, 2),
                    new Color(0xC0, 0x9C, 0x60), INK);
            outline(g, new java.awt.Rectangle(11, 2, 10, 5), STEEL, INK);
            outline(g, new java.awt.Rectangle(8, 11, 16, 15), PAPER, INK);
            g.setColor(STEEL);
            g.setStroke(new BasicStroke(1.4f));
            for (int i = 0; i < 3; i++) {
                g.drawLine(11, 15 + i * 4, 21, 15 + i * 4);
            }
        });
    }

    // ---- paint tools -----------------------------------------------------

    public static Icon pencil(int size) {
        return of(size, g -> {
            GeneralPath body = new GeneralPath();
            body.moveTo(9, 26);
            body.lineTo(21, 5);
            body.lineTo(27, 9);
            body.lineTo(15, 30);
            body.closePath();
            outline(g, body, new Color(0xF2, 0xC4, 0x4C), INK);
            GeneralPath tip = new GeneralPath();
            tip.moveTo(9, 26);
            tip.lineTo(15, 30);
            tip.lineTo(6, 31);
            tip.closePath();
            outline(g, tip, INK, INK);
        });
    }

    public static Icon lineTool(int size) {
        return of(size, g -> {
            g.setColor(STEEL_DARK);
            g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(5, 27, 27, 5);
            outline(g, new java.awt.Rectangle(3, 25, 5, 5), PAPER, INK);
            outline(g, new java.awt.Rectangle(25, 3, 5, 5), PAPER, INK);
        });
    }

    public static Icon rectangleTool(int size) {
        return of(size, g -> {
            g.setColor(STEEL_DARK);
            g.setStroke(new BasicStroke(2.2f));
            g.drawRect(6, 9, 21, 15);
        });
    }

    public static Icon ellipseTool(int size) {
        return of(size, g -> {
            g.setColor(STEEL_DARK);
            g.setStroke(new BasicStroke(2.2f));
            g.draw(new Ellipse2D.Double(5, 8, 22, 17));
        });
    }

    public static Icon fillTool(int size) {
        return of(size, g -> {
            GeneralPath can = new GeneralPath();
            can.moveTo(6, 14);
            can.lineTo(16, 5);
            can.lineTo(26, 15);
            can.lineTo(16, 25);
            can.closePath();
            outline(g, can, STEEL, INK);
            g.setColor(BLUE);
            GeneralPath spill = new GeneralPath();
            spill.moveTo(26, 17);
            spill.curveTo(30, 22, 30, 28, 26, 30);
            spill.curveTo(22, 28, 22, 22, 26, 17);
            spill.closePath();
            outline(g, spill, BLUE, INK);
        });
    }

    public static Icon eraser(int size) {
        return of(size, g -> {
            GeneralPath body = new GeneralPath();
            body.moveTo(4, 22);
            body.lineTo(17, 6);
            body.lineTo(28, 14);
            body.lineTo(15, 29);
            body.closePath();
            outline(g, body, new Color(0xE4, 0x9C, 0x9C), INK);
            g.setColor(INK);
            g.drawLine(10, 17, 22, 26);
        });
    }

    public static Icon dukeIcon(int size) {
        return of(size, g -> JavaArt.duke(g, 1, 1, 30));
    }

    /** The Java logo, sized to the icon box. */
    public static Icon javaLogo(int size) {
        return of(size, g -> JavaLogo.paint(g, 1, 1, 30));
    }

    /**
     * The IEC power symbol: a broken ring with the stem standing in the break.
     *
     * <p>Arc2D puts 0 degrees at 3 o'clock and sweeps anticlockwise, so 90 is the
     * top. Drawing 300 degrees from 120 leaves the 60 degrees between 60 and 120
     * open, which centres the gap under the stem.
     */
    public static Icon power(int size) {
        return of(size, g -> {
            g.setColor(RED);
            g.setStroke(new BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new java.awt.geom.Arc2D.Double(6, 8, 20, 20, 120, 300,
                    java.awt.geom.Arc2D.OPEN));
            g.drawLine(16, 4, 16, 15);
        });
    }

    /** A closed padlock, for the lock screen and the menu item that raises it. */
    public static Icon lock(int size) {
        return of(size, g -> {
            g.setColor(STEEL_DARK);
            g.setStroke(new BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new java.awt.geom.Arc2D.Double(9, 6, 14, 16, 0, 180,
                    java.awt.geom.Arc2D.OPEN));
            outline(g, new RoundRectangle2D.Double(5, 15, 22, 14, 4, 4), MANILA, INK);
            g.setColor(INK);
            g.fill(new Ellipse2D.Double(14, 18, 4, 4));
            g.fillRect(15, 21, 2, 5);
        });
    }

    public static Icon info(int size) {
        return of(size, g -> {
            outline(g, new Ellipse2D.Double(3, 3, 26, 26), BLUE, INK);
            g.setColor(Color.WHITE);
            g.setFont(new Font("Serif", Font.BOLD, 20));
            g.drawString("i", 14, 24);
        });
    }

    // ---- media ---------------------------------------------------------

    /** A right-pointing play triangle, sized and placed on the 32x32 grid. */
    private static GeneralPath playTriangle(double x, double y, double w, double h) {
        GeneralPath p = new GeneralPath();
        p.moveTo(x, y);
        p.lineTo(x + w, y + h / 2);
        p.lineTo(x, y + h);
        p.closePath();
        return p;
    }

    /** The Media Player: a disc with the play triangle struck through it. */
    public static Icon media(int size) {
        return of(size, g -> {
            outline(g, new Ellipse2D.Double(2, 2, 28, 28), STEEL_DARK, INK);
            g.setColor(Ui.lighter(GLASS, 0.1));
            g.setStroke(new BasicStroke(1.4f));
            g.draw(new java.awt.geom.Arc2D.Double(6, 6, 20, 20, 55, 110,
                    java.awt.geom.Arc2D.OPEN));
            outline(g, playTriangle(12, 9, 11, 14), new Color(0x5C, 0xE6, 0x5C), INK);
        });
    }

    public static Icon mediaPlay(int size) {
        return of(size, g -> outline(g, playTriangle(9, 5, 16, 22), GREEN, INK));
    }

    public static Icon mediaPause(int size) {
        return of(size, g -> {
            outline(g, new java.awt.Rectangle(9, 5, 5, 22), STEEL_DARK, INK);
            outline(g, new java.awt.Rectangle(18, 5, 5, 22), STEEL_DARK, INK);
        });
    }

    public static Icon mediaStop(int size) {
        return of(size, g -> outline(g, new java.awt.Rectangle(7, 7, 18, 18), RED, INK));
    }

    public static Icon mediaPrevious(int size) {
        return of(size, g -> {
            outline(g, new java.awt.Rectangle(7, 6, 4, 20), STEEL_DARK, INK);
            GeneralPath p = new GeneralPath();
            p.moveTo(26, 6);
            p.lineTo(26, 26);
            p.lineTo(13, 16);
            p.closePath();
            outline(g, p, STEEL_DARK, INK);
        });
    }

    public static Icon mediaNext(int size) {
        return of(size, g -> {
            outline(g, playTriangle(6, 6, 13, 20), STEEL_DARK, INK);
            outline(g, new java.awt.Rectangle(21, 6, 4, 20), STEEL_DARK, INK);
        });
    }

    /**
     * The hand-over icon: a strip of film with an arrow leaving it. Deliberately
     * generic -- it means "give this to the external player", and borrowing
     * VLC's own artwork to say so would be someone else's trademark on a
     * desktop that draws everything itself.
     */
    public static Icon mediaVlc(int size) {
        return of(size, g -> {
            outline(g, new java.awt.Rectangle(4, 6, 17, 20), STEEL_DARK, INK);
            g.setColor(PAPER);
            for (int y = 8; y < 25; y += 5) {
                g.fillRect(6, y, 3, 3);
                g.fillRect(16, y, 3, 3);
            }
            g.setColor(Ui.lighter(GLASS, 0.05));
            g.fillRect(10, 8, 5, 16);

            g.setColor(GREEN);
            g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(21, 13, 29, 5);
            GeneralPath head = new GeneralPath();
            head.moveTo(30, 3);
            head.lineTo(30, 11);
            head.lineTo(22, 3);
            head.closePath();
            g.setColor(GREEN);
            g.fill(head);
        });
    }

    // ---- the office suite tiles ----------------------------------------

    /**
     * The office application tiles: a glossy rounded square with a white wave
     * down the left edge and the application letter on the right, in the shape
     * of the store tile the desktop was modelled on.
     *
     * <p>The colours are each suite's own, but the artwork is drawn here, like
     * every other icon on this desktop. Pasting Microsoft's real Word, Excel,
     * PowerPoint and Access glyphs in would be putting someone else's trademark
     * on a system that has never shipped a bitmap -- the same line
     * {@link #mediaVlc} draws with VLC's cone.
     */
    private static void suiteTile(Graphics2D g, Color light, Color dark, String letter) {
        RoundRectangle2D tile = new RoundRectangle2D.Double(2, 2, 28, 28, 7, 7);
        g.setPaint(new java.awt.GradientPaint(0, 2, light, 0, 30, dark));
        g.fill(tile);

        // The wave: two mirrored curves down the left third, as on the tile.
        java.awt.Shape clip = g.getClip();
        g.clip(tile);
        GeneralPath wave = new GeneralPath();
        wave.moveTo(11, 2);
        wave.curveTo(6, 9, 14, 15, 10, 21);
        wave.curveTo(8, 25, 8, 28, 9, 30);
        g.setColor(new Color(255, 255, 255, 150));
        g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(wave);

        // The gloss: a highlight across the top, fading out by the middle.
        g.setPaint(new java.awt.GradientPaint(0, 3, new Color(255, 255, 255, 90),
                0, 17, new Color(255, 255, 255, 0)));
        g.fill(new RoundRectangle2D.Double(3, 3, 26, 14, 6, 6));
        g.setClip(clip);

        g.setColor(new Color(255, 255, 255, 230));
        g.setStroke(new BasicStroke(1.6f));
        g.draw(tile);

        g.setFont(new Font("SansSerif", Font.BOLD, 17));
        int width = g.getFontMetrics().stringWidth(letter);
        g.setColor(new Color(0, 0, 0, 70));
        g.drawString(letter, 20 - width / 2 + 1, 23);
        g.setColor(Color.WHITE);
        g.drawString(letter, 20 - width / 2, 22);
    }

    public static Icon officeWord(int size) {
        return of(size, g -> suiteTile(g, new Color(0x2B, 0x7C, 0xD3),
                new Color(0x10, 0x3F, 0x91), "W"));
    }

    public static Icon officeExcel(int size) {
        return of(size, g -> suiteTile(g, new Color(0x33, 0xB6, 0x74),
                new Color(0x0E, 0x6B, 0x38), "X"));
    }

    public static Icon officePowerPoint(int size) {
        return of(size, g -> suiteTile(g, new Color(0xF2, 0x7B, 0x52),
                new Color(0xB8, 0x35, 0x16), "P"));
    }

    public static Icon officeAccess(int size) {
        return of(size, g -> suiteTile(g, new Color(0xCE, 0x51, 0x4E),
                new Color(0x93, 0x2F, 0x32), "A"));
    }

    /**
     * The office hand-over mark: a document with an arrow leaving it, shown
     * wherever JavaOS is about to start somebody else's suite instead of its own
     * editor.
     */
    public static Icon officeHandover(int size) {
        return of(size, g -> {
            GeneralPath sheet = new GeneralPath();
            sheet.moveTo(5, 3);
            sheet.lineTo(15, 3);
            sheet.lineTo(20, 8);
            sheet.lineTo(20, 29);
            sheet.lineTo(5, 29);
            sheet.closePath();
            outline(g, sheet, PAPER, INK);
            g.setColor(STEEL);
            g.setStroke(new BasicStroke(1.4f));
            for (int i = 0; i < 4; i++) {
                g.drawLine(8, 14 + i * 4, 17, 14 + i * 4);
            }
            g.setColor(BLUE);
            g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(21, 12, 28, 5);
            GeneralPath head = new GeneralPath();
            head.moveTo(30, 3);
            head.lineTo(30, 11);
            head.lineTo(22, 3);
            head.closePath();
            g.fill(head);
        });
    }
}

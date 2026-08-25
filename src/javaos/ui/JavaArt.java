package javaos.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;

/** Duke, drawn in Java2D so the desktop ships without assets. The cup lives in {@link JavaLogo}. */
public final class JavaArt {

    public static final Color DUKE_NOSE = new Color(0xE8, 0x30, 0x2A);

    private JavaArt() {
    }

    /** Duke: rounded body, red nose, two eyes, one waving arm. Scales to any size. */
    public static void duke(Graphics2D g0, double x, double y, double size) {
        Graphics2D g = Ui.smooth(g0);
        g.translate(x, y);
        g.scale(size / 100.0, size / 100.0);

        // Body: the tooth-shaped blob everyone recognises.
        GeneralPath body = new GeneralPath(Path2D.WIND_NON_ZERO);
        body.moveTo(50, 8);
        body.curveTo(70, 8, 82, 34, 84, 62);
        body.curveTo(86, 84, 72, 94, 50, 94);
        body.curveTo(28, 94, 14, 84, 16, 62);
        body.curveTo(18, 34, 30, 8, 50, 8);
        body.closePath();
        g.setColor(Color.WHITE);
        g.fill(body);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(3f));
        g.draw(body);

        // Nose: the red wedge that makes him Duke and not an egg.
        GeneralPath nose = new GeneralPath();
        nose.moveTo(50, 6);
        nose.curveTo(60, 14, 63, 26, 58, 34);
        nose.curveTo(52, 40, 44, 38, 41, 30);
        nose.curveTo(38, 21, 42, 11, 50, 6);
        nose.closePath();
        g.setColor(DUKE_NOSE);
        g.fill(nose);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(2.5f));
        g.draw(nose);

        // Eyes.
        g.setColor(Color.BLACK);
        g.fill(new Ellipse2D.Double(35, 44, 9, 12));
        g.fill(new Ellipse2D.Double(56, 44, 9, 12));

        // Arms: one down, one raised in the eternal wave.
        g.setStroke(new BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        GeneralPath waving = new GeneralPath();
        waving.moveTo(80, 58);
        waving.quadTo(96, 46, 92, 26);
        g.draw(waving);
        GeneralPath resting = new GeneralPath();
        resting.moveTo(20, 60);
        resting.quadTo(6, 70, 10, 86);
        g.draw(resting);

        g.dispose();
    }
}

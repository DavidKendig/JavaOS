package javaos.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.border.Border;
import javax.swing.plaf.metal.MetalLookAndFeel;

/** Shared 3D-bevel and gradient painting, the way every toolbar looked in 2002. */
public final class Ui {

    private Ui() {
    }

    public static Color control() { return MetalLookAndFeel.getControl(); }
    public static Color light() { return MetalLookAndFeel.getControlHighlight(); }
    public static Color shadow() { return MetalLookAndFeel.getControlShadow(); }
    public static Color darkShadow() { return MetalLookAndFeel.getControlDarkShadow(); }
    public static Color accent() { return MetalLookAndFeel.getPrimaryControlDarkShadow(); }
    public static Color accentLight() { return MetalLookAndFeel.getPrimaryControl(); }

    public static Graphics2D smooth(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return g2;
    }

    /** The one-pixel raised/sunken bevel that carried the entire decade. */
    public static void bevel(Graphics g, int x, int y, int w, int h, boolean raised) {
        Color hi = raised ? light() : shadow();
        Color lo = raised ? shadow() : light();
        g.setColor(hi);
        g.drawLine(x, y, x + w - 1, y);
        g.drawLine(x, y, x, y + h - 1);
        g.setColor(lo);
        g.drawLine(x, y + h - 1, x + w - 1, y + h - 1);
        g.drawLine(x + w - 1, y, x + w - 1, y + h - 1);
    }

    public static void vGradient(Graphics2D g, int x, int y, int w, int h, Color top, Color bottom) {
        g.setPaint(new GradientPaint(x, y, top, x, y + h, bottom));
        g.fillRect(x, y, w, h);
    }

    public static void hGradient(Graphics2D g, int x, int y, int w, int h, Color left, Color right) {
        g.setPaint(new GradientPaint(x, y, left, x + w, y, right));
        g.fillRect(x, y, w, h);
    }

    /** Metal's signature dotted "bumps", used on toolbar grips and status corners. */
    public static void bumps(Graphics g, int x, int y, int w, int h) {
        for (int row = 0; row < h; row += 4) {
            for (int col = (row / 4) % 2 == 0 ? 0 : 2; col < w; col += 4) {
                g.setColor(light());
                g.fillRect(x + col, y + row, 1, 1);
                g.setColor(shadow());
                g.fillRect(x + col + 1, y + row + 1, 1, 1);
            }
        }
    }

    public static Color mix(Color a, Color b, double t) {
        return new Color(
                (int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    public static Color lighter(Color c, double t) { return mix(c, Color.WHITE, t); }

    public static Color darker(Color c, double t) { return mix(c, Color.BLACK, t); }

    /** Sunken status-bar cell, complete with the pointless-but-mandatory inset. */
    public static JLabel statusCell(String text, int minWidth) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("Dialog", Font.PLAIN, 11));
        label.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLoweredBevelBorder(),
                BorderFactory.createEmptyBorder(1, 5, 1, 5)));
        label.setPreferredSize(new Dimension(minWidth, 20));
        label.setMinimumSize(new Dimension(minWidth, 20));
        return label;
    }

    /** Flat toolbar button that pops into a raised bevel on rollover. */
    public static JButton toolButton(javax.swing.Action action) {
        JButton b = new JButton(action) {
            @Override protected void paintComponent(Graphics g) {
                boolean armed = getModel().isPressed() || getModel().isSelected();
                if (getModel().isRollover() || armed) {
                    g.setColor(armed ? shadow() : lighter(control(), 0.35));
                    g.fillRect(0, 0, getWidth(), getHeight());
                    bevel(g, 0, 0, getWidth(), getHeight(), !armed);
                }
                super.paintComponent(g);
            }
        };
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setRolloverEnabled(true);
        b.setMargin(new java.awt.Insets(3, 3, 3, 3));
        b.setVerticalTextPosition(SwingConstants.BOTTOM);
        b.setHorizontalTextPosition(SwingConstants.CENTER);
        if (b.getIcon() != null) {
            // Not just setText(null): the button is bound to the action, so a
            // later change of its NAME would put the label straight back.
            b.setHideActionText(true);
            b.setText(null);
        }
        return b;
    }

    public static Border sunkenPanel() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLoweredBevelBorder(),
                BorderFactory.createEmptyBorder(1, 1, 1, 1));
    }

    /** Etched separator line used inside toolbars and menus. */
    public static void etchedLine(Graphics g, int x, int y, int w, int h, boolean vertical) {
        g.setColor(shadow());
        if (vertical) {
            g.drawLine(x, y, x, y + h);
            g.setColor(light());
            g.drawLine(x + 1, y, x + 1, y + h);
        } else {
            g.drawLine(x, y, x + w, y);
            g.setColor(light());
            g.drawLine(x, y + 1, x + w, y + 1);
        }
    }

    public static void drawOutlinedText(Graphics2D g, String text, int x, int y,
            Color fill, Color halo) {
        g.setColor(halo);
        g.drawString(text, x + 1, y + 1);
        g.setColor(fill);
        g.drawString(text, x, y);
    }

}

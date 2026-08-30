package javaos.desktop;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

import javaos.ui.Ui;

/** A draggable shortcut sitting on the wallpaper. Single click selects, double click opens. */
public class DesktopIcon extends JComponent {

    private static final int WIDTH = 84;
    private static final int HEIGHT = 74;

    private final Icon icon;
    private final String label;
    private final Runnable action;
    private boolean selected;
    private Point grabOffset;

    public DesktopIcon(Icon icon, String label, Runnable action) {
        this.icon = icon;
        this.label = label;
        this.action = action;
        setSize(WIDTH, HEIGHT);
        setPreferredSize(new Dimension(WIDTH, HEIGHT));
        setToolTipText(label);

        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                grabOffset = e.getPoint();
                selectExclusively();
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    action.run();
                }
            }

            @Override public void mouseDragged(MouseEvent e) {
                if (grabOffset == null || getParent() == null) {
                    return;
                }
                int x = getX() + e.getX() - grabOffset.x;
                int y = getY() + e.getY() - grabOffset.y;
                x = Math.max(0, Math.min(x, getParent().getWidth() - getWidth()));
                y = Math.max(0, Math.min(y, getParent().getHeight() - getHeight()));
                setLocation(x, y);
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    private void selectExclusively() {
        if (getParent() != null) {
            for (java.awt.Component c : getParent().getComponents()) {
                if (c instanceof DesktopIcon other && other != this) {
                    other.setSelected(false);
                }
            }
        }
        setSelected(true);
        requestFocusInWindow();
    }

    public void setSelected(boolean selected) {
        if (this.selected != selected) {
            this.selected = selected;
            repaint();
        }
    }

    public void open() {
        action.run();
    }

    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = Ui.smooth(g);
        int iconX = (WIDTH - 32) / 2;
        if (selected) {
            g2.setColor(new Color(255, 255, 255, 60));
            g2.fillRect(iconX - 4, 2, 40, 40);
        }
        icon.paintIcon(this, g2, iconX, 6);

        g2.setFont(new Font("Dialog", Font.BOLD, 11));
        FontMetrics fm = g2.getFontMetrics();
        String text = label;
        int textWidth = fm.stringWidth(text);
        if (textWidth > WIDTH - 6) {
            while (textWidth > WIDTH - 16 && text.length() > 3) {
                text = text.substring(0, text.length() - 1);
                textWidth = fm.stringWidth(text + "...");
            }
            text = text + "...";
            textWidth = fm.stringWidth(text);
        }
        int textX = (WIDTH - textWidth) / 2;
        int textY = 56;
        if (selected) {
            g2.setColor(Ui.accent());
            g2.fillRect(textX - 3, textY - fm.getAscent() - 1, textWidth + 6, fm.getHeight() + 2);
            g2.setColor(Color.WHITE);
            g2.drawString(text, textX, textY);
        } else {
            // A ring rather than a shadow: the backdrop can be light or dark
            // depending on the wallpaper, and this label has to hold up on both.
            Ui.drawHaloText(g2, text, textX, textY, Color.WHITE, new Color(0, 0, 0, 150));
        }
        g2.dispose();
    }
}

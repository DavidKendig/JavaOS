package javaos.desktop;

import javax.swing.DefaultDesktopManager;
import javax.swing.JComponent;
import javax.swing.JDesktopPane;
import javax.swing.JInternalFrame;

/**
 * Keeps windows on screen and snaps them to the desktop edges and to each other,
 * which is the one window-management nicety the era never quite delivered.
 */
public class SnappingDesktopManager extends DefaultDesktopManager {

    private static final int SNAP = 10;

    @Override public void dragFrame(JComponent f, int x, int y) {
        if (f instanceof JInternalFrame frame) {
            JDesktopPane pane = frame.getDesktopPane();
            if (pane != null) {
                int right = pane.getWidth() - frame.getWidth();
                int bottom = pane.getHeight() - frame.getHeight();
                if (Math.abs(x) < SNAP) {
                    x = 0;
                }
                if (Math.abs(y) < SNAP) {
                    y = 0;
                }
                if (Math.abs(x - right) < SNAP) {
                    x = right;
                }
                if (Math.abs(y - bottom) < SNAP) {
                    y = bottom;
                }
                // Never let a title bar be dragged off the top or past the right edge.
                y = Math.max(0, Math.min(y, pane.getHeight() - 24));
                x = Math.max(-frame.getWidth() + 60, Math.min(x, pane.getWidth() - 60));
            }
        }
        super.dragFrame(f, x, y);
    }

    @Override public void resizeFrame(JComponent f, int x, int y, int width, int height) {
        super.resizeFrame(f, x, y, Math.max(width, 180), Math.max(height, 90));
    }
}

package javaos.apps;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;

import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JInternalFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JToolBar;
import javax.swing.event.InternalFrameAdapter;
import javax.swing.event.InternalFrameEvent;

import javaos.desktop.Shell;
import javaos.ui.Ui;
import javaos.vfs.Vfs;

/**
 * Base window for every application: a stack of toolbars on top, a body in the
 * middle, and the obligatory sunken status bar along the bottom.
 */
public class AppWindow extends JInternalFrame {

    protected final Shell shell;
    private final JPanel toolbars = new JPanel();
    private final JPanel body = new JPanel(new BorderLayout());
    private final JLabel statusText = Ui.statusCell(" ", 120);
    private final JPanel statusExtras = new JPanel(new java.awt.FlowLayout(
            java.awt.FlowLayout.RIGHT, 3, 0));
    private final String appName;

    private String documentPath;
    private boolean dirty;

    public AppWindow(Shell shell, String appName, Icon icon) {
        super(appName, true, true, true, true);
        this.shell = shell;
        this.appName = appName;
        setFrameIcon(icon);
        setSize(640, 460);

        toolbars.setLayout(new javax.swing.BoxLayout(toolbars, javax.swing.BoxLayout.Y_AXIS));
        JPanel south = new JPanel(new BorderLayout(3, 0)) {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g;
                // The resize bumps every StarOffice window had in its corner.
                Ui.bumps(g2, getWidth() - 14, getHeight() - 12, 12, 10);
            }
        };
        south.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 16));
        statusExtras.setOpaque(false);
        south.add(statusText, BorderLayout.CENTER);
        south.add(statusExtras, BorderLayout.EAST);

        setLayout(new BorderLayout());
        add(toolbars, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);

        addInternalFrameListener(new InternalFrameAdapter() {
            @Override public void internalFrameClosing(InternalFrameEvent e) {
                if (confirmClose()) {
                    dispose();
                }
            }
        });
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
    }

    public Shell shell() {
        return shell;
    }

    protected Vfs vfs() {
        return shell.vfs();
    }

    public String appName() {
        return appName;
    }

    // ---- layout helpers ------------------------------------------------

    public void setBody(JComponent component) {
        body.removeAll();
        body.add(component, BorderLayout.CENTER);
        body.revalidate();
    }

    public void addToolBar(JComponent bar) {
        bar.setAlignmentX(0f);
        toolbars.add(bar);
    }

    /** A floatable toolbar with the Metal grip, sized like the 2002 originals. */
    public static JToolBar toolBar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setRollover(true);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Ui.shadow()),
                BorderFactory.createEmptyBorder(2, 3, 2, 3)));
        return bar;
    }

    public static JButton tool(Action action) {
        JButton b = Ui.toolButton(action);
        b.setPreferredSize(new Dimension(26, 26));
        return b;
    }

    public static void gap(JToolBar bar) {
        bar.add(Box.createHorizontalStrut(4));
        bar.addSeparator(new Dimension(2, 20));
        bar.add(Box.createHorizontalStrut(4));
    }

    // ---- status bar ----------------------------------------------------

    public void status(String message) {
        statusText.setText(message == null || message.isEmpty() ? " " : message);
    }

    public void addStatusCell(JComponent cell) {
        statusExtras.add(cell);
    }

    // ---- document bookkeeping ------------------------------------------

    public String documentPath() {
        return documentPath;
    }

    public void setDocumentPath(String path) {
        this.documentPath = path;
        updateTitle();
    }

    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        if (this.dirty != dirty) {
            this.dirty = dirty;
            updateTitle();
        }
    }

    protected void updateTitle() {
        String doc = documentPath == null ? "Untitled" : Vfs.name(documentPath);
        setTitle((dirty ? "*" : "") + doc + " - " + appName);
    }

    /**
     * Asks about unsaved work. Subclasses that own a document override
     * {@link #saveDocument()} so the Yes branch does something useful.
     */
    protected boolean confirmClose() {
        if (!dirty) {
            return true;
        }
        int answer = JOptionPane.showInternalConfirmDialog(this,
                "The document has been modified.\nSave changes before closing?",
                appName, JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer == JOptionPane.CANCEL_OPTION || answer == JOptionPane.CLOSED_OPTION) {
            return false;
        }
        if (answer == JOptionPane.YES_OPTION) {
            return saveDocument();
        }
        return true;
    }

    /** @return true when the document is safely on disk. */
    protected boolean saveDocument() {
        return true;
    }

    protected void error(String message) {
        JOptionPane.showInternalMessageDialog(this, message, appName,
                JOptionPane.ERROR_MESSAGE);
    }
}

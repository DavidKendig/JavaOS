package javaos.apps;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Deque;

import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.SpinnerNumberModel;

import javaos.desktop.Shell;
import javaos.ui.Icons;
import javaos.ui.Ui;
import javaos.vfs.Vfs;

/** A bitmap editor with the tool palette down the side, saving real PNGs. */
public class PaintApp extends AppWindow {

    private enum Tool { PENCIL, LINE, RECTANGLE, ELLIPSE, FILL, ERASER }

    private static final Color[] PALETTE = {
        Color.BLACK, new Color(0x40, 0x40, 0x40), new Color(0x80, 0x80, 0x80), Color.WHITE,
        new Color(0x8C, 0x1A, 0x11), new Color(0xC0, 0x39, 0x2B), new Color(0xE8, 0x7E, 0x2A),
        new Color(0xF2, 0xC4, 0x4C), new Color(0x1E, 0x5B, 0x2A), new Color(0x3E, 0x8E, 0x41),
        new Color(0x7D, 0xC4, 0x5E), new Color(0x14, 0x3A, 0x6B), new Color(0x2E, 0x5C, 0x94),
        new Color(0x6E, 0x9E, 0xD4), new Color(0x5B, 0x2C, 0x6F), new Color(0xA5, 0x6E, 0x3A),
    };

    private final BufferedImage image;
    private final Canvas canvas;
    private final Deque<BufferedImage> undoStack = new ArrayDeque<>();

    private Tool tool = Tool.PENCIL;
    private Color color = Color.BLACK;
    private int strokeWidth = 2;

    public PaintApp(Shell shell, String argument) {
        super(shell, "Paint", Icons.paint(16));
        setSize(660, 520);

        image = new BufferedImage(560, 380, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.dispose();

        canvas = new Canvas();
        JScrollPane scroll = new JScrollPane(canvas);
        scroll.getViewport().setBackground(Ui.darker(Ui.control(), 0.2));

        JPanel root = new JPanel(new BorderLayout(4, 4));
        root.add(toolPalette(), BorderLayout.WEST);
        root.add(scroll, BorderLayout.CENTER);
        root.add(palette(), BorderLayout.SOUTH);
        setBody(root);
        addToolBar(fileBar());

        if (argument != null && vfs().exists(argument)) {
            load(argument);
        } else {
            setDocumentPath(null);
            updateTitle();
            status(image.getWidth() + " x " + image.getHeight() + " pixels");
        }
    }

    private JToolBar fileBar() {
        JToolBar bar = toolBar();
        bar.add(tool(action("New", Icons.newDoc(20), this::clearCanvas)));
        bar.add(tool(action("Open...", Icons.folderOpen(20), this::openImage)));
        bar.add(tool(action("Save", Icons.disk(20), this::saveDocument)));
        gap(bar);
        bar.add(tool(action("Undo", Icons.arrow(20, 0), this::undo)));
        gap(bar);
        bar.add(new javax.swing.JLabel(" Width "));
        JSpinner width = new JSpinner(new SpinnerNumberModel(2, 1, 24, 1));
        width.setMaximumSize(new Dimension(52, 24));
        width.addChangeListener(e -> strokeWidth = (Integer) width.getValue());
        bar.add(width);
        return bar;
    }

    private JComponent toolPalette() {
        JPanel panel = new JPanel(new GridLayout(0, 2, 2, 2));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Tools"),
                BorderFactory.createEmptyBorder(2, 2, 2, 2)));
        ButtonGroup group = new ButtonGroup();
        addTool(panel, group, Tool.PENCIL, "Pencil", Icons.pencil(20));
        addTool(panel, group, Tool.LINE, "Line", Icons.lineTool(20));
        addTool(panel, group, Tool.RECTANGLE, "Rectangle", Icons.rectangleTool(20));
        addTool(panel, group, Tool.ELLIPSE, "Ellipse", Icons.ellipseTool(20));
        addTool(panel, group, Tool.FILL, "Fill", Icons.fillTool(20));
        addTool(panel, group, Tool.ERASER, "Eraser", Icons.eraser(20));
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(panel, BorderLayout.NORTH);
        return wrapper;
    }

    private void addTool(JPanel panel, ButtonGroup group, Tool value, String name, Icon glyph) {
        JToggleButton button = new JToggleButton(glyph);
        button.setToolTipText(name);
        button.setPreferredSize(new Dimension(34, 30));
        button.setFocusPainted(false);
        button.setSelected(value == Tool.PENCIL);
        button.addActionListener(e -> {
            tool = value;
            status(name + " selected.");
        });
        group.add(button);
        panel.add(button);
    }

    private JComponent palette() {
        JPanel strip = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 2, 2));
        strip.setBorder(BorderFactory.createEmptyBorder(2, 4, 4, 4));
        JPanel current = new JPanel();
        current.setPreferredSize(new Dimension(30, 24));
        current.setBackground(color);
        current.setBorder(BorderFactory.createLoweredBevelBorder());
        strip.add(current);
        strip.add(javax.swing.Box.createHorizontalStrut(6));
        for (Color swatch : PALETTE) {
            JPanel chip = new JPanel();
            chip.setPreferredSize(new Dimension(20, 20));
            chip.setBackground(swatch);
            chip.setBorder(BorderFactory.createRaisedBevelBorder());
            chip.setToolTipText(String.format("#%06X", swatch.getRGB() & 0xFFFFFF));
            chip.addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    color = swatch;
                    current.setBackground(swatch);
                }
            });
            strip.add(chip);
        }
        javax.swing.JButton more = new javax.swing.JButton("More...");
        more.setMargin(new java.awt.Insets(1, 4, 1, 4));
        more.setFont(new Font("Dialog", Font.PLAIN, 11));
        more.addActionListener(e -> {
            Color chosen = javax.swing.JColorChooser.showDialog(this, "Colour", color);
            if (chosen != null) {
                color = chosen;
                current.setBackground(chosen);
            }
        });
        strip.add(more);
        return strip;
    }

    private Action action(String name, Icon icon, Runnable body) {
        Action a = new AbstractAction(name, icon) {
            @Override public void actionPerformed(ActionEvent e) {
                body.run();
            }
        };
        a.putValue(Action.SHORT_DESCRIPTION, name);
        return a;
    }

    // ---- drawing -------------------------------------------------------

    private Graphics2D brush() {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(tool == Tool.ERASER ? Color.WHITE : color);
        g.setStroke(new BasicStroke(tool == Tool.ERASER ? strokeWidth * 3 : strokeWidth,
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        return g;
    }

    private void snapshot() {
        BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        undoStack.push(copy);
        if (undoStack.size() > 12) {
            undoStack.removeLast();
        }
        setDirty(true);
    }

    private void undo() {
        BufferedImage previous = undoStack.poll();
        if (previous == null) {
            status("Nothing to undo.");
            return;
        }
        Graphics2D g = image.createGraphics();
        g.drawImage(previous, 0, 0, null);
        g.dispose();
        canvas.repaint();
        status("Undone.");
    }

    private void clearCanvas() {
        snapshot();
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.dispose();
        canvas.repaint();
        setDocumentPath(null);
        setDirty(false);
        status("New picture.");
    }

    /** Scanline-free flood fill: good enough for a 560x380 canvas. */
    private void floodFill(int x, int y, Color replacement) {
        int target = image.getRGB(x, y);
        int fill = replacement.getRGB();
        if (target == fill) {
            return;
        }
        Deque<Point> queue = new ArrayDeque<>();
        queue.add(new Point(x, y));
        while (!queue.isEmpty()) {
            Point p = queue.poll();
            if (p.x < 0 || p.y < 0 || p.x >= image.getWidth() || p.y >= image.getHeight()) {
                continue;
            }
            if (image.getRGB(p.x, p.y) != target) {
                continue;
            }
            image.setRGB(p.x, p.y, fill);
            queue.add(new Point(p.x + 1, p.y));
            queue.add(new Point(p.x - 1, p.y));
            queue.add(new Point(p.x, p.y + 1));
            queue.add(new Point(p.x, p.y - 1));
        }
    }

    // ---- documents -----------------------------------------------------

    private void openImage() {
        String path = VfsChooser.open(this, vfs(),
                vfs().firstDirectory(Vfs.HOME + "/Pictures"),
                "Open Picture", "png", "jpg", "jpeg", "gif");
        if (path != null) {
            load(path);
        }
    }

    private void load(String path) {
        try {
            BufferedImage loaded = ImageIO.read(
                    new java.io.ByteArrayInputStream(vfs().readBytes(path)));
            if (loaded == null) {
                error("That is not an image JavaOS can read.");
                return;
            }
            Graphics2D g = image.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.drawImage(loaded, 0, 0, null);
            g.dispose();
            canvas.repaint();
            setDocumentPath(path);
            setDirty(false);
            status("Opened " + path + "  (" + loaded.getWidth() + " x "
                    + loaded.getHeight() + ")");
        } catch (Exception e) {
            error("Could not open " + Vfs.name(path) + ":\n" + e.getMessage());
        }
    }

    @Override protected boolean saveDocument() {
        String path = documentPath();
        if (path == null || !Vfs.extension(path).equals("png")) {
            path = VfsChooser.save(this, vfs(),
                    vfs().firstDirectory(Vfs.HOME + "/Pictures"),
                    "picture.png", "Save Picture", "png");
        }
        if (path == null) {
            return false;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            vfs().writeBytes(path, out.toByteArray());
            setDocumentPath(path);
            setDirty(false);
            status("Saved " + path);
            return true;
        } catch (Exception e) {
            error("Could not save the picture:\n" + e.getMessage());
            return false;
        }
    }

    @Override protected boolean confirmClose() {
        if (!isDirty()) {
            return true;
        }
        int answer = JOptionPane.showInternalConfirmDialog(this,
                "Save changes to the picture?", "Paint",
                JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer == JOptionPane.CANCEL_OPTION || answer == JOptionPane.CLOSED_OPTION) {
            return false;
        }
        return answer != JOptionPane.YES_OPTION || saveDocument();
    }

    /** The drawing surface, with a rubber-band preview for the shape tools. */
    private class Canvas extends JComponent {
        private Point start;
        private Point current;

        Canvas() {
            setPreferredSize(new Dimension(image.getWidth(), image.getHeight()));
            setBorder(BorderFactory.createLineBorder(Ui.darkShadow()));

            MouseAdapter mouse = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    start = e.getPoint();
                    current = e.getPoint();
                    snapshot();
                    if (tool == Tool.FILL && inside(e.getPoint())) {
                        floodFill(e.getX(), e.getY(), color);
                        repaint();
                    }
                }

                @Override public void mouseDragged(MouseEvent e) {
                    if (start == null) {
                        return;
                    }
                    if (tool == Tool.PENCIL || tool == Tool.ERASER) {
                        Graphics2D g = brush();
                        g.drawLine(current.x, current.y, e.getX(), e.getY());
                        g.dispose();
                    }
                    current = e.getPoint();
                    status("(" + e.getX() + ", " + e.getY() + ")");
                    repaint();
                }

                @Override public void mouseReleased(MouseEvent e) {
                    if (start == null) {
                        return;
                    }
                    Graphics2D g = brush();
                    int x = Math.min(start.x, e.getX());
                    int y = Math.min(start.y, e.getY());
                    int w = Math.abs(e.getX() - start.x);
                    int h = Math.abs(e.getY() - start.y);
                    switch (tool) {
                        case LINE -> g.drawLine(start.x, start.y, e.getX(), e.getY());
                        case RECTANGLE -> g.drawRect(x, y, w, h);
                        case ELLIPSE -> g.drawOval(x, y, w, h);
                        default -> {
                            // Pencil, eraser and fill have already drawn themselves.
                        }
                    }
                    g.dispose();
                    start = null;
                    repaint();
                }

                @Override public void mouseMoved(MouseEvent e) {
                    status("(" + e.getX() + ", " + e.getY() + ")");
                }

                private boolean inside(Point p) {
                    return p.x >= 0 && p.y >= 0
                            && p.x < image.getWidth() && p.y < image.getHeight();
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        @Override protected void paintComponent(Graphics g) {
            g.drawImage(image, 0, 0, null);
            if (start != null && current != null
                    && (tool == Tool.LINE || tool == Tool.RECTANGLE || tool == Tool.ELLIPSE)) {
                Graphics2D g2 = Ui.smooth(g);
                g2.setColor(color);
                g2.setStroke(new BasicStroke(strokeWidth, BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND, 1f, new float[] {4f, 4f}, 0f));
                int x = Math.min(start.x, current.x);
                int y = Math.min(start.y, current.y);
                int w = Math.abs(current.x - start.x);
                int h = Math.abs(current.y - start.y);
                switch (tool) {
                    case LINE -> g2.drawLine(start.x, start.y, current.x, current.y);
                    case RECTANGLE -> g2.drawRect(x, y, w, h);
                    default -> g2.drawOval(x, y, w, h);
                }
                g2.dispose();
            }
        }
    }
}

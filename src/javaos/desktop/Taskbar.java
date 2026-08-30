package javaos.desktop;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.beans.PropertyVetoException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.event.InternalFrameAdapter;
import javax.swing.event.InternalFrameEvent;

import javaos.Settings;
import javaos.apps.AppWindow;
import javaos.sys.Machine;
import javaos.ui.JavaLogo;
import javaos.ui.Ui;

/** The bar along the bottom: Launch button, quick starters, window list, tray. */
public class Taskbar extends JPanel {

    private final Shell shell;
    private final Settings settings;
    private final JPanel windowList = new JPanel(new GridLayout(1, 0, 2, 0));
    private final Map<AppWindow, JToggleButton> buttons = new LinkedHashMap<>();
    private final JLabel clock = new JLabel("", SwingConstants.CENTER);
    private final JLabel message = new JLabel("");
    private final LaunchButton launchButton;
    private Gauge cpuGauge;
    private Gauge ramGauge;
    private Timer messageTimer;

    public Taskbar(Shell shell, Settings settings, LaunchMenu menu) {
        this.shell = shell;
        this.settings = settings;
        setLayout(new BorderLayout(4, 0));
        // No inset at all on the left: the Launch slab fills the corner of the
        // screen edge to edge, top included. Everything else carries its own.
        setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4));
        setPreferredSize(new Dimension(0, 34));

        launchButton = new LaunchButton();
        launchButton.addActionListener(e -> menu.show(launchButton, 0, -menu.height()));

        // No quick starters any more: the Launch menu is the only way in, so
        // the button runs straight into the window list with a rule between.
        JPanel gap = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 2, 0));
        gap.setOpaque(false);
        gap.setBorder(BorderFactory.createEmptyBorder(3, 6, 2, 0));
        gap.add(new Separator());

        JPanel left = new JPanel(new BorderLayout());
        left.setOpaque(false);
        left.add(launchButton, BorderLayout.WEST);
        left.add(gap, BorderLayout.CENTER);

        windowList.setOpaque(false);
        JPanel middle = new JPanel(new BorderLayout());
        middle.setOpaque(false);
        middle.setBorder(BorderFactory.createEmptyBorder(3, 0, 2, 0));
        middle.add(windowList, BorderLayout.CENTER);

        add(left, BorderLayout.WEST);
        add(middle, BorderLayout.CENTER);
        add(tray(), BorderLayout.EAST);
    }

    private JComponent tray() {
        JPanel tray = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0));
        tray.setOpaque(false);
        tray.setBorder(BorderFactory.createEmptyBorder(3, 0, 2, 0));
        message.setFont(new Font("Dialog", Font.ITALIC, 12));
        message.setHorizontalAlignment(SwingConstants.RIGHT);
        message.setPreferredSize(new Dimension(260, 26));
        tray.add(message);
        cpuGauge = new Gauge("CPU", Machine::cpuLoad, Taskbar::cpuTip);
        ramGauge = new Gauge("RAM", Machine::ramLoad, Taskbar::ramTip);
        tray.add(cpuGauge);
        tray.add(ramGauge);
        clock.setFont(new Font("Dialog", Font.BOLD, 12));
        clock.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLoweredBevelBorder(),
                BorderFactory.createEmptyBorder(3, 10, 3, 10)));
        clock.setToolTipText("System clock");
        clock.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    shell.launch("settings", "clock");
                }
            }
        });
        tray.add(clock);
        new Timer(1000, e -> {
            tickClock();
            cpuGauge.sample();
            ramGauge.sample();
        }).start();
        tickClock();
        return tray;
    }

    /** What the CPU gauge says when the pointer rests on it. */
    private static String cpuTip() {
        double load = Machine.cpuLoad();
        String reading = load < 0 ? "load unavailable" : Math.round(load * 100) + "% busy";
        return "Processor: " + reading + " across " + Machine.threads()
                + " logical cores. Click for the System Monitor.";
    }

    /** What the RAM gauge says when the pointer rests on it. */
    private static String ramTip() {
        long total = Machine.ramTotal();
        if (total <= 0) {
            return "Physical memory unavailable. Click for the System Monitor.";
        }
        return "Memory: " + javaos.vfs.Vfs.humanSize(Machine.ramUsed()) + " of "
                + javaos.vfs.Vfs.humanSize(total) + " used ("
                + Math.round(Machine.ramLoad() * 100) + "%). Click for the System Monitor.";
    }

    private void tickClock() {
        String pattern = (settings.clock24h() ? "HH:mm" : "h:mm")
                + (settings.showSeconds() ? ":ss" : "")
                + (settings.clock24h() ? "" : " a");
        clock.setText(new SimpleDateFormat(pattern).format(new Date()));
        clock.setToolTipText(new SimpleDateFormat("EEEE, d MMMM yyyy").format(new Date()));
    }

    // ---- window list ---------------------------------------------------

    /** Adds a button for a newly opened window and keeps it in sync with the frame. */
    public void track(AppWindow frame) {
        JToggleButton button = new JToggleButton(frame.getTitle(), frame.getFrameIcon());
        button.setFont(new Font("Dialog", Font.PLAIN, 12));
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setMargin(new java.awt.Insets(1, 6, 1, 6));
        button.setFocusPainted(false);
        button.setToolTipText(frame.getTitle());
        button.addActionListener(e -> toggleFrame(frame));
        buttons.put(frame, button);
        windowList.add(button);
        relayout();

        frame.addPropertyChangeListener("title", e -> {
            button.setText(frame.getTitle());
            button.setToolTipText(frame.getTitle());
        });
        frame.addInternalFrameListener(new InternalFrameAdapter() {
            @Override public void internalFrameActivated(InternalFrameEvent e) {
                updateSelection();
            }

            @Override public void internalFrameDeactivated(InternalFrameEvent e) {
                updateSelection();
            }

            @Override public void internalFrameIconified(InternalFrameEvent e) {
                updateSelection();
            }

            @Override public void internalFrameClosed(InternalFrameEvent e) {
                JToggleButton b = buttons.remove(frame);
                if (b != null) {
                    windowList.remove(b);
                    relayout();
                }
            }
        });
        updateSelection();
    }

    private void toggleFrame(AppWindow frame) {
        try {
            if (frame.isIcon()) {
                frame.setIcon(false);
            } else if (frame.isSelected()) {
                frame.setIcon(true);
                return;
            }
            frame.setSelected(true);
            frame.toFront();
        } catch (PropertyVetoException ignored) {
            // A window that refuses to budge simply stays where it is.
        }
        updateSelection();
    }

    private void updateSelection() {
        for (Map.Entry<AppWindow, JToggleButton> e : buttons.entrySet()) {
            AppWindow f = e.getKey();
            e.getValue().setSelected(f.isSelected() && !f.isIcon());
            e.getValue().setFont(new Font("Dialog",
                    f.isIcon() ? Font.ITALIC : Font.PLAIN, 12));
        }
    }

    private void relayout() {
        int count = Math.max(buttons.size(), 1);
        windowList.setLayout(new GridLayout(1, count, 2, 0));
        windowList.revalidate();
        windowList.repaint();
    }

    // ---- painting ------------------------------------------------------

    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g;
        Ui.vGradient(g2, 0, 0, getWidth(), getHeight(),
                Ui.lighter(Ui.control(), 0.45), Ui.control());
        g2.setColor(Ui.light());
        g2.drawLine(0, 0, getWidth(), 0);
        g2.setColor(Ui.shadow());
        g2.drawLine(0, 1, getWidth(), 1);
    }

    /**
     * The Launch button: a green slab that fills the bottom-left corner, square
     * where it meets the screen edges and rounded on the right, with the Java
     * logo and bold white lettering on it.
     */
    private static class LaunchButton extends JButton {
        private static final Color GREEN_TOP = new Color(0x62, 0xB0, 0x45);
        private static final Color GREEN_BOTTOM = new Color(0x22, 0x6B, 0x21);
        private static final Color GREEN_EDGE = new Color(0x10, 0x3A, 0x10);
        private static final int RADIUS = 12;
        private static final int PAD = 8;
        private static final int GAP = 6;

        LaunchButton() {
            super("Launch");
            setFont(new Font("Dialog", Font.BOLD, 13));
            setForeground(Color.WHITE);
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);       // the taskbar gradient shows past the rounded corner
            setRolloverEnabled(true);
            setPreferredSize(new Dimension(112, 34));
            setToolTipText("Launch applications");
        }

        /** Square left edge, rounded right: a rounded rectangle with its left half squared off. */
        private static java.awt.Shape slab(double x, double y, double w, double h) {
            java.awt.geom.Area area = new java.awt.geom.Area(
                    new java.awt.geom.RoundRectangle2D.Double(x, y, w, h, RADIUS * 2, RADIUS * 2));
            area.add(new java.awt.geom.Area(
                    new java.awt.geom.Rectangle2D.Double(x, y, w - RADIUS, h)));
            return area;
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = Ui.smooth(g);
            int w = getWidth();
            int h = getHeight();
            boolean down = getModel().isPressed() || getModel().isSelected();
            double lift = !down && getModel().isRollover() ? 0.14 : 0.0;

            java.awt.Shape slab = slab(0, 0, w, h);
            java.awt.Shape clip = g2.getClip();
            g2.clip(slab);
            Ui.vGradient(g2, 0, 0, w, h,
                    Ui.lighter(down ? GREEN_BOTTOM : GREEN_TOP, lift),
                    Ui.lighter(down ? GREEN_EDGE : GREEN_BOTTOM, lift));

            // The sheen across the top half that every slab of the era carried.
            int sheen = Math.max(1, h / 2);
            g2.setPaint(new java.awt.GradientPaint(
                    0, 0, new Color(255, 255, 255, down ? 22 : 68),
                    0, sheen, new Color(255, 255, 255, 0)));
            g2.fillRect(0, 0, w, sheen);

            // Lit along the top and left, where the light of 2002 always came from.
            g2.setColor(new Color(255, 255, 255, down ? 40 : 110));
            g2.drawLine(0, 0, w - RADIUS, 0);
            g2.drawLine(0, 0, 0, h - 1);
            g2.setClip(clip);

            g2.setColor(GREEN_EDGE);
            g2.draw(slab(0.5, 0.5, w - 1, h - 1));

            int shift = down ? 1 : 0;
            int logo = Math.min(28, h - 3);
            double logoX = PAD + shift;
            double logoY = (h - JavaLogo.height(logo)) / 2.0 + shift;
            JavaLogo.paintAt(g2, logoX + 1, logoY + 1, logo, new Color(0x0B, 0x2C, 0x0B, 0x88));
            JavaLogo.paintAt(g2, logoX, logoY, logo);

            g2.setFont(getFont());
            java.awt.FontMetrics fm = g2.getFontMetrics();
            int textX = (int) (logoX + JavaLogo.width(logo)) + GAP;
            int baseline = (h + fm.getAscent() - fm.getDescent()) / 2 + shift;
            Ui.drawOutlinedText(g2, getText(), textX, baseline,
                    Color.WHITE, new Color(0x0B, 0x2C, 0x0B, 0xAA));

            g2.dispose();
        }
    }

    /** Vertical etched divider between taskbar sections. */
    private static class Separator extends JComponent {
        Separator() {
            setPreferredSize(new Dimension(5, 26));
        }

        @Override protected void paintComponent(Graphics g) {
            Ui.etchedLine(g, 1, 3, 0, getHeight() - 6, true);
        }
    }

    /**
     * A tray gauge: a caption to the left of an LED bar, the way every system
     * monitor of the era sat in the corner. Clicking one opens the real thing.
     */
    private class Gauge extends JComponent {
        private static final int BAR = 52;

        private final String caption;
        private final java.util.function.DoubleSupplier load;
        private final java.util.function.Supplier<String> tip;
        private double value;

        Gauge(String caption, java.util.function.DoubleSupplier load,
                java.util.function.Supplier<String> tip) {
            this.caption = caption;
            this.load = load;
            this.tip = tip;
            setFont(new Font("Dialog", Font.BOLD, 10));
            setPreferredSize(new Dimension(BAR + 28, 26));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    shell.launch("monitor");
                }
            });
            sample();
        }

        void sample() {
            double reading = load.getAsDouble();
            value = reading < 0 ? 0 : Math.min(1, reading);
            setToolTipText(tip.get());
            repaint();
        }

        @Override protected void paintComponent(Graphics g) {
            int h = getHeight();
            g.setFont(getFont());
            java.awt.FontMetrics fm = g.getFontMetrics();
            g.setColor(Ui.darkShadow());
            g.drawString(caption, 0, (h + fm.getAscent() - fm.getDescent()) / 2);

            int x = getWidth() - BAR;
            Ui.bevel(g, x, 1, BAR, h - 2, false);
            int inner = BAR - 4;
            g.setColor(Color.BLACK);
            g.fillRect(x + 2, 3, inner, h - 6);
            int bars = inner / 4;
            int lit = (int) Math.round(bars * value);
            for (int i = 0; i < bars; i++) {
                double t = i / (double) bars;
                g.setColor(i < lit
                        ? (t > 0.8 ? new Color(0xE0, 0x50, 0x40)
                                : t > 0.6 ? new Color(0xE0, 0xC0, 0x40) : new Color(0x50, 0xD0, 0x50))
                        : new Color(0x18, 0x28, 0x18));
                g.fillRect(x + 3 + i * 4, 5, 3, h - 10);
            }
        }
    }

    /** Shows a transient system message in the tray, cleared after a few seconds. */
    public void message(String text) {
        message.setText(text == null ? "" : text);
        if (messageTimer != null) {
            messageTimer.stop();
        }
        messageTimer = new Timer(5000, e -> message.setText(""));
        messageTimer.setRepeats(false);
        messageTimer.start();
    }
}

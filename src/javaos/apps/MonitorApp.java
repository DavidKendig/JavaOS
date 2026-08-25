package javaos.apps;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;

import javaos.desktop.Shell;
import javaos.sys.Machine;
import javaos.ui.Icons;
import javaos.ui.Ui;
import javaos.vfs.Vfs;

/**
 * The System Monitor: what the machine is doing and what it is made of.
 *
 * <p>The readings come from {@link Machine} -- overall load and physical memory
 * from the JDK's own bean, per-core load and the hardware inventory from a
 * platform probe that runs only while this window is open. The probe answers
 * asynchronously, so the core grid and the hardware table fill in a moment after
 * the window appears, and say so in the status line until they do.
 */
public class MonitorApp extends AppWindow {

    private final LoadGraph cpuGraph = new LoadGraph(new Color(0x40, 0xD0, 0x40));
    private final LoadGraph heapGraph = new LoadGraph(new Color(0x50, 0xB0, 0xE0));
    private final JLabel cpuLabel = new JLabel();
    private final JLabel coreLabel = new JLabel();
    private final CoreGrid cores = new CoreGrid();
    private final Bar ramBar = new Bar();
    private final JLabel ramLabel = new JLabel();
    private final JLabel swapLabel = new JLabel();
    private final JPanel graphics = new JPanel();
    private final JLabel heapLabel = new JLabel();
    private final JLabel threadLabel = new JLabel();
    private final JLabel volumeLabel = new JLabel();

    private final DefaultTableModel hardware = readOnlyModel("Component", "Detail");
    private final DefaultTableModel threads = readOnlyModel(
            "Name", "State", "Priority", "Daemon");

    private final Timer timer = new Timer(1000, e -> sample());
    private Machine.Hardware shown;

    public MonitorApp(Shell shell, String argument) {
        super(shell, "System Monitor", Icons.monitor(16));
        setSize(620, 520);

        Machine.openDetail();

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Performance", Icons.monitor(16), performanceTab());
        tabs.addTab("Hardware", Icons.computer(16), scroll(new JTable(hardware), 190));
        tabs.addTab("Java", Icons.javaLogo(16), javaTab());
        tabs.addTab("Threads", Icons.settings(16), scroll(new JTable(threads), 220));
        tabs.addTab("System", Icons.disk(16), propertiesTab());
        setBody(tabs);

        addInternalFrameListener(new javax.swing.event.InternalFrameAdapter() {
            @Override public void internalFrameClosed(javax.swing.event.InternalFrameEvent e) {
                timer.stop();
                Machine.closeDetail();
            }
        });
        sample();
        timer.start();
    }

    // ---- performance ----------------------------------------------------

    private JComponent performanceTab() {
        Box column = Box.createVerticalBox();
        column.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel cpuBox = titled("Processor", new BorderLayout(4, 4));
        cpuBox.add(cpuGraph, BorderLayout.CENTER);
        JPanel cpuText = new JPanel(new GridLayout(2, 1));
        for (JLabel label : new JLabel[] {cpuLabel, coreLabel}) {
            label.setFont(new Font("Monospaced", Font.PLAIN, 12));
            cpuText.add(label);
        }
        cpuBox.add(cpuText, BorderLayout.SOUTH);

        JPanel coreBox = titled("Cores", new BorderLayout());
        coreBox.add(cores, BorderLayout.CENTER);

        JPanel memoryBox = titled("Memory", new BorderLayout(4, 4));
        memoryBox.add(ramBar, BorderLayout.NORTH);
        JPanel memoryText = new JPanel(new GridLayout(2, 1));
        for (JLabel label : new JLabel[] {ramLabel, swapLabel}) {
            label.setFont(new Font("Monospaced", Font.PLAIN, 12));
            memoryText.add(label);
        }
        memoryBox.add(memoryText, BorderLayout.CENTER);

        JPanel graphicsBox = titled("Graphics", new BorderLayout());
        graphics.setLayout(new BoxLayout(graphics, BoxLayout.Y_AXIS));
        graphicsBox.add(graphics, BorderLayout.CENTER);

        column.add(cpuBox);
        column.add(Box.createVerticalStrut(6));
        column.add(coreBox);
        column.add(Box.createVerticalStrut(6));
        column.add(memoryBox);
        column.add(Box.createVerticalStrut(6));
        column.add(graphicsBox);

        JScrollPane scroller = new JScrollPane(column);
        scroller.getVerticalScrollBar().setUnitIncrement(16);
        scroller.setBorder(BorderFactory.createEmptyBorder());
        return scroller;
    }

    private JComponent javaTab() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel graphBox = titled("Heap usage history", new BorderLayout());
        graphBox.add(heapGraph, BorderLayout.CENTER);

        JPanel readouts = new JPanel(new GridLayout(3, 1, 0, 2));
        readouts.setBorder(BorderFactory.createTitledBorder("Totals"));
        for (JLabel label : new JLabel[] {heapLabel, threadLabel, volumeLabel}) {
            label.setFont(new Font("Monospaced", Font.PLAIN, 12));
            readouts.add(label);
        }

        JButton collect = new JButton("Collect garbage");
        collect.addActionListener(e -> {
            System.gc();
            sample();
            status("Requested a garbage collection.");
        });
        JPanel south = new JPanel(new BorderLayout());
        south.add(readouts, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT));
        buttons.add(collect);
        south.add(buttons, BorderLayout.SOUTH);

        panel.add(graphBox, BorderLayout.CENTER);
        panel.add(south, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent propertiesTab() {
        DefaultTableModel model = readOnlyModel("Property", "Value");
        String[] keys = {"java.version", "java.vendor", "java.vm.name", "os.name", "os.arch",
            "os.version", "user.name", "file.encoding", "java.home"};
        for (String key : keys) {
            model.addRow(new Object[] {key, System.getProperty(key)});
        }
        model.addRow(new Object[] {"javaos.volume", vfs().realRoot().toString()});
        model.addRow(new Object[] {"javaos.apps", String.valueOf(shell.apps().size())});
        return scroll(new JTable(model), 150);
    }

    // ---- sampling -------------------------------------------------------

    private void sample() {
        sampleProcessor();
        sampleMemory();
        sampleJava();
        Machine.Hardware found = Machine.hardware();
        if (found != shown) {
            shown = found;
            fillHardware(found);
            fillGraphics(found);
        }
        status(Machine.detailStatus());
    }

    private void sampleProcessor() {
        double load = Machine.cpuLoad();
        cpuGraph.push(Math.max(0, load));
        cpuLabel.setText(String.format("  System   %s     This JVM  %s",
                percent(load), percent(Machine.processCpuLoad())));

        double[] loads = Machine.coreLoads();
        cores.show(loads);
        Machine.Hardware hw = Machine.hardware();
        String name = hw.cpu();
        coreLabel.setText(String.format("  %s  (%d cores, %d threads)",
                name, hw.cores() > 0 ? hw.cores() : Machine.threads(),
                hw.threads() > 0 ? hw.threads() : Machine.threads()));
    }

    private void sampleMemory() {
        long total = Machine.ramTotal();
        long used = Machine.ramUsed();
        double fraction = Machine.ramLoad();
        ramBar.set(Math.max(0, fraction));
        ramLabel.setText(total <= 0
                ? "  Memory   unavailable on this platform"
                : String.format("  Memory   %s used of %s installed  (%s free)",
                        Vfs.humanSize(used), Vfs.humanSize(total),
                        Vfs.humanSize(Machine.ramFree())));
        long swap = Machine.swapTotal();
        swapLabel.setText(swap <= 0
                ? "  Swap     none configured"
                : String.format("  Swap     %s used of %s",
                        Vfs.humanSize(Machine.swapUsed()), Vfs.humanSize(swap)));
    }

    private void sampleJava() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        heapGraph.push(used / (double) rt.maxMemory());
        heapLabel.setText(String.format("  Heap     %s used of %s allocated (max %s)",
                Vfs.humanSize(used), Vfs.humanSize(rt.totalMemory()),
                Vfs.humanSize(rt.maxMemory())));

        Thread[] all = new Thread[Thread.activeCount() + 8];
        int count = Thread.enumerate(all);
        threads.setRowCount(0);
        for (int i = 0; i < count; i++) {
            Thread t = all[i];
            if (t != null) {
                threads.addRow(new Object[] {t.getName(), t.getState(), t.getPriority(),
                    t.isDaemon() ? "yes" : "no"});
            }
        }
        threadLabel.setText(String.format("  Threads  %d live, %d processors",
                count, rt.availableProcessors()));
        volumeLabel.setText(String.format("  Volume   %s used by %d open window(s)",
                Vfs.humanSize(vfs().usedBytes()), shell.pane().getAllFrames().length));
    }

    /** Rewrites the inventory table whenever the probe reports something new. */
    private void fillHardware(Machine.Hardware hw) {
        hardware.setRowCount(0);
        hardware.addRow(new Object[] {"Processor", hw.cpu()});
        hardware.addRow(new Object[] {"Cores", (hw.cores() > 0 ? hw.cores() : Machine.threads())
                + " physical, " + (hw.threads() > 0 ? hw.threads() : Machine.threads())
                + " logical"});
        if (hw.mhz() > 0) {
            hardware.addRow(new Object[] {"Base clock", hw.mhz() + " MHz"});
        }
        if (Machine.ramTotal() > 0) {
            hardware.addRow(new Object[] {"Memory installed", Vfs.humanSize(Machine.ramTotal())});
        }
        int module = 0;
        for (Machine.MemoryStick stick : hw.memory()) {
            StringBuilder detail = new StringBuilder(Vfs.humanSize(stick.bytes()));
            if (!stick.type().isBlank()) {
                detail.append(' ').append(stick.type());
            }
            if (stick.speedMhz() > 0) {
                detail.append('-').append(stick.speedMhz());
            }
            if (!stick.maker().isBlank()) {
                detail.append("  ").append(stick.maker());
            }
            if (!stick.slot().isBlank()) {
                detail.append("  (").append(stick.slot()).append(')');
            }
            // Numbered here rather than by slot: soldered memory often reports
            // the same locator for every module.
            hardware.addRow(new Object[] {"Memory module " + (++module), detail.toString()});
        }
        for (Machine.Gpu gpu : hw.gpus()) {
            String detail = gpu.vram() > 0
                    ? gpu.name() + "  --  " + Vfs.humanSize(gpu.vram()) + " VRAM"
                    : gpu.name();
            if (!gpu.driver().isBlank()) {
                detail += "  (driver " + gpu.driver() + ")";
            }
            hardware.addRow(new Object[] {"Graphics", detail});
        }
        for (Machine.Disk disk : hw.disks()) {
            hardware.addRow(new Object[] {"Disk",
                disk.model() + "  --  " + Vfs.humanSize(disk.bytes()) + "  " + disk.kind()});
        }
        if (!hw.board().isBlank()) {
            hardware.addRow(new Object[] {"Motherboard", hw.board()});
        }
        if (!hw.system().isBlank()) {
            hardware.addRow(new Object[] {"System", hw.system()});
        }
        hardware.addRow(new Object[] {"Operating system",
            hw.os().isBlank() ? Machine.platform() : hw.os()});
        hardware.addRow(new Object[] {"Java", System.getProperty("java.version")
                + "  (" + System.getProperty("java.vm.name") + ")"});
    }

    private void fillGraphics(Machine.Hardware hw) {
        graphics.removeAll();
        if (hw.gpus().isEmpty()) {
            graphics.add(monospaced("  No adapter reported yet."));
        } else {
            for (Machine.Gpu gpu : hw.gpus()) {
                graphics.add(monospaced(String.format("  %-38s %s", gpu.name(),
                        gpu.vram() > 0 ? Vfs.humanSize(gpu.vram()) + " VRAM" : "VRAM unknown")));
            }
            if (hw.gpus().size() > 1) {
                graphics.add(monospaced(String.format("  %-38s %s", "Total video memory",
                        Vfs.humanSize(hw.vram()))));
            }
        }
        graphics.revalidate();
        graphics.repaint();
    }

    // ---- small helpers --------------------------------------------------

    private static JLabel monospaced(String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("Monospaced", Font.PLAIN, 12));
        return label;
    }

    private static JPanel titled(String title, java.awt.LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setBorder(BorderFactory.createTitledBorder(title));
        return panel;
    }

    private static DefaultTableModel readOnlyModel(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    private static JComponent scroll(JTable table, int firstColumnWidth) {
        table.setRowHeight(18);
        table.setFont(new Font("Dialog", Font.PLAIN, 11));
        table.getTableHeader().setFont(new Font("Dialog", Font.BOLD, 11));
        if (table.getColumnModel().getColumnCount() > 0) {
            // Spare width belongs to the detail column, not shared out evenly,
            // and the first column keeps its width instead of being squeezed.
            table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
            javax.swing.table.TableColumn first = table.getColumnModel().getColumn(0);
            first.setMinWidth(firstColumnWidth);
            first.setPreferredWidth(firstColumnWidth);
        }
        return new JScrollPane(table);
    }

    private static String percent(double load) {
        return load < 0 ? " --%" : String.format("%3d%%", Math.round(load * 100));
    }

    // ---- the drawn parts ------------------------------------------------

    /** Scrolling area graph, one pixel column per sample, in the colour given. */
    private static class LoadGraph extends JComponent {
        private final double[] samples = new double[240];
        private final Color trace;
        private int at;

        LoadGraph(Color trace) {
            this.trace = trace;
            setPreferredSize(new Dimension(400, 120));
            setBorder(Ui.sunkenPanel());
        }

        void push(double fraction) {
            samples[at] = Math.max(0, Math.min(1, fraction));
            at = (at + 1) % samples.length;
            repaint();
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = Ui.smooth(g);
            int w = getWidth();
            int h = getHeight();
            g2.setColor(new Color(0x08, 0x14, 0x08));
            g2.fillRect(0, 0, w, h);
            g2.setColor(new Color(0x18, 0x38, 0x18));
            for (int x = 0; x < w; x += 20) {
                g2.drawLine(x, 0, x, h);
            }
            for (int y = 0; y < h; y += 15) {
                g2.drawLine(0, y, w, y);
            }
            g2.setColor(trace);
            int points = samples.length;
            for (int i = 1; i < points; i++) {
                int indexA = (at + i - 1) % points;
                int indexB = (at + i) % points;
                int xa = (i - 1) * w / points;
                int xb = i * w / points;
                int ya = h - (int) (samples[indexA] * h);
                int yb = h - (int) (samples[indexB] * h);
                g2.drawLine(xa, ya, xb, yb);
            }
            g2.dispose();
        }
    }

    /** A labelled horizontal bar: one per core, and one for physical memory. */
    private static class Bar extends JComponent {
        private String caption = "";
        private double value;

        Bar() {
            setPreferredSize(new Dimension(200, 18));
            setFont(new Font("Dialog", Font.PLAIN, 11));
        }

        void set(double fraction) {
            set(fraction, "");
        }

        void set(double fraction, String caption) {
            this.value = Math.max(0, Math.min(1, fraction));
            this.caption = caption;
            repaint();
        }

        @Override protected void paintComponent(Graphics g) {
            int w = getWidth();
            int h = getHeight();
            int text = caption.isEmpty() ? 0 : 72;
            if (!caption.isEmpty()) {
                g.setFont(getFont());
                java.awt.FontMetrics fm = g.getFontMetrics();
                g.setColor(Ui.darkShadow());
                g.drawString(caption, 0, (h + fm.getAscent() - fm.getDescent()) / 2);
            }
            int x = text;
            int bw = w - text;
            g.setColor(Color.BLACK);
            g.fillRect(x, 1, bw, h - 2);
            int lit = (int) Math.round((bw - 2) * value);
            g.setColor(value > 0.85 ? new Color(0xE0, 0x50, 0x40)
                    : value > 0.65 ? new Color(0xE0, 0xC0, 0x40) : new Color(0x50, 0xD0, 0x50));
            g.fillRect(x + 1, 2, lit, h - 4);
            Ui.bevel(g, x, 1, bw, h - 2, false);
        }
    }

    /** One bar per logical core, relaid out when the probe changes its mind. */
    private static class CoreGrid extends JPanel {
        private Bar[] bars = new Bar[0];

        CoreGrid() {
            setLayout(new GridLayout(0, 2, 6, 2));
        }

        void show(double[] loads) {
            if (loads.length == 0) {
                if (bars.length != 0) {
                    removeAll();
                    bars = new Bar[0];
                    add(monospaced("  Waiting for the first per-core sample..."));
                    revalidate();
                    repaint();
                }
                return;
            }
            if (bars.length != loads.length) {
                removeAll();
                bars = new Bar[loads.length];
                for (int i = 0; i < loads.length; i++) {
                    bars[i] = new Bar();
                    add(bars[i]);
                }
                revalidate();
            }
            for (int i = 0; i < loads.length; i++) {
                bars[i].set(loads[i], String.format("Core %-2d %3d%%", i, Math.round(loads[i] * 100)));
            }
            repaint();
        }
    }
}

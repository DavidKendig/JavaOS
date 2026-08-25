package javaos.sys;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;

/**
 * What the desktop knows about the machine underneath it.
 *
 * <p>Two tiers. The cheap tier -- overall processor load, physical memory, swap
 * -- comes from the JDK's own {@code com.sun.management} bean and is safe to
 * poll once a second from the taskbar. The detailed tier -- per-core load and
 * the hardware inventory -- costs a helper process on most platforms, so it is
 * started only while something is looking at it. {@link #openDetail()} and
 * {@link #closeDetail()} bracket that interest; the probe stops when the last
 * viewer goes away.
 *
 * <p>Everything degrades: if a platform cannot answer, the readings come back
 * empty or unknown rather than throwing, and {@link #detailStatus()} says why.
 */
public final class Machine {

    /** A display adapter and the memory soldered to it. */
    public record Gpu(String name, long vram, String driver) {
    }

    /** One memory module in one slot. */
    public record MemoryStick(String slot, long bytes, int speedMhz, String maker, String type) {
    }

    /** One drive, as the platform describes it. */
    public record Disk(String model, long bytes, String kind) {
    }

    /** The static inventory: what is installed, as opposed to what it is doing. */
    public record Hardware(String cpu, int cores, int threads, int mhz,
            String board, String system, String os,
            List<Gpu> gpus, List<MemoryStick> memory, List<Disk> disks) {

        public static final Hardware UNKNOWN = new Hardware("Unknown processor", 0, 0, 0,
                "", "", "", List.of(), List.of(), List.of());

        /** Total video memory across every adapter reported. */
        public long vram() {
            return gpus.stream().mapToLong(Gpu::vram).sum();
        }
    }

    private static final com.sun.management.OperatingSystemMXBean OS = sunBean();

    private static Probe probe;
    private static int viewers;
    private static boolean hooked;

    private static volatile double[] cores = new double[0];
    private static volatile Hardware hardware = Hardware.UNKNOWN;
    private static volatile String status = "Not started.";

    private Machine() {
    }

    private static com.sun.management.OperatingSystemMXBean sunBean() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        return bean instanceof com.sun.management.OperatingSystemMXBean sun ? sun : null;
    }

    // ---- the cheap tier, safe to poll from the taskbar ------------------

    /** Processor load across the whole machine, 0..1, or -1 if unknown. */
    public static double cpuLoad() {
        if (OS == null) {
            return -1;
        }
        double load = OS.getCpuLoad();
        return Double.isNaN(load) || load < 0 ? -1 : Math.min(1, load);
    }

    /** Load of this JVM alone, 0..1, or -1 if unknown. */
    public static double processCpuLoad() {
        if (OS == null) {
            return -1;
        }
        double load = OS.getProcessCpuLoad();
        return Double.isNaN(load) || load < 0 ? -1 : Math.min(1, load);
    }

    public static long ramTotal() {
        return OS == null ? 0 : OS.getTotalMemorySize();
    }

    public static long ramFree() {
        return OS == null ? 0 : OS.getFreeMemorySize();
    }

    public static long ramUsed() {
        return Math.max(0, ramTotal() - ramFree());
    }

    /** Physical memory in use, 0..1, or -1 if the platform will not say. */
    public static double ramLoad() {
        long total = ramTotal();
        return total <= 0 ? -1 : ramUsed() / (double) total;
    }

    public static long swapTotal() {
        return OS == null ? 0 : OS.getTotalSwapSpaceSize();
    }

    public static long swapUsed() {
        return OS == null ? 0 : Math.max(0, OS.getTotalSwapSpaceSize() - OS.getFreeSwapSpaceSize());
    }

    /** Logical processors, which is what the core grid draws one cell for. */
    public static int threads() {
        return Runtime.getRuntime().availableProcessors();
    }

    // ---- the detailed tier, running only while someone watches ----------

    /** Registers interest in per-core load and the inventory, starting the probe. */
    public static synchronized void openDetail() {
        if (viewers++ == 0) {
            probe = Probe.forThisPlatform();
            status = "Probing...";
            probe.start();
            hookShutdown();
        }
    }

    /**
     * A probe may own a helper process, and a child process outlives the JVM
     * that started it. One hook, registered the first time a probe runs, makes
     * sure nothing is left behind if the desktop exits with the monitor open.
     */
    private static void hookShutdown() {
        if (hooked) {
            return;
        }
        hooked = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Probe live;
            synchronized (Machine.class) {
                live = probe;
                probe = null;
            }
            if (live != null) {
                live.stop();
            }
        }, "javaos-probe-shutdown"));
    }

    /** Withdraws that interest; the probe stops when the last viewer leaves. */
    public static synchronized void closeDetail() {
        viewers = Math.max(0, viewers - 1);
        if (viewers == 0 && probe != null) {
            probe.stop();
            probe = null;
            status = "Stopped.";
        }
    }

    /** Per-core load, 0..1 each, in core order. Empty when unknown. */
    public static double[] coreLoads() {
        return cores.clone();
    }

    /** The installed hardware, or {@link Hardware#UNKNOWN} until the probe answers. */
    public static Hardware hardware() {
        return hardware;
    }

    /** What the detailed probe is doing, for the monitor's status line. */
    public static String detailStatus() {
        return status;
    }

    /** The platform name, tidied up for display. */
    public static String platform() {
        return System.getProperty("os.name", "unknown")
                + " " + System.getProperty("os.version", "")
                + " (" + System.getProperty("os.arch", "") + ")";
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    // ---- what the probes call back into -------------------------------

    static void reportCores(double[] loads) {
        cores = loads;
    }

    static void reportHardware(Hardware found) {
        hardware = found;
    }

    static void reportStatus(String message) {
        status = message;
    }
}

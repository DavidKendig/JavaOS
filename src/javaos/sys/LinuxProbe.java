package javaos.sys;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Linux detail, straight out of the kernel's own files.
 *
 * <p>{@code /proc/stat} carries a running tally of jiffies per core, so load is
 * the difference between two readings rather than an instantaneous figure; the
 * first sample after opening the monitor therefore reads zero. Video memory
 * comes from the DRM nodes where the driver publishes it, and the GPU name from
 * {@code lspci} if it happens to be installed.
 */
final class LinuxProbe implements Probe {

    private static final Path STAT = Path.of("/proc/stat");

    private volatile Thread thread;
    private volatile boolean running;
    private long[][] previous = new long[0][];

    @Override public void start() {
        running = true;
        thread = new Thread(this::run, "javaos-proc");
        thread.setDaemon(true);
        thread.start();
    }

    @Override public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void run() {
        readInventory();
        while (running) {
            try {
                sampleCores();
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                Machine.reportStatus("Per-core sampling unavailable: " + e.getMessage());
                return;
            }
        }
    }

    /** Each "cpuN" row is a tally; load is busy jiffies over total, between readings. */
    private void sampleCores() throws Exception {
        List<long[]> now = new ArrayList<>();
        for (String line : Files.readAllLines(STAT)) {
            if (line.startsWith("cpu") && !line.startsWith("cpu ")) {
                String[] f = line.trim().split("\\s+");
                long total = 0;
                long idle = 0;
                for (int i = 1; i < f.length; i++) {
                    long value = Long.parseLong(f[i]);
                    total += value;
                    if (i == 4 || i == 5) {         // idle and iowait
                        idle += value;
                    }
                }
                now.add(new long[] {total, idle});
            }
        }
        long[][] current = now.toArray(new long[0][]);
        if (previous.length == current.length && current.length > 0) {
            double[] loads = new double[current.length];
            for (int i = 0; i < current.length; i++) {
                long total = current[i][0] - previous[i][0];
                long idle = current[i][1] - previous[i][1];
                loads[i] = total <= 0 ? 0 : Math.max(0, Math.min(1, (total - idle) / (double) total));
            }
            Machine.reportCores(loads);
            Machine.reportStatus("Sampling " + loads.length + " cores from /proc/stat.");
        }
        previous = current;
    }

    private void readInventory() {
        String cpu = "Unknown processor";
        int threads = 0;
        int cores = 0;
        int mhz = 0;
        try {
            for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
                String[] kv = line.split(":", 2);
                if (kv.length < 2) {
                    continue;
                }
                String key = kv[0].trim();
                String value = kv[1].trim();
                switch (key) {
                    case "model name" -> {
                        cpu = value;
                        threads++;
                    }
                    case "cpu cores" -> cores = Math.max(cores, (int) number(value));
                    case "cpu MHz" -> mhz = Math.max(mhz, (int) number(value));
                    default -> { }
                }
            }
        } catch (Exception ignored) {
            // /proc/cpuinfo is optional on stripped-down kernels.
        }
        if (threads == 0) {
            threads = Machine.threads();
        }
        if (cores == 0) {
            cores = threads;
        }

        Machine.reportHardware(new Machine.Hardware(cpu, cores, threads, mhz,
                read(Path.of("/sys/devices/virtual/dmi/id/board_name")),
                read(Path.of("/sys/devices/virtual/dmi/id/product_name")),
                Machine.platform(), gpus(), List.of(), disks()));
    }

    /** GPU names from lspci where it exists, video memory from the DRM nodes. */
    private static List<Machine.Gpu> gpus() {
        List<Machine.Gpu> found = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String line : run("lspci")) {
            if (line.contains("VGA compatible controller") || line.contains("3D controller")) {
                // "01:00.0 VGA compatible controller: NVIDIA ... [GeForce RTX 3070]"
                int colon = line.indexOf(": ");
                names.add(colon < 0 ? line : line.substring(colon + 2).trim());
            }
        }
        int index = 0;
        try (var cards = Files.list(Path.of("/sys/class/drm"))) {
            List<Path> nodes = cards.filter(p -> p.getFileName().toString().matches("card\\d+"))
                    .sorted().toList();
            for (Path card : nodes) {
                long vram = (long) number(read(card.resolve("device/mem_info_vram_total")));
                String name = index < names.size() ? names.get(index) : card.getFileName().toString();
                found.add(new Machine.Gpu(name, vram, ""));
                index++;
            }
        } catch (Exception ignored) {
            // No DRM nodes: fall back to whatever lspci said.
        }
        if (found.isEmpty()) {
            for (String name : names) {
                found.add(new Machine.Gpu(name, 0, ""));
            }
        }
        return List.copyOf(found);
    }

    private static List<Machine.Disk> disks() {
        List<Machine.Disk> found = new ArrayList<>();
        for (String line : run("lsblk", "-dbno", "NAME,SIZE,MODEL,ROTA")) {
            String[] f = line.trim().split("\\s+", 4);
            if (f.length >= 2) {
                String model = f.length >= 3 ? f[2] : f[0];
                boolean spinning = line.trim().endsWith("1");
                found.add(new Machine.Disk(model, (long) number(f[1]),
                        spinning ? "Hard disk" : "SSD"));
            }
        }
        return List.copyOf(found);
    }

    private static List<String> run(String... command) {
        List<String> lines = new ArrayList<>();
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    lines.add(line);
                }
            }
            p.waitFor();
        } catch (Exception ignored) {
            // The tool is not installed; the caller copes with an empty list.
        }
        return lines;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static double number(String text) {
        StringBuilder digits = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (Character.isDigit(c) || (c == '.' && digits.indexOf(".") < 0)) {
                digits.append(c);
            } else if (!digits.isEmpty()) {
                break;
            }
        }
        try {
            return digits.isEmpty() ? 0 : Double.parseDouble(digits.toString());
        } catch (RuntimeException e) {
            return 0;
        }
    }
}

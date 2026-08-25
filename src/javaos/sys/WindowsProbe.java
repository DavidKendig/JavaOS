package javaos.sys;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Windows detail, by way of PowerShell.
 *
 * <p>Two shapes of query. The inventory runs once and exits. The per-core load
 * needs a fresh sample every couple of seconds, and starting a shell that often
 * would cost more than it measures, so one PowerShell sits in a loop writing a
 * block of counters and a sentinel line, and this class reads its output for as
 * long as the monitor is open.
 *
 * <p>Both scripts are handed over as {@code -EncodedCommand}, which sidesteps
 * every layer of quoting between here and the interpreter. Both are read-only
 * queries: CIM classes and one registry value that holds the true size of video
 * memory, which {@code Win32_VideoController} still caps at 4 GB.
 */
final class WindowsProbe implements Probe {

    private static final String SENTINEL = "--END--";

    private static final String SAMPLE_SCRIPT = """
            $ErrorActionPreference = 'SilentlyContinue'
            while ($true) {
              Get-CimInstance Win32_PerfFormattedData_PerfOS_Processor |
                ForEach-Object { 'CORE|' + $_.Name + '|' + $_.PercentProcessorTime }
              '--END--'
              Start-Sleep -Seconds 2
            }
            """;

    private static final String INVENTORY_SCRIPT = """
            $ErrorActionPreference = 'SilentlyContinue'
            Get-CimInstance Win32_Processor | ForEach-Object {
              'CPU|' + $_.Name + '|' + $_.NumberOfCores + '|' +
              $_.NumberOfLogicalProcessors + '|' + $_.MaxClockSpeed }
            Get-CimInstance Win32_VideoController | ForEach-Object {
              'GPU|' + $_.Name + '|' + $_.AdapterRAM + '|' + $_.DriverVersion }
            $class = 'HKLM:\\SYSTEM\\CurrentControlSet\\Control\\Class\\{4d36e968-e325-11ce-bfc1-08002be10318}\\*'
            Get-ItemProperty $class | Where-Object { $_.'HardwareInformation.qwMemorySize' } |
              ForEach-Object { 'VRAM|' + $_.DriverDesc + '|' + $_.'HardwareInformation.qwMemorySize' }
            Get-CimInstance Win32_PhysicalMemory | ForEach-Object {
              'RAM|' + $_.DeviceLocator + '|' + $_.Capacity + '|' + $_.Speed + '|' +
              $_.Manufacturer + '|' + $_.SMBIOSMemoryType }
            Get-CimInstance Win32_DiskDrive | ForEach-Object {
              'DISK|' + $_.Model + '|' + $_.Size + '|' + $_.MediaType }
            Get-CimInstance Win32_BaseBoard | ForEach-Object {
              'BOARD|' + $_.Manufacturer + '|' + $_.Product }
            Get-CimInstance Win32_ComputerSystem | ForEach-Object {
              'SYS|' + $_.Manufacturer + '|' + $_.Model }
            Get-CimInstance Win32_OperatingSystem | ForEach-Object {
              'OS|' + $_.Caption + '|' + $_.Version }
            '--END--'
            """;

    private volatile Process sampler;
    private volatile Thread samplerThread;
    private volatile Thread inventoryThread;
    private volatile boolean running;

    @Override public void start() {
        running = true;
        inventoryThread = daemon("javaos-inventory", this::readInventory);
        samplerThread = daemon("javaos-cores", this::readCores);
    }

    @Override public void stop() {
        running = false;
        Process p = sampler;
        if (p != null) {
            p.destroy();
        }
        for (Thread t : new Thread[] {samplerThread, inventoryThread}) {
            if (t != null) {
                t.interrupt();
            }
        }
        sampler = null;
    }

    private static Thread daemon(String name, Runnable body) {
        Thread t = new Thread(body, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** Wraps a script as -EncodedCommand, which PowerShell wants as base64 UTF-16LE. */
    private static ProcessBuilder powershell(String script) {
        String encoded = Base64.getEncoder()
                .encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        return new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)
                .redirectErrorStream(true);
    }

    // ---- per-core load --------------------------------------------------

    private void readCores() {
        try {
            Process p = powershell(SAMPLE_SCRIPT).start();
            sampler = p;
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                Map<String, Double> block = new LinkedHashMap<>();
                String line;
                while (running && (line = in.readLine()) != null) {
                    if (line.startsWith(SENTINEL)) {
                        publish(block);
                        block = new LinkedHashMap<>();
                    } else if (line.startsWith("CORE|")) {
                        String[] parts = line.split("\\|", -1);
                        if (parts.length >= 3) {
                            block.put(parts[1], parseDouble(parts[2]));
                        }
                    }
                }
            }
        } catch (Exception e) {
            if (running) {
                Machine.reportStatus("Per-core sampling unavailable: " + e.getMessage());
            }
        }
    }

    /** Turns one block of counters into core loads, dropping the "_Total" row. */
    private void publish(Map<String, Double> block) {
        if (block.isEmpty()) {
            return;
        }
        List<Double> loads = new ArrayList<>();
        for (Map.Entry<String, Double> e : block.entrySet()) {
            if (!"_Total".equals(e.getKey())) {
                loads.add(Math.max(0, Math.min(1, e.getValue() / 100.0)));
            }
        }
        double[] out = new double[loads.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = loads.get(i);
        }
        Machine.reportCores(out);
        Machine.reportStatus("Sampling " + out.length + " cores through PowerShell.");
    }

    // ---- inventory ------------------------------------------------------

    private void readInventory() {
        List<Machine.Gpu> gpus = new ArrayList<>();
        Map<String, Long> trueVram = new LinkedHashMap<>();
        List<Machine.MemoryStick> sticks = new ArrayList<>();
        List<Machine.Disk> disks = new ArrayList<>();
        String cpu = "Unknown processor";
        int cores = 0;
        int threads = 0;
        int mhz = 0;
        String board = "";
        String system = "";
        String os = Machine.platform();

        try {
            Process p = powershell(INVENTORY_SCRIPT).start();
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith(SENTINEL)) {
                        break;
                    }
                    String[] f = line.split("\\|", -1);
                    switch (f[0]) {
                        case "CPU" -> {
                            if (f.length >= 5) {
                                cpu = f[1].trim();
                                cores += (int) parseDouble(f[2]);
                                threads += (int) parseDouble(f[3]);
                                mhz = Math.max(mhz, (int) parseDouble(f[4]));
                            }
                        }
                        case "GPU" -> {
                            if (f.length >= 4) {
                                gpus.add(new Machine.Gpu(f[1].trim(),
                                        (long) parseDouble(f[2]), f[3].trim()));
                            }
                        }
                        case "VRAM" -> {
                            if (f.length >= 3) {
                                trueVram.put(f[1].trim(), (long) parseDouble(f[2]));
                            }
                        }
                        case "RAM" -> {
                            if (f.length >= 6) {
                                sticks.add(new Machine.MemoryStick(f[1].trim(),
                                        (long) parseDouble(f[2]), (int) parseDouble(f[3]),
                                        f[4].trim(), memoryType((int) parseDouble(f[5]))));
                            }
                        }
                        case "DISK" -> {
                            if (f.length >= 4) {
                                disks.add(new Machine.Disk(f[1].trim(),
                                        (long) parseDouble(f[2]),
                                        f[3].isBlank() ? "Disk" : f[3].trim()));
                            }
                        }
                        case "BOARD" -> board = f.length >= 3 ? (f[1] + " " + f[2]).trim() : board;
                        case "SYS" -> system = f.length >= 3 ? (f[1] + " " + f[2]).trim() : system;
                        case "OS" -> os = f.length >= 3 ? (f[1] + " " + f[2]).trim() : os;
                        default -> { }
                    }
                }
            }
            p.waitFor();
        } catch (Exception e) {
            Machine.reportStatus("Inventory unavailable: " + e.getMessage());
            return;
        }

        // The registry knows the real size of video memory; the CIM class caps at 4 GB.
        List<Machine.Gpu> merged = new ArrayList<>();
        for (Machine.Gpu gpu : gpus) {
            Long better = trueVram.get(gpu.name());
            merged.add(better != null && better > gpu.vram()
                    ? new Machine.Gpu(gpu.name(), better, gpu.driver()) : gpu);
        }

        Machine.reportHardware(new Machine.Hardware(cpu, cores, threads, mhz, board, system, os,
                List.copyOf(merged), List.copyOf(sticks), List.copyOf(disks)));
    }

    /** SMBIOS memory-type codes, for the handful still in circulation. */
    private static String memoryType(int code) {
        return switch (code) {
            case 20 -> "DDR";
            case 21 -> "DDR2";
            case 22 -> "DDR2 FB-DIMM";
            case 24 -> "DDR3";
            case 26 -> "DDR4";
            case 27 -> "LPDDR";
            case 28 -> "LPDDR2";
            case 29 -> "LPDDR3";
            case 30 -> "LPDDR4";
            case 34 -> "DDR5";
            case 35 -> "LPDDR5";
            default -> code == 0 ? "" : "Type " + code;
        };
    }

    private static double parseDouble(String text) {
        try {
            return Double.parseDouble(text.trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }
}

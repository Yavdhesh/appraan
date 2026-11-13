package dev.appraan.os;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Linux collector reading {@code /proc/stat}. Per-core busy% is computed from the
 * delta between successive reads; the first read reports the since-boot average.
 * Later milestones replace/augment this with eBPF collectors behind the same SPI.
 */
final class LinuxOsCollector implements OsCollector {

    private static final Path PROC_STAT = Path.of("/proc/stat");

    /** Previous [total, idle] jiffies per core. */
    private long[][] previous;

    @Override
    public String name() {
        return "linux-/proc";
    }

    @Override
    public double[] perCoreCpuBusyPercent() {
        List<long[]> current = readCpuLines();
        if (current.isEmpty()) {
            return new double[0];
        }
        double[] busy = new double[current.size()];
        for (int i = 0; i < current.size(); i++) {
            long total = current.get(i)[0];
            long idle = current.get(i)[1];
            if (previous != null && i < previous.length) {
                long dTotal = total - previous[i][0];
                long dIdle = idle - previous[i][1];
                busy[i] = dTotal > 0 ? 100.0 * (dTotal - dIdle) / dTotal : 0.0;
            } else {
                busy[i] = total > 0 ? 100.0 * (total - idle) / total : 0.0;
            }
        }
        previous = current.toArray(new long[0][]);
        return busy;
    }

    /**
     * Reads {@code /proc/<pid>/io}: syscr/syscw (read/write syscalls issued)
     * and read_bytes/write_bytes/cancelled_write_bytes (bytes the process
     * caused to go to the storage layer). Cumulative since process start.
     * Returns {@code null} when the file is unreadable (process gone, or
     * hidden by hidepid) rather than fabricating zeros.
     */
    @Override
    public ProcessIo processIo(long pid) {
        if (pid <= 0) {
            return null;
        }
        try {
            long syscr = -1, syscw = -1, readBytes = -1, writeBytes = -1, cancelled = -1;
            for (String line : Files.readAllLines(Path.of("/proc/" + pid + "/io"))) {
                int colon = line.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                long value;
                try {
                    value = Long.parseLong(line.substring(colon + 1).trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                switch (line.substring(0, colon).trim()) {
                    case "syscr" -> syscr = value;
                    case "syscw" -> syscw = value;
                    case "read_bytes" -> readBytes = value;
                    case "write_bytes" -> writeBytes = value;
                    case "cancelled_write_bytes" -> cancelled = value;
                    default -> {
                        // rchar/wchar not surfaced in the model today.
                    }
                }
            }
            if (syscr < 0 || syscw < 0 || readBytes < 0 || writeBytes < 0) {
                return null;
            }
            return new ProcessIo(syscr, syscw, readBytes, writeBytes, Math.max(cancelled, 0));
        } catch (IOException e) {
            return null;
        }
    }

    /** Parses the per-core {@code cpuN} lines of /proc/stat into [total, idleAll] pairs. */
    private static List<long[]> readCpuLines() {
        List<long[]> cores = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(PROC_STAT)) {
                if (!line.matches("cpu\\d+ .*")) {
                    continue;
                }
                String[] p = line.trim().split("\\s+");
                // p[0]=cpuN, then user nice system idle iowait irq softirq steal guest guest_nice
                long total = 0;
                for (int i = 1; i < p.length; i++) {
                    total += Long.parseLong(p[i]);
                }
                long idleAll = Long.parseLong(p[4]) + (p.length > 5 ? Long.parseLong(p[5]) : 0);
                cores.add(new long[]{total, idleAll});
            }
        } catch (IOException | NumberFormatException e) {
            return List.of();
        }
        return cores;
    }
}

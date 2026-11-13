package dev.appraan.ui;

import dev.appraan.discover.TargetInfo;
import dev.appraan.metrics.G1Stats;
import dev.appraan.metrics.Metrics;
import dev.appraan.os.OsCollector;
import dev.appraan.os.ProcessIo;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One immutable, self-contained view of everything the UI renders, captured
 * from the live metric model after each JFR poll. HTTP handlers and the
 * proof/snapshot renderers all consume this — never the mutable
 * {@link Metrics} — so a page can never observe a half-updated state.
 */
public record Snapshot(
        String pid,
        String versionLine,
        String javaVersion,
        String gcCollector,
        Map<String, String> flags,
        String commandLine,
        long youngGcCount,
        long oldGcCount,
        long totalPauseMs,
        long maxPauseMs,
        String sinceLastYoungGc,
        String sinceLastOldGc,
        long allocatedBytes,
        double allocRateBytesPerSec,
        long heapUsedBytes,
        String allocationSource,
        long executionSamples,
        long stackSamples,
        List<Map.Entry<String, Long>> topLeafMethods,
        List<Map.Entry<String, Long>> collapsedStacks,
        String osCollectorName,
        double[] coreBusyPercent,
        double elapsedSeconds,
        Instant capturedAt,
        boolean g1Available,
        long promotionBytes,
        long promotionGcCount,
        long g1EdenUsedBytes,
        long g1SurvivorUsedBytes,
        long g1OldUsedBytes,
        long g1RegionCount,
        long humongousRegionCount,
        long humongousUsedBytes,
        Map<String, long[]> regionTypeCensus,
        Instant regionCensusAt,
        /** Linux /proc per-process I/O for the target (cumulative), or null. */
        ProcessIo procIo) {

    /** How many leaf methods the dashboard / flame page tables show. */
    public static final int TOP_LEAF_LIMIT = 15;

    public static Snapshot capture(TargetInfo info, Metrics metrics, OsCollector os,
                                   Instant eventClockNow, double allocRateBytesPerSec,
                                   double elapsedSeconds) {
        return capture(info, metrics, os, eventClockNow, allocRateBytesPerSec, elapsedSeconds,
                os.perCoreCpuBusyPercent());
    }

    /**
     * As {@link #capture(TargetInfo, Metrics, OsCollector, Instant, double, double)}
     * but with a per-core reading taken by the caller. Per-core CPU is a
     * delta between successive collector reads, so a round of observation
     * must read it exactly once and share the result between the console
     * block and the UI snapshot — otherwise the second read measures only
     * the milliseconds since the first.
     */
    public static Snapshot capture(TargetInfo info, Metrics metrics, OsCollector os,
                                   Instant eventClockNow, double allocRateBytesPerSec,
                                   double elapsedSeconds, double[] coreBusyPercent) {
        G1Stats g1 = metrics.g1();
        Map<String, long[]> census = new java.util.TreeMap<>();
        g1.regionCensus().forEach((type, agg) -> census.put(type, agg.clone()));
        return new Snapshot(
                info.pid(),
                info.versionLine(),
                info.javaVersion(),
                info.gcCollector(),
                Map.copyOf(info.flagsOfInterest()),
                info.commandLine(),
                metrics.youngGcCount(),
                metrics.oldGcCount(),
                metrics.totalPause().toMillis(),
                metrics.maxPause().toMillis(),
                since(metrics.lastYoungGcEnd(), eventClockNow),
                since(metrics.lastOldGcEnd(), eventClockNow),
                metrics.allocatedBytes(),
                allocRateBytesPerSec,
                metrics.lastHeapUsed(),
                metrics.allocationSource(),
                metrics.executionSamples(),
                metrics.stackSamples(),
                List.copyOf(metrics.topLeafMethods(TOP_LEAF_LIMIT)),
                List.copyOf(metrics.collapsedStacks()),
                os.name(),
                coreBusyPercent.clone(),
                elapsedSeconds,
                Instant.now(),
                g1.available(),
                g1.promotionBytes(),
                g1.promotionGcCount(),
                g1.edenUsedBytes(),
                g1.survivorUsedBytes(),
                g1.oldUsedBytes(),
                g1.regionCount(),
                g1.humongousRegionCount(),
                g1.humongousUsedBytes(),
                census,
                g1.censusUpdatedAt(),
                readProcessIo(info, os));
    }

    /** Per-process I/O via the OS collector; null when unavailable/unparseable. */
    private static ProcessIo readProcessIo(TargetInfo info, OsCollector os) {
        try {
            return os.processIo(Long.parseLong(info.pid()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String since(Instant last, Instant now) {
        if (last == null || now == null) {
            return "never";
        }
        Duration d = Duration.between(last, now);
        if (d.isNegative()) {
            d = Duration.ZERO;
        }
        return Metrics.formatDuration(d) + " ago";
    }

    /** Share (0–100) of all execution samples whose leaf is the given method. */
    public double leafPercent(long leafCount) {
        return executionSamples == 0 ? 0.0 : leafCount * 100.0 / executionSamples;
    }
}

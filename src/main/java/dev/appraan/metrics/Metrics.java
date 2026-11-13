package dev.appraan.metrics;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Running aggregates fed by JFR events parsed from successive snapshot dumps.
 *
 * <p>Allocation accounting, in preference order:
 * <ol>
 *   <li>{@code jdk.ObjectAllocationSample} — sum of the sampled {@code weight} field.</li>
 *   <li>TLAB events ({@code jdk.ObjectAllocationInNewTLAB} / {@code ...OutsideTLAB}) —
 *       used only while no sample events have ever been seen, to avoid double counting.</li>
 *   <li>Fallback estimate from {@code jdk.GCHeapSummary} Before/After-GC heap-used deltas —
 *       used only when neither event kind appears (reported as "estimated").</li>
 * </ol>
 */
public final class Metrics {

    // GC
    private long youngGcCount;
    private long oldGcCount;
    private final Set<String> seenGcEvents = new HashSet<>();
    private Duration totalPause = Duration.ZERO;
    private Duration maxPause = Duration.ZERO;
    private Instant lastYoungGcEnd;
    private Instant lastOldGcEnd;

    // Allocation
    private boolean allocFromSamples;
    /** While true, allocation events are observed (source detection) but their bytes are
     *  discarded: the JFR allocation sampler's throttle adapts during the first moments
     *  after recording start and emits hugely inflated weights in that window. */
    private boolean allocationWarmup;
    private long sampleAllocBytes;
    private long sampleAllocEvents;
    private long tlabAllocBytes;
    private long heapSummaryEstBytes;
    private Long lastAfterGcHeapUsed;
    private long lastHeapUsed = -1;

    // CPU
    private long executionSamples;
    private final Map<String, Long> leafMethodCounts = new HashMap<>();
    private final Map<String, Long> collapsedStacks = new HashMap<>();

    // G1 (promotion estimate, region census, humongous) — see G1Stats
    private final G1Stats g1 = new G1Stats();

    public void onEvent(RecordedEvent e) {
        String type = e.getEventType().getName();
        switch (type) {
            case "jdk.YoungGarbageCollection" -> {
                if (seenGcEvents.add(type + "@" + e.getStartTime())) {
                    youngGcCount++;
                    lastYoungGcEnd = e.getEndTime();
                }
                g1.onYoungGc(safeGcId(e));
            }
            case "jdk.OldGarbageCollection" -> {
                if (seenGcEvents.add(type + "@" + e.getStartTime())) {
                    oldGcCount++;
                    lastOldGcEnd = e.getEndTime();
                }
                g1.onOldGc(safeGcId(e));
            }
            case "jdk.GarbageCollection" -> {
                // Deduped by gcId: these events bypass the dump watermark filter
                // (they are emitted when the GC completes) so they can be re-fed.
                if (seenGcEvents.add("GC#" + e.getLong("gcId"))) {
                    Duration sum = safeDuration(e, "sumOfPauses");
                    Duration longest = safeDuration(e, "longestPause");
                    totalPause = totalPause.plus(sum);
                    if (longest.compareTo(maxPause) > 0) {
                        maxPause = longest;
                    }
                }
            }
            case "jdk.ObjectAllocationSample" -> {
                allocFromSamples = true;
                sampleAllocEvents++;
                if (!allocationWarmup) {
                    sampleAllocBytes += e.getLong("weight");
                }
            }
            case "jdk.ObjectAllocationInNewTLAB" -> {
                if (!allocFromSamples && !allocationWarmup) {
                    tlabAllocBytes += e.getLong("tlabSize");
                }
            }
            case "jdk.ObjectAllocationOutsideTLAB" -> {
                if (!allocFromSamples && !allocationWarmup) {
                    tlabAllocBytes += e.getLong("allocationSize");
                }
            }
            case "jdk.GCHeapSummary" -> onHeapSummary(e);
            case "jdk.G1HeapSummary" -> onG1HeapSummary(e);
            case "jdk.G1HeapRegionInformation" -> onG1RegionInfo(e);
            case "jdk.ExecutionSample" -> onExecutionSample(e);
            default -> {
                // All other event types are ignored in Milestone 1.
            }
        }
    }

    private void onHeapSummary(RecordedEvent e) {
        long used = e.getLong("heapUsed");
        lastHeapUsed = used;
        String when = e.getString("when");
        if ("Before GC".equals(when)) {
            if (lastAfterGcHeapUsed != null && used > lastAfterGcHeapUsed) {
                heapSummaryEstBytes += used - lastAfterGcHeapUsed;
            }
        } else if ("After GC".equals(when)) {
            lastAfterGcHeapUsed = used;
        }
    }

    private void onG1HeapSummary(RecordedEvent e) {
        try {
            g1.onHeapSummary(e.getLong("gcId"), e.getString("when"),
                    e.getLong("edenUsedSize"), e.getLong("survivorUsedSize"),
                    e.getLong("oldGenUsedSize"), e.getLong("numberOfRegions"));
        } catch (Exception ignored) {
            // A G1 summary without the expected fields must never break the poll.
        }
    }

    private void onG1RegionInfo(RecordedEvent e) {
        try {
            g1.onRegionInfo(e.getStartTime(), (int) e.getLong("index"),
                    e.getString("type"), e.getLong("used"));
        } catch (Exception ignored) {
            // As above: region detail is best-effort.
        }
    }

    private static long safeGcId(RecordedEvent e) {
        try {
            return e.getLong("gcId");
        } catch (Exception ex) {
            return -1L;
        }
    }

    private void onExecutionSample(RecordedEvent e) {
        executionSamples++;
        RecordedStackTrace trace = e.getStackTrace();
        if (trace == null || trace.getFrames().isEmpty()) {
            return;
        }
        List<RecordedFrame> frames = trace.getFrames();
        RecordedFrame leaf = frames.get(0);
        if (leaf.getMethod() != null && leaf.getMethod().getType() != null) {
            leafMethodCounts.merge(Stacks.frameName(leaf), 1L, Long::sum);
        }
        List<String> names = new ArrayList<>(frames.size());
        for (RecordedFrame frame : frames) {
            names.add(Stacks.frameName(frame));
        }
        String collapsed = Stacks.collapseLeafToRoot(names);
        if (collapsed != null) {
            collapsedStacks.merge(collapsed, 1L, Long::sum);
        }
    }

    private static Duration safeDuration(RecordedEvent e, String field) {
        try {
            Duration d = e.getDuration(field);
            return d == null ? Duration.ZERO : d;
        } catch (Exception ex) {
            return Duration.ZERO;
        }
    }

    public void setAllocationWarmup(boolean warmup) {
        this.allocationWarmup = warmup;
    }

    /** Total allocated bytes according to the best available source. */
    public long allocatedBytes() {
        if (allocFromSamples) {
            return sampleAllocBytes;
        }
        if (tlabAllocBytes > 0) {
            return tlabAllocBytes;
        }
        return heapSummaryEstBytes;
    }

    /** Which source {@link #allocatedBytes()} is using. */
    public String allocationSource() {
        if (allocFromSamples) {
            return "jdk.ObjectAllocationSample weight (" + sampleAllocEvents + " sampled events)";
        }
        if (tlabAllocBytes > 0) {
            return "TLAB events (no ObjectAllocationSample events in this recording)";
        }
        return "estimated from jdk.GCHeapSummary deltas (no allocation events in this recording)";
    }

    public List<Map.Entry<String, Long>> topLeafMethods(int n) {
        List<Map.Entry<String, Long>> entries = new ArrayList<>(leafMethodCounts.entrySet());
        entries.sort(Comparator.comparingLong((Map.Entry<String, Long> en) -> en.getValue()).reversed());
        return entries.subList(0, Math.min(n, entries.size()));
    }

    public long youngGcCount() {
        return youngGcCount;
    }

    public long oldGcCount() {
        return oldGcCount;
    }

    public Duration totalPause() {
        return totalPause;
    }

    public Duration maxPause() {
        return maxPause;
    }

    public long executionSamples() {
        return executionSamples;
    }

    /**
     * Collapsed stacks (root-first, {@code ';'}-joined) with sample counts,
     * sorted by count descending. Feeds the flame graph and the JSON API;
     * derived from the same {@code jdk.ExecutionSample} events as
     * {@link #topLeafMethods(int)}, so the two views are consistent.
     */
    public List<Map.Entry<String, Long>> collapsedStacks() {
        List<Map.Entry<String, Long>> entries = new ArrayList<>(collapsedStacks.entrySet());
        entries.sort(Comparator.comparingLong((Map.Entry<String, Long> en) -> en.getValue()).reversed()
                .thenComparing(Map.Entry::getKey));
        return entries;
    }

    /** Samples that carried a usable stack trace (subset of {@link #executionSamples()}). */
    public long stackSamples() {
        long total = 0;
        for (long v : collapsedStacks.values()) {
            total += v;
        }
        return total;
    }

    /** End time of the most recent young GC, or {@code null} if none yet. */
    public Instant lastYoungGcEnd() {
        return lastYoungGcEnd;
    }

    /** End time of the most recent old GC, or {@code null} if none yet. */
    public Instant lastOldGcEnd() {
        return lastOldGcEnd;
    }

    public long lastHeapUsed() {
        return lastHeapUsed;
    }

    /** G1 aggregates (promotion estimate, region census). Never null. */
    public G1Stats g1() {
        return g1;
    }

    /** Time between {@code now} (use the newest event time seen) and the last GC of each kind. */
    public String sinceLastGc(Instant now) {
        return "young: " + since(lastYoungGcEnd, now) + ", old: " + since(lastOldGcEnd, now);
    }

    private static String since(Instant last, Instant now) {
        if (last == null || now == null) {
            return "never";
        }
        Duration d = Duration.between(last, now);
        if (d.isNegative()) {
            d = Duration.ZERO;
        }
        return formatDuration(d) + " ago";
    }

    public static String formatDuration(Duration d) {
        long ms = d.toMillis();
        if (ms < 1_000) {
            return ms + "ms";
        }
        if (ms < 60_000) {
            return String.format("%.1fs", ms / 1000.0);
        }
        return (ms / 60_000) + "m" + String.format("%02d", (ms % 60_000) / 1000) + "s";
    }

    public static String formatBytes(long bytes) {
        if (bytes < 0) {
            return "n/a";
        }
        double v = bytes;
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        int u = 0;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return u == 0 ? bytes + " B" : String.format("%.1f %s", v, units[u]);
    }
}

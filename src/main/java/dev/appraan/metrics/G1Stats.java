package dev.appraan.metrics;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * G1-specific aggregates fed by JFR events. Pure logic over plain values, so
 * it is unit-testable without a recording file; {@link Metrics} adapts
 * {@code RecordedEvent}s into these calls.
 *
 * <p>Sources (all present in the {@code profile} JFR settings, except region
 * info which {@code LiveJfr} enables explicitly):
 * <ul>
 *   <li>{@code jdk.G1HeapSummary} — eden / survivor / old used and region
 *       count, before and after each GC (keyed by {@code gcId}).</li>
 *   <li>{@code jdk.YoungGarbageCollection} / {@code jdk.OldGarbageCollection}
 *       — classify a {@code gcId} so old-gen growth during <em>old</em> GCs is
 *       never counted as promotion.</li>
 *   <li>{@code jdk.G1HeapRegionInformation} — one event per region, emitted
 *       in batches at some (not every) GCs. The census keeps the latest
 *       report per region index (humongous regions appear as
 *       {@code Starts Humongous} / {@code Continues Humongous}), filtered to
 *       indices below the heap's current region count.</li>
 * </ul>
 *
 * <p>Promotion estimate: for each young GC with both a before- and an
 * after-summary, {@code max(0, oldAfter - oldBefore)}. Objects that survive
 * evacuation into the old generation grow the old generation during the
 * pause; humongous allocations go to the old generation <em>between</em>
 * GCs, so they do not inflate this number. It is an estimate from heap
 * summaries, not a per-object count, and is labelled as such everywhere it
 * is shown. G1 only — for other collectors no {@code G1HeapSummary} events
 * arrive and every getter reports "unavailable" rather than a fabricated 0.
 *
 * <p>Idempotency: snapshot dumps re-read the recording from its start, so
 * the same events are fed repeatedly. All state is keyed (per-gcId maps,
 * census keyed by region index), making re-feeds overwrite identical values
 * instead of double counting. The census can briefly blend two consecutive
 * batch reports while a batch is being read; it settles to the newest batch
 * within one batch because every batch re-reports every region index.
 */
public final class G1Stats {

    /** One region in the current census. */
    public record Region(String type, long usedBytes) {
    }

    private final Set<Long> youngGcIds = new HashSet<>();
    private final Set<Long> oldGcIds = new HashSet<>();
    private final Map<Long, Long> oldBeforeByGc = new HashMap<>();
    private final Map<Long, Long> oldAfterByGc = new HashMap<>();

    private boolean summarySeen;
    private long latestEdenUsed = -1;
    private long latestSurvivorUsed = -1;
    private long latestOldUsed = -1;
    private long latestRegions = -1;

    private Instant censusUpdatedAt;
    private final Map<Integer, Region> censusByIndex = new HashMap<>();

    public void onYoungGc(long gcId) {
        youngGcIds.add(gcId);
    }

    public void onOldGc(long gcId) {
        oldGcIds.add(gcId);
    }

    public void onHeapSummary(long gcId, String when, long edenUsed, long survivorUsed,
                              long oldUsed, long regions) {
        summarySeen = true;
        if ("Before GC".equals(when)) {
            oldBeforeByGc.put(gcId, oldUsed);
        } else if ("After GC".equals(when)) {
            oldAfterByGc.put(gcId, oldUsed);
            // The after-state is the heap as it stands now.
            latestEdenUsed = edenUsed;
            latestSurvivorUsed = survivorUsed;
            latestOldUsed = oldUsed;
            latestRegions = regions;
        }
    }

    public void onRegionInfo(Instant startTime, int index, String type, long usedBytes) {
        // Latest report per region index wins. Batches re-report every index,
        // so after one full batch the map is exactly that batch; re-fed
        // events from an old dump position just overwrite identical values.
        censusByIndex.put(index, new Region(type, usedBytes));
        if (censusUpdatedAt == null || startTime.isAfter(censusUpdatedAt)) {
            censusUpdatedAt = startTime;
        }
    }

    /** True once any {@code G1HeapSummary} has been seen (i.e. target runs G1). */
    public boolean available() {
        return summarySeen;
    }

    /**
     * Estimated bytes promoted to the old generation across all young GCs
     * seen so far. Computed from the per-gcId maps, so re-fed events and
     * summary/GC event ordering across polls cannot double count.
     */
    public long promotionBytes() {
        long total = 0;
        for (long gcId : youngGcIds) {
            Long before = oldBeforeByGc.get(gcId);
            Long after = oldAfterByGc.get(gcId);
            if (before != null && after != null && after > before) {
                total += after - before;
            }
        }
        return total;
    }

    /** Young GCs for which both summaries have arrived (promotion sample size). */
    public long promotionGcCount() {
        long n = 0;
        for (long gcId : youngGcIds) {
            if (oldBeforeByGc.containsKey(gcId) && oldAfterByGc.containsKey(gcId)) {
                n++;
            }
        }
        return n;
    }

    public long edenUsedBytes() {
        return latestEdenUsed;
    }

    public long survivorUsedBytes() {
        return latestSurvivorUsed;
    }

    public long oldUsedBytes() {
        return latestOldUsed;
    }

    public long regionCount() {
        return latestRegions;
    }

    /** Region type → {region count, used bytes} from the latest per-index reports; empty if none yet. */
    public Map<String, long[]> regionCensus() {
        Map<String, long[]> byType = new TreeMap<>();
        censusByIndex.forEach((index, r) -> {
            // The heap can shrink; ignore indices beyond its current size.
            if (latestRegions > 0 && index >= latestRegions) {
                return;
            }
            long[] agg = byType.computeIfAbsent(r.type(), k -> new long[2]);
            agg[0]++;
            agg[1] += r.usedBytes();
        });
        return byType;
    }

    public long humongousRegionCount() {
        return humongousAgg()[0];
    }

    public long humongousUsedBytes() {
        return humongousAgg()[1];
    }

    /** G1 names the two halves of a humongous allocation "Starts Humongous" / "Continues Humongous". */
    private long[] humongousAgg() {
        long[] total = new long[2];
        regionCensus().forEach((type, agg) -> {
            if (type.equals("Starts Humongous") || type.equals("Continues Humongous")) {
                total[0] += agg[0];
                total[1] += agg[1];
            }
        });
        return total;
    }

    /** Whether any region report has arrived (region-info events enabled + a batch seen). */
    public boolean censusAvailable() {
        return !censusByIndex.isEmpty();
    }

    /** Start time of the newest region report seen, or {@code null} if none. */
    public Instant censusUpdatedAt() {
        return censusUpdatedAt;
    }
}

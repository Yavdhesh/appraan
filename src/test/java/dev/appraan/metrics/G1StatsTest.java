package dev.appraan.metrics;

import java.time.Instant;

/**
 * Manual test for {@link G1Stats}: promotion must count only old-gen growth
 * during young GCs, re-fed events must not double count, and the region
 * census must keep only the newest timestamp group. Run via
 * {@code ./run-tests.sh}; exits non-zero (AssertionError) on first failure.
 */
public final class G1StatsTest {

    private static int checks;

    public static void main(String[] args) {
        promotionCountsYoungOnly();
        promotionIgnoresOldGcGrowth();
        refeedIsIdempotent();
        summaryBeforeGcEventArrives();
        censusLatestPerIndex();
        humongousFromCensus();
        unavailableWhenNoG1Events();
        System.out.println("G1StatsTest: all " + checks + " checks passed");
    }

    /** A young GC that grows old by 1.5 MB, then one that grows it by 0.5 MB. */
    private static void promotionCountsYoungOnly() {
        G1Stats g = new G1Stats();
        g.onYoungGc(1);
        g.onHeapSummary(1, "Before GC", 20_000_000, 2_000_000, 4_500_000, 126);
        g.onHeapSummary(1, "After GC", 0, 1_300_000, 6_000_000, 126);
        check(g.promotionBytes() == 1_500_000, "young GC promotion = after - before");
        check(g.promotionGcCount() == 1, "one young GC with both summaries");
        check(g.oldUsedBytes() == 6_000_000, "latest old used from After GC");
        check(g.edenUsedBytes() == 0, "latest eden used from After GC");
        check(g.regionCount() == 126, "latest region count from After GC");

        g.onYoungGc(2);
        g.onHeapSummary(2, "Before GC", 70_000_000, 1_300_000, 33_000_000, 126);
        g.onHeapSummary(2, "After GC", 0, 5_300_000, 33_500_000, 126);
        check(g.promotionBytes() == 2_000_000, "promotion accumulates across young GCs");
        check(g.promotionGcCount() == 2, "two young GCs with both summaries");
    }

    /** Old-gen shrinking (or growing) during an old GC is never promotion. */
    private static void promotionIgnoresOldGcGrowth() {
        G1Stats g = new G1Stats();
        g.onOldGc(4);
        g.onHeapSummary(4, "Before GC", 0, 8_600_000, 56_000_000, 130);
        g.onHeapSummary(4, "After GC", 0, 8_600_000, 59_000_000, 130);
        check(g.promotionBytes() == 0, "old GC growth is not promotion");
        check(g.promotionGcCount() == 0, "old GC not in promotion sample");
        check(g.oldUsedBytes() == 59_000_000, "latest old used still tracks old GCs");
    }

    /** Snapshot dumps re-feed every event; totals must not move on a re-feed. */
    private static void refeedIsIdempotent() {
        G1Stats g = new G1Stats();
        g.onYoungGc(1);
        g.onHeapSummary(1, "Before GC", 20_000_000, 2_000_000, 4_500_000, 126);
        g.onHeapSummary(1, "After GC", 0, 1_300_000, 6_000_000, 126);
        long once = g.promotionBytes();
        g.onYoungGc(1);
        g.onHeapSummary(1, "Before GC", 20_000_000, 2_000_000, 4_500_000, 126);
        g.onHeapSummary(1, "After GC", 0, 1_300_000, 6_000_000, 126);
        check(g.promotionBytes() == once, "re-fed summaries do not double count promotion");
        check(g.promotionGcCount() == 1, "re-fed GC still counts once");
    }

    /** Summaries can be read in a poll before the YoungGC event for that gcId. */
    private static void summaryBeforeGcEventArrives() {
        G1Stats g = new G1Stats();
        g.onHeapSummary(7, "Before GC", 50_000_000, 4_000_000, 40_000_000, 114);
        g.onHeapSummary(7, "After GC", 0, 7_000_000, 42_000_000, 114);
        check(g.promotionBytes() == 0, "no promotion counted before the GC is classified");
        g.onYoungGc(7);
        check(g.promotionBytes() == 2_000_000, "promotion counted once classification arrives");
    }

    /** Latest report per index wins; indices beyond the heap size are ignored. */
    private static void censusLatestPerIndex() {
        G1Stats g = new G1Stats();
        Instant t1 = Instant.parse("2026-11-13T18:00:00Z");
        Instant t2 = Instant.parse("2026-11-13T18:00:05Z");
        g.onRegionInfo(t1, 0, "Old", 250_000);
        g.onRegionInfo(t1, 1, "Free", 0);
        g.onRegionInfo(t1, 2, "Starts Humongous", 2_000_000);
        g.onRegionInfo(t2, 0, "Old", 260_000);
        g.onRegionInfo(t2, 1, "Eden", 900_000);
        var census = g.regionCensus();
        check(census.get("Old")[0] == 1 && census.get("Old")[1] == 260_000,
                "newer report for index 0 wins");
        check(census.get("Eden")[0] == 1 && census.get("Eden")[1] == 900_000,
                "newer report for index 1 wins");
        check(g.censusUpdatedAt().equals(t2), "census timestamp tracks the newest report");
        check(g.censusAvailable(), "census available after regions seen");

        // Heap shrinks to 2 regions: the stale index-2 report must drop out.
        g.onHeapSummary(9, "After GC", 0, 0, 1_000_000, 2);
        census = g.regionCensus();
        check(!census.containsKey("Starts Humongous"),
                "index beyond current region count is ignored");
        check(census.size() == 2, "census filtered to current heap size, got " + census.keySet());
    }

    private static void humongousFromCensus() {
        G1Stats g = new G1Stats();
        Instant t = Instant.parse("2026-11-13T18:00:00Z");
        g.onRegionInfo(t, 5, "Starts Humongous", 2_000_000);
        g.onRegionInfo(t, 6, "Continues Humongous", 2_000_000);
        g.onRegionInfo(t, 7, "Old", 1_000_000);
        check(g.humongousRegionCount() == 2, "Starts + Continues Humongous regions counted");
        check(g.humongousUsedBytes() == 4_000_000, "humongous used bytes summed");
    }

    private static void unavailableWhenNoG1Events() {
        G1Stats g = new G1Stats();
        check(!g.available(), "no G1 summaries -> not available (non-G1 target)");
        check(!g.censusAvailable(), "no region info -> census not available");
        check(g.promotionBytes() == 0, "no promotion without G1 data");
        check(g.edenUsedBytes() == -1, "eden used unknown before any After GC");
    }

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) {
            throw new AssertionError("FAILED: " + what);
        }
    }
}

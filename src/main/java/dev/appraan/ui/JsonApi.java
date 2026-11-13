package dev.appraan.ui;

import dev.appraan.kernel.KernelData;
import dev.appraan.kernel.KernelFeed;
import dev.appraan.os.ProcessIo;

import java.util.Map;

/**
 * The JSON API as pure functions {@code Snapshot -> String}. The HTTP layer
 * only routes and writes bytes; everything testable lives here.
 */
public final class JsonApi {

    private JsonApi() {
    }

    /** {@code GET /api/summary} — target discovery + headline numbers. */
    public static String summary(Snapshot s) {
        Json.Obj flags = Json.obj();
        for (Map.Entry<String, String> e : s.flags().entrySet()) {
            flags.put(e.getKey(), e.getValue());
        }
        return Json.obj()
                .put("pid", Long.parseLong(s.pid()))
                .put("version", s.versionLine())
                .put("javaVersion", s.javaVersion())
                .put("gcCollector", s.gcCollector())
                .putRaw("flags", flags.toString())
                .put("commandLine", s.commandLine())
                .put("elapsedSeconds", round1(s.elapsedSeconds()))
                .put("capturedAt", String.valueOf(s.capturedAt()))
                .putRaw("allocation", Json.obj()
                        .put("rateBytesPerSec", Math.round(s.allocRateBytesPerSec()))
                        .put("totalBytes", s.allocatedBytes())
                        .put("heapUsedBytes", s.heapUsedBytes())
                        .put("source", s.allocationSource())
                        .toString())
                .toString();
    }

    /** {@code GET /api/gc} — GC counts, pauses, recency, G1 promotion + regions. */
    public static String gc(Snapshot s) {
        Json.Obj census = Json.obj();
        s.regionTypeCensus().forEach((type, agg) -> census.putRaw(type, Json.obj()
                .put("regions", agg[0])
                .put("usedBytes", agg[1])
                .toString()));
        return Json.obj()
                .putRaw("young", Json.obj()
                        .put("count", s.youngGcCount())
                        .put("sinceLast", s.sinceLastYoungGc())
                        .toString())
                .putRaw("old", Json.obj()
                        .put("count", s.oldGcCount())
                        .put("sinceLast", s.sinceLastOldGc())
                        .toString())
                .putRaw("pauses", Json.obj()
                        .put("totalMs", s.totalPauseMs())
                        .put("maxMs", s.maxPauseMs())
                        .toString())
                .putRaw("promotion", Json.obj()
                        .put("bytes", s.promotionBytes())
                        .put("gcCount", s.promotionGcCount())
                        .put("basis", "G1HeapSummary old-gen growth during young GCs (estimate)")
                        .toString())
                .putRaw("g1", Json.obj()
                        .put("available", s.g1Available())
                        .put("edenUsedBytes", s.g1EdenUsedBytes())
                        .put("survivorUsedBytes", s.g1SurvivorUsedBytes())
                        .put("oldUsedBytes", s.g1OldUsedBytes())
                        .put("regions", s.g1RegionCount())
                        .putRaw("humongous", Json.obj()
                                .put("regions", s.humongousRegionCount())
                                .put("usedBytes", s.humongousUsedBytes())
                                .toString())
                        .putRaw("regionTypes", census.toString())
                        .put("regionCensusAt", s.regionCensusAt() == null
                                ? null : String.valueOf(s.regionCensusAt()))
                        .toString())
                .toString();
    }

    /** {@code GET /api/cpu} — execution-sample totals + top leaf methods. */
    public static String cpu(Snapshot s) {
        Json.Arr top = Json.arr();
        for (Map.Entry<String, Long> e : s.topLeafMethods()) {
            top.addRaw(Json.obj()
                    .put("method", e.getKey())
                    .put("samples", e.getValue())
                    .put("percent", round1(s.leafPercent(e.getValue())))
                    .toString());
        }
        return Json.obj()
                .put("executionSamples", s.executionSamples())
                .put("stackSamples", s.stackSamples())
                .putRaw("topLeaf", top.toString())
                .toString();
    }

    /** {@code GET /api/cores} — per-core busy percentages from the OS collector. */
    public static String cores(Snapshot s) {
        Json.Arr cores = Json.arr();
        double[] busy = s.coreBusyPercent();
        for (int i = 0; i < busy.length; i++) {
            cores.addRaw(Json.obj()
                    .put("core", i)
                    .put("busyPercent", round1(busy[i]))
                    .toString());
        }
        return Json.obj()
                .put("collector", s.osCollectorName())
                .putRaw("cores", cores.toString())
                .toString();
    }

    /** {@code GET /api/stacks} — collapsed stacks (root-first) with counts. */
    public static String stacks(Snapshot s) {
        Json.Arr stacks = Json.arr();
        for (Map.Entry<String, Long> e : s.collapsedStacks()) {
            stacks.addRaw(Json.obj()
                    .put("stack", e.getKey())
                    .put("samples", e.getValue())
                    .toString());
        }
        return Json.obj()
                .put("totalSamples", s.stackSamples())
                .putRaw("stacks", stacks.toString())
                .toString();
    }

    /**
     * {@code GET /api/kernel} — the freshest kernel snapshot (re-serialised
     * from the parsed model, so what the panel shows is exactly what
     * Appraan parsed), its source/age/staleness, the server-composed hint
     * for empty/stale states, and the Linux /proc process-I/O counters
     * when the OS collector provides them.
     */
    public static String kernel(KernelFeed.View view, ProcessIo procIo) {
        KernelFeed.Hint hint = view.hint();
        Json.Obj out = Json.obj()
                .put("available", view.data() != null)
                .put("source", view.source())
                .put("ageSeconds", view.ageSeconds())
                .put("stale", view.stale())
                .putRaw("hint", Json.obj()
                        .put("state", hint.state())
                        .put("message", hint.message())
                        .put("command", hint.command())
                        .toString());
        if (view.data() != null) {
            out.putRaw("kernel", kernelDocument(view.data()));
        }
        if (procIo != null) {
            out.putRaw("procIo", Json.obj()
                    .put("syscr", procIo.syscr())
                    .put("syscw", procIo.syscw())
                    .put("readBytes", procIo.readBytes())
                    .put("writeBytes", procIo.writeBytes())
                    .put("cancelledWriteBytes", procIo.cancelledWriteBytes())
                    .put("basis", "Linux /proc/<pid>/io — cumulative since process start")
                    .toString());
        } else {
            out.putRaw("procIo", "null");
        }
        return out.toString();
    }

    /** Re-serialises a parsed kernel snapshot in the agent's document shape. */
    private static String kernelDocument(KernelData d) {
        Json.Arr categories = Json.arr();
        for (KernelData.Category c : d.categories()) {
            Json.Obj kinds = Json.obj();
            c.kinds().forEach(kinds::put);
            Json.Obj cat = Json.obj()
                    .put("name", c.name())
                    .put("count", c.count())
                    .put("latOps", c.latOps())
                    .putRaw("avgUs", c.avgUs() == null ? "null" : Double.toString(c.avgUs()))
                    .putRaw("maxUs", c.maxUs() == null ? "null" : Double.toString(c.maxUs()))
                    .putRaw("kinds", kinds.toString());
            if (!c.hist().isEmpty()) {
                Json.Obj hist = Json.obj();
                c.hist().forEach(hist::put);
                cat.putRaw("hist", hist.toString());
            }
            categories.addRaw(cat.toString());
        }
        Json.Arr recent = Json.arr();
        for (KernelData.Conn conn : d.tcp().recent()) {
            recent.addRaw(Json.obj()
                    .put("pid", conn.pid())
                    .put("comm", conn.comm())
                    .put("laddr", conn.laddr())
                    .put("lport", conn.lport())
                    .put("raddr", conn.raddr())
                    .put("rport", conn.rport())
                    .put("txKb", conn.txKb())
                    .put("rxKb", conn.rxKb())
                    .put("ms", conn.ms())
                    .toString());
        }
        Json.Arr reasons = Json.arr();
        for (KernelData.Reason r : d.offCpu().reasons()) {
            reasons.addRaw(Json.obj()
                    .put("name", r.name())
                    .put("episodes", r.episodes())
                    .put("totalMs", r.totalMs())
                    .put("pct", r.pct())
                    .toString());
        }
        Json.Arr threads = Json.arr();
        for (KernelData.ThreadStat t : d.offCpu().threads()) {
            threads.addRaw(Json.obj()
                    .put("tid", t.tid())
                    .put("episodes", t.episodes())
                    .put("offCpuMs", t.offCpuMs())
                    .put("maxMs", t.maxMs())
                    .put("onCpuMs", t.onCpuMs())
                    .put("offCpuPct", t.offCpuPct())
                    .put("partial", t.partial())
                    .toString());
        }
        return Json.obj()
                .put("pid", d.pid())
                .put("tEpochMs", d.tEpochMs())
                .put("windowSeconds", d.windowSeconds())
                .putRaw("ops", Json.obj()
                        .putRaw("categories", categories.toString())
                        .putRaw("diagnostics", Json.obj()
                                .put("pendingFileOps", d.diagnostics().pendingFileOps())
                                .put("unmatchedCompletions", d.diagnostics().unmatchedCompletions())
                                .put("duplicateStarts", d.diagnostics().duplicateStarts())
                                .put("evictedPending", d.diagnostics().evictedPending())
                                .toString())
                        .toString())
                .putRaw("tcp", Json.obj()
                        .put("closedTotal", d.tcp().closedTotal())
                        .put("txBytes", d.tcp().txBytes())
                        .put("rxBytes", d.tcp().rxBytes())
                        .put("stillOpen", d.tcp().stillOpen())
                        .put("unmatchedDisconnects", d.tcp().unmatchedDisconnects())
                        .putRaw("recent", recent.toString())
                        .toString())
                .putRaw("offCpu", Json.obj()
                        .put("onCpuMs", d.offCpu().onCpuMs())
                        .put("offCpuMs", d.offCpu().offCpuMs())
                        .put("offCpuPct", d.offCpu().offCpuPct())
                        .put("episodes", d.offCpu().episodes())
                        .put("maxEpisodeMs", d.offCpu().maxEpisodeMs())
                        .putRaw("reasons", reasons.toString())
                        .putRaw("threads", threads.toString())
                        .toString())
                .toString();
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}

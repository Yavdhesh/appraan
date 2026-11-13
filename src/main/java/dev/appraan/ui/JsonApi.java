package dev.appraan.ui;

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

    /** {@code GET /api/gc} — GC counts, pauses, recency. */
    public static String gc(Snapshot s) {
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

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}

package dev.appraan.kernel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OS-neutral model of one kernel snapshot, as produced by the
 * <b>appraanagent</b> sidecar (appraan-windows) and consumed by Appraan's
 * console and web UI. This is the platform-parity seam: the model says
 * nothing about ETW or Windows; only the producer is Windows-specific.
 *
 * <p>The JSON contract (written by the agent, parsed here — see
 * {@link #parse(String)}):
 * <pre>
 * {"pid":4828,"tEpochMs":…,"windowSeconds":25.0,
 *  "ops":{"categories":[{"name":"File","count":2909,"latOps":2909,
 *          "avgUs":18.6,"maxUs":2727.5,"kinds":{"Write":112},
 *          "hist":{"&lt;10us":2706,…}}…],
 *         "diagnostics":{"pendingFileOps":0,"unmatchedCompletions":14,
 *          "duplicateStarts":0,"evictedPending":0}},
 *  "tcp":{"closedTotal":262,"txBytes":8384,"rxBytes":8384,"stillOpen":0,
 *         "unmatchedDisconnects":0,"recent":[{…}]},
 *  "offCpu":{"onCpuMs":…,"offCpuMs":…,"offCpuPct":…,"episodes":…,
 *            "maxEpisodeMs":…,"reasons":[{…}],"threads":[{…}]}}
 * </pre>
 * {@code avgUs}/{@code maxUs} are {@code null} for categories that measure
 * no latency. {@code hist} maps the producer's latency-bucket labels
 * (winops' buckets, e.g. {@code "<10us"} … {@code ">=50ms"}) to counts and
 * is empty for latency-less categories.
 */
public record KernelData(long pid, long tEpochMs, double windowSeconds,
                         List<Category> categories, Diagnostics diagnostics,
                         Tcp tcp, OffCpu offCpu,
                         /** Producing host (optional, pod/collector foresight); null when absent. */
                         String host,
                         /** Free-form producer labels (optional); empty when absent. */
                         Map<String, String> labels) {

    public record Category(String name, long count, long latOps, Double avgUs, Double maxUs,
                           Map<String, Long> kinds, Map<String, Long> hist) {
    }

    public record Diagnostics(long pendingFileOps, long unmatchedCompletions,
                              long duplicateStarts, long evictedPending) {
    }

    public record Tcp(long closedTotal, long txBytes, long rxBytes, long stillOpen,
                      long unmatchedDisconnects, List<Conn> recent) {
    }

    public record Conn(long pid, String comm, String laddr, long lport,
                       String raddr, long rport, double txKb, double rxKb, double ms) {
    }

    public record OffCpu(double onCpuMs, double offCpuMs, double offCpuPct, long episodes,
                         double maxEpisodeMs, List<Reason> reasons, List<ThreadStat> threads) {
    }

    public record Reason(String name, long episodes, double totalMs, double pct) {
    }

    public record ThreadStat(long tid, long episodes, double offCpuMs, double maxMs,
                             double onCpuMs, double offCpuPct, boolean partial) {
    }

    /** The category with the given name, or {@code null}. */
    public Category category(String name) {
        for (Category c : categories) {
            if (c.name().equals(name)) {
                return c;
            }
        }
        return null;
    }

    /**
     * Parses one snapshot document. Malformed JSON (or a non-object root)
     * throws {@link JsonParser.ParseException}; missing sections degrade to
     * empty/zero values so a slightly older/newer agent still parses.
     */
    public static KernelData parse(String json) {
        Object root = JsonParser.parse(json);
        Map<String, Object> doc = asObject(root);
        if (doc == null) {
            throw new JsonParser.ParseException("kernel snapshot root is not a JSON object");
        }
        Map<String, Object> ops = asObject(doc.get("ops"));
        Map<String, Object> tcpObj = asObject(doc.get("tcp"));
        Map<String, Object> offObj = asObject(doc.get("offCpu"));
        return new KernelData(
                asLong(doc.get("pid")),
                asLong(doc.get("tEpochMs")),
                asDouble(doc.get("windowSeconds")),
                parseCategories(ops),
                parseDiagnostics(ops == null ? null : asObject(ops.get("diagnostics"))),
                parseTcp(tcpObj),
                parseOffCpu(offObj),
                doc.get("host") instanceof String h ? h : null,
                asStringMap(doc.get("labels")));
    }

    private static Map<String, String> asStringMap(Object o) {
        Map<String, Object> m = asObject(o);
        if (m == null) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        m.forEach((k, v) -> {
            if (v instanceof String s) {
                out.put(k, s);
            }
        });
        return java.util.Collections.unmodifiableMap(out);
    }

    private static List<Category> parseCategories(Map<String, Object> ops) {
        if (ops == null) {
            return List.of();
        }
        List<Object> arr = asArray(ops.get("categories"));
        if (arr == null) {
            return List.of();
        }
        java.util.ArrayList<Category> out = new java.util.ArrayList<>();
        for (Object o : arr) {
            Map<String, Object> c = asObject(o);
            if (c == null) {
                continue;
            }
            out.add(new Category(
                    asString(c.get("name"), ""),
                    asLong(c.get("count")),
                    asLong(c.get("latOps")),
                    asDoubleOrNull(c.get("avgUs")),
                    asDoubleOrNull(c.get("maxUs")),
                    asLongMap(c.get("kinds")),
                    asLongMap(c.get("hist"))));
        }
        return List.copyOf(out);
    }

    private static Diagnostics parseDiagnostics(Map<String, Object> d) {
        if (d == null) {
            return new Diagnostics(0, 0, 0, 0);
        }
        return new Diagnostics(
                asLong(d.get("pendingFileOps")),
                asLong(d.get("unmatchedCompletions")),
                asLong(d.get("duplicateStarts")),
                asLong(d.get("evictedPending")));
    }

    private static Tcp parseTcp(Map<String, Object> t) {
        if (t == null) {
            return new Tcp(0, 0, 0, 0, 0, List.of());
        }
        java.util.ArrayList<Conn> recent = new java.util.ArrayList<>();
        List<Object> arr = asArray(t.get("recent"));
        if (arr != null) {
            for (Object o : arr) {
                Map<String, Object> c = asObject(o);
                if (c == null) {
                    continue;
                }
                recent.add(new Conn(
                        asLong(c.get("pid")),
                        asString(c.get("comm"), ""),
                        asString(c.get("laddr"), ""),
                        asLong(c.get("lport")),
                        asString(c.get("raddr"), ""),
                        asLong(c.get("rport")),
                        asDouble(c.get("txKb")),
                        asDouble(c.get("rxKb")),
                        asDouble(c.get("ms"))));
            }
        }
        return new Tcp(
                asLong(t.get("closedTotal")),
                asLong(t.get("txBytes")),
                asLong(t.get("rxBytes")),
                asLong(t.get("stillOpen")),
                asLong(t.get("unmatchedDisconnects")),
                List.copyOf(recent));
    }

    private static OffCpu parseOffCpu(Map<String, Object> o) {
        if (o == null) {
            return new OffCpu(0, 0, 0, 0, 0, List.of(), List.of());
        }
        java.util.ArrayList<Reason> reasons = new java.util.ArrayList<>();
        List<Object> rArr = asArray(o.get("reasons"));
        if (rArr != null) {
            for (Object r : rArr) {
                Map<String, Object> m = asObject(r);
                if (m == null) {
                    continue;
                }
                reasons.add(new Reason(
                        asString(m.get("name"), ""),
                        asLong(m.get("episodes")),
                        asDouble(m.get("totalMs")),
                        asDouble(m.get("pct"))));
            }
        }
        java.util.ArrayList<ThreadStat> threads = new java.util.ArrayList<>();
        List<Object> tArr = asArray(o.get("threads"));
        if (tArr != null) {
            for (Object th : tArr) {
                Map<String, Object> m = asObject(th);
                if (m == null) {
                    continue;
                }
                threads.add(new ThreadStat(
                        asLong(m.get("tid")),
                        asLong(m.get("episodes")),
                        asDouble(m.get("offCpuMs")),
                        asDouble(m.get("maxMs")),
                        asDouble(m.get("onCpuMs")),
                        asDouble(m.get("offCpuPct")),
                        Boolean.TRUE.equals(m.get("partial"))));
            }
        }
        return new OffCpu(
                asDouble(o.get("onCpuMs")),
                asDouble(o.get("offCpuMs")),
                asDouble(o.get("offCpuPct")),
                asLong(o.get("episodes")),
                asDouble(o.get("maxEpisodeMs")),
                List.copyOf(reasons),
                List.copyOf(threads));
    }

    // ---- tolerant scalar extraction over the parser's value tree ----

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asArray(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    private static long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static double asDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static Double asDoubleOrNull(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    private static String asString(Object o, String fallback) {
        return o instanceof String s ? s : fallback;
    }

    private static Map<String, Long> asLongMap(Object o) {
        Map<String, Object> m = asObject(o);
        if (m == null) {
            return Map.of();
        }
        Map<String, Long> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k, asLong(v)));
        // Insertion order is meaningful (kinds by count, hist by bucket) —
        // Map.copyOf would scramble it, so wrap a LinkedHashMap instead.
        return java.util.Collections.unmodifiableMap(out);
    }
}

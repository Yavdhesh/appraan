package dev.appraan.kernel;

/**
 * Manual test for {@link KernelData} + {@link JsonParser}: a snapshot
 * document built from the REAL verified Windows runs (winops pid 18772,
 * winoffcpu pid 53152, wintcplife reverification — the same figures as
 * proof/run4/kernel-fixture.json) must parse into exactly those values,
 * histogram included; malformed documents must be rejected.
 * Run via {@code ./run-tests.sh}; exits non-zero (AssertionError) on failure.
 */
public final class KernelDataTest {

    private static int checks;

    /** Compact but structurally complete agent document, verified numbers. */
    private static final String DOC = """
            {"pid":18772,"tEpochMs":1794596400000,"windowSeconds":40.0,
             "ops":{"categories":[
               {"name":"File","count":4040,"latOps":4040,"avgUs":139.0,"maxUs":17270.1,
                "kinds":{"QueryInfo":1663,"Write":1172,"Close":357,"Cleanup":251,"FSControl":235,"Create":234,"Read":125,"Delete":2,"Flush":1},
                "hist":{"<10us":2706,"<50us":543,"<100us":141,"<500us":312,"<1ms":187,"<5ms":145,"<10ms":5,"<50ms":1,">=50ms":0}},
               {"name":"Disk","count":1291,"latOps":1291,"avgUs":251.1,"maxUs":6088.5,
                "kinds":{"Write":1116,"Read":175},
                "hist":{"<10us":0,"<50us":356,"<100us":40,"<500us":682,"<1ms":172,"<5ms":38,"<10ms":3,"<50ms":0,">=50ms":0}},
               {"name":"Network","count":919,"latOps":0,"avgUs":null,"maxUs":null,
                "kinds":{"TcpRecv":235,"TcpDisconnect":228,"TcpSend":228,"TcpAccept":114,"TcpConnect":114}},
               {"name":"ProcessThread","count":4813,"latOps":0,"avgUs":null,"maxUs":null,
                "kinds":{"ContextSwitch":4794,"ThreadStop":16,"ThreadStart":2,"ProcessStop":1}},
               {"name":"Registry","count":0,"latOps":0,"avgUs":null,"maxUs":null,"kinds":{}}],
              "diagnostics":{"pendingFileOps":0,"unmatchedCompletions":22,"duplicateStarts":0,"evictedPending":0}},
             "tcp":{"closedTotal":262,"txBytes":8384,"rxBytes":8384,"stillOpen":0,"unmatchedDisconnects":0,
              "recent":[{"pid":18772,"comm":"winload","laddr":"127.0.0.1","lport":55001,"raddr":"127.0.0.1","rport":61339,"txKb":0.03,"rxKb":0.03,"ms":0.42}]},
             "offCpu":{"onCpuMs":584.0,"offCpuMs":138427.2,"offCpuPct":99.6,"episodes":4113,"maxEpisodeMs":5671.6,
              "reasons":[{"name":"WrQueue","episodes":2234,"totalMs":88776.3,"pct":64.1},
                         {"name":"UserRequest","episodes":298,"totalMs":48918.6,"pct":35.3}],
              "threads":[{"tid":8488,"episodes":243,"offCpuMs":12908.7,"maxMs":120.2,"onCpuMs":6.4,"offCpuPct":100.0,"partial":true}]}}
            """;

    public static void main(String[] args) {
        parsesVerifiedFigures();
        histogramRoundTrips();
        parserRejectsMalformed();
        parserHandlesEscapesAndShapes();
        System.out.println("KernelDataTest: all " + checks + " checks passed");
    }

    private static void parsesVerifiedFigures() {
        KernelData d = KernelData.parse(DOC);
        check(d.pid() == 18772, "pid");
        check(d.tEpochMs() == 1794596400000L, "tEpochMs");
        check(d.windowSeconds() == 40.0, "windowSeconds");
        check(d.categories().size() == 5, "five categories");

        KernelData.Category file = d.category("File");
        check(file.count() == 4040 && file.latOps() == 4040, "File counts");
        check(file.avgUs() == 139.0 && file.maxUs() == 17270.1, "File latency");
        check(file.kinds().get("Write") == 1172 && file.kinds().get("Flush") == 1, "File kinds");
        check(file.kinds().values().stream().mapToLong(Long::longValue).sum() == 4040,
                "File kinds sum to count");

        KernelData.Category net = d.category("Network");
        check(net.count() == 919 && net.latOps() == 0, "Network counts");
        check(net.avgUs() == null && net.maxUs() == null, "Network latency is null, not 0");
        check(net.hist().isEmpty(), "Network has no histogram");

        check(d.diagnostics().unmatchedCompletions() == 22, "diagnostics unmatched");
        check(d.diagnostics().pendingFileOps() == 0, "diagnostics pending");

        check(d.tcp().closedTotal() == 262, "tcp closedTotal");
        check(d.tcp().txBytes() == 8384 && d.tcp().rxBytes() == 8384, "tcp bytes");
        KernelData.Conn c = d.tcp().recent().get(0);
        check("winload".equals(c.comm()) && c.lport() == 55001 && c.rport() == 61339, "conn endpoints");
        check(c.txKb() == 0.03 && c.ms() == 0.42, "conn sizes/lifetime");

        check(d.offCpu().offCpuPct() == 99.6, "offCpu pct");
        check(d.offCpu().episodes() == 4113 && d.offCpu().maxEpisodeMs() == 5671.6, "offCpu episodes/max");
        KernelData.Reason top = d.offCpu().reasons().get(0);
        check("WrQueue".equals(top.name()) && top.episodes() == 2234 && top.pct() == 64.1, "top reason");
        KernelData.ThreadStat t = d.offCpu().threads().get(0);
        check(t.tid() == 8488 && t.partial() && t.offCpuMs() == 12908.7, "top thread");
    }

    private static void histogramRoundTrips() {
        KernelData d = KernelData.parse(DOC);
        KernelData.Category file = d.category("File");
        check(file.hist().get("<10us") == 2706, "File hist <10us");
        check(file.hist().get(">=50ms") == 0, "File hist >=50ms present as 0");
        check(file.hist().values().stream().mapToLong(Long::longValue).sum() == 4040,
                "File hist sums to latency count");
        check(file.hist().keySet().iterator().next().equals("<10us"),
                "hist bucket order preserved (label order, not alphabetical)");
        KernelData.Category disk = d.category("Disk");
        check(disk.hist().get("<500us") == 682, "Disk hist <500us");
        check(disk.hist().values().stream().mapToLong(Long::longValue).sum() == 1291,
                "Disk hist sums to latency count");
    }

    private static void parserRejectsMalformed() {
        expectParseFailure("", "empty input");
        expectParseFailure("{", "unterminated object");
        expectParseFailure("{\"pid\":}", "missing value");
        expectParseFailure("{\"pid\":18772} garbage", "trailing content");
        expectParseFailure("[1,2", "unterminated array");
        expectParseFailure("{\"a\":\"unterminated}", "unterminated string");
        expectParseFailure("not json", "bare word");
        expectParseFailure("[1,2,3]", "array root is not a snapshot");
    }

    private static void parserHandlesEscapesAndShapes() {
        Object v = JsonParser.parse("{\"s\":\"a\\\"b\\\\c\\n\\u0041\",\"n\":-12.5e2,\"b\":true,\"z\":null}");
        check(v instanceof java.util.Map, "object root");
        java.util.Map<?, ?> m = (java.util.Map<?, ?>) v;
        check("a\"b\\c\nA".equals(m.get("s")), "string escapes incl. \\u");
        check(((Double) m.get("n")) == -1250.0, "exponent number");
        check(Boolean.TRUE.equals(m.get("b")), "boolean");
        check(m.containsKey("z") && m.get("z") == null, "null literal");

        // Missing sections degrade to empty/zero instead of failing.
        KernelData sparse = KernelData.parse("{\"pid\":7}");
        check(sparse.pid() == 7 && sparse.categories().isEmpty()
                && sparse.tcp().closedTotal() == 0 && sparse.offCpu().episodes() == 0,
                "sparse document degrades gracefully");
    }

    private static void expectParseFailure(String json, String label) {
        checks++;
        try {
            KernelData.parse(json);
        } catch (JsonParser.ParseException expected) {
            return;
        }
        throw new AssertionError("KernelDataTest FAILED: expected ParseException for " + label);
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) {
            throw new AssertionError("KernelDataTest FAILED: " + label);
        }
    }
}

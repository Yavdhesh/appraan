package dev.appraan.kernel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Manual test for {@link KernelFeed}: missing-file tolerance, the file
 * feed, push precedence, staleness, and the platform-aware hint state
 * machine (Windows gets the elevated appraanagent command with this
 * instance's PID and port; Linux never does).
 * Run via {@code ./run-tests.sh}; exits non-zero (AssertionError) on failure.
 */
public final class KernelFeedTest {

    private static int checks;

    private static final String DOC = "{\"pid\":4242,\"tEpochMs\":1,\"windowSeconds\":2.0,"
            + "\"ops\":{\"categories\":[],\"diagnostics\":{\"pendingFileOps\":0,"
            + "\"unmatchedCompletions\":0,\"duplicateStarts\":0,\"evictedPending\":0}},"
            + "\"tcp\":{\"closedTotal\":3,\"txBytes\":96,\"rxBytes\":96,\"stillOpen\":0,"
            + "\"unmatchedDisconnects\":0,\"recent\":[]},"
            + "\"offCpu\":{\"onCpuMs\":1,\"offCpuMs\":2,\"offCpuPct\":66.7,\"episodes\":4,"
            + "\"maxEpisodeMs\":5,\"reasons\":[],\"threads\":[]}}";

    public static void main(String[] args) throws Exception {
        missingFileTolerated();
        fileFeedAndStaleness();
        pushTakesPrecedence();
        windowsNoDataHint();
        windowsWaitingFileHint();
        windowsStaleHint();
        linuxHintsNeverMentionAgent();
        System.out.println("KernelFeedTest: all " + checks + " checks passed");
    }

    private static void missingFileTolerated() throws Exception {
        Path dir = Files.createTempDirectory("appraan-feed-test");
        KernelFeed feed = new KernelFeed(dir.resolve("nope.json"), false, "4242", 7676);
        feed.refreshFile(); // must not throw
        KernelFeed.View v = feed.view();
        check(v.data() == null && "none".equals(v.source()), "missing file -> no data, no crash");
        check(v.ageSeconds() == -1 && !v.stale(), "no data -> age -1, not stale");
    }

    private static void fileFeedAndStaleness() throws Exception {
        Path dir = Files.createTempDirectory("appraan-feed-test");
        Path file = dir.resolve("kernel.json");
        Files.writeString(file, DOC);
        KernelFeed feed = new KernelFeed(file, false, "4242", 7676);
        feed.refreshFile();
        KernelFeed.View v = feed.view();
        check(v.data() != null && "file".equals(v.source()), "file snapshot loaded");
        check(v.data().tcp().closedTotal() == 3, "file snapshot parsed");

        Instant mtime = Files.getLastModifiedTime(file).toInstant();
        KernelFeed.View fresh = feed.viewAt(mtime.plusSeconds(10));
        check(fresh.ageSeconds() == 10 && !fresh.stale(), "10s old is fresh");
        KernelFeed.View stale = feed.viewAt(mtime.plusSeconds(16));
        check(stale.ageSeconds() == 16 && stale.stale(), "16s old is stale");
        check("stale".equals(stale.hint().state()), "stale hint state");

        // A torn/half-written update keeps the last good snapshot.
        Files.writeString(file, "{\"pid\":");
        // Force a new mtime so refresh actually re-reads.
        Files.setLastModifiedTime(file,
                java.nio.file.attribute.FileTime.from(mtime.plusSeconds(60)));
        feed.refreshFile();
        check(feed.view().data() != null && feed.view().data().tcp().closedTotal() == 3,
                "malformed rewrite keeps last good snapshot");
    }

    private static void pushTakesPrecedence() throws Exception {
        Path dir = Files.createTempDirectory("appraan-feed-test");
        Path file = dir.resolve("kernel.json");
        Files.writeString(file, DOC);
        KernelFeed feed = new KernelFeed(file, true, "4242", 7676);
        feed.refreshFile();
        check("file".equals(feed.view().source()), "file before any push");

        KernelData pushed = KernelData.parse(DOC.replace("\"closedTotal\":3", "\"closedTotal\":9"));
        feed.ingest(pushed, Instant.now().plusSeconds(1));
        KernelFeed.View v = feed.view();
        check("push".equals(v.source()) && v.data().tcp().closedTotal() == 9,
                "push wins over file once it arrives");
    }

    private static void windowsNoDataHint() {
        KernelFeed feed = new KernelFeed(null, true, "4242", 7676);
        KernelFeed.Hint h = feed.view().hint();
        check("none".equals(h.state()), "windows+none state");
        check(h.message() != null && h.message().contains("appraanagent")
                && h.message().contains("elevated"), "windows+none message names the agent + elevation");
        check(("appraanagent 4242 --push http://127.0.0.1:7676/api/kernel/ingest --interval 2")
                .equals(h.command()), "windows+none command has observed PID and actual port");

        KernelFeed otherPort = new KernelFeed(null, true, "99", 0);
        otherPort.setUiPort(8123);
        check(otherPort.view().hint().command().contains("127.0.0.1:8123"),
                "command uses the bound port once known");
    }

    private static void windowsWaitingFileHint() throws Exception {
        Path dir = Files.createTempDirectory("appraan-feed-test");
        Path file = dir.resolve("appraan-kernel.json");
        KernelFeed feed = new KernelFeed(file, true, "4242", 7676);
        KernelFeed.Hint h = feed.view().hint();
        check("waiting-file".equals(h.state()), "windows+file-mode state");
        check(h.message() != null && h.message().contains(file.toString()),
                "waiting-file message names the path");
        check(h.command() != null && h.command().contains("--out " + file),
                "waiting-file command writes that file");
    }

    private static void windowsStaleHint() {
        KernelFeed feed = new KernelFeed(null, true, "4242", 7676);
        Instant t0 = Instant.now();
        feed.ingest(KernelData.parse(DOC), t0);
        KernelFeed.View live = feed.viewAt(t0.plusSeconds(5));
        check("live".equals(live.hint().state()) && !live.stale(), "fresh push is live");
        KernelFeed.View stale = feed.viewAt(t0.plusSeconds(30));
        check(stale.stale() && "stale".equals(stale.hint().state()), "30s old push is stale");
        check(stale.hint().message() != null
                && stale.hint().message().contains("appraanagent seems to have stopped")
                && stale.hint().message().contains("30s ago"), "stale message + age");
        check(stale.hint().command() != null
                && stale.hint().command().contains("--push http://127.0.0.1:7676/api/kernel/ingest"),
                "stale hint reuses the push command");
        check(stale.data() != null, "stale view still carries the last data");
    }

    private static void linuxHintsNeverMentionAgent() {
        KernelFeed feed = new KernelFeed(null, false, "4242", 7676);
        KernelFeed.Hint h = feed.view().hint();
        check("none".equals(h.state()), "linux+none state");
        check(h.command() == null, "linux hint has no agent command");
        check(h.message() == null || !h.message().contains("appraanagent"),
                "linux hint never instructs running the Windows agent");

        Instant t0 = Instant.now();
        feed.ingest(KernelData.parse(DOC), t0);
        KernelFeed.Hint stale = feed.viewAt(t0.plusSeconds(30)).hint();
        check("stale".equals(stale.state()) && stale.command() == null
                && (stale.message() == null || !stale.message().contains("appraanagent")),
                "linux stale hint is generic, no agent instruction");
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) {
            throw new AssertionError("KernelFeedTest FAILED: " + label);
        }
    }
}

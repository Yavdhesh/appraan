package dev.appraan.ui;

import dev.appraan.attach.TargetVm;
import dev.appraan.discover.TargetInfo;
import dev.appraan.jfr.LiveJfr;
import dev.appraan.metrics.Metrics;
import dev.appraan.os.OsCollector;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Proof harness for Milestone 2 (not a test): attaches to a live target with
 * the real M1 pipeline, captures one {@link Snapshot}, then
 * <ol>
 *   <li>starts a real {@link UiServer} on an ephemeral port and tries to GET
 *       every route with an in-process {@link HttpClient} (the "second JVM
 *       client" rung of the proof ladder — loopback TCP may be intercepted
 *       in restricted sandboxes, in which case this reports the failure
 *       honestly and step 2 still proves the renderers);</li>
 *   <li>renders every page/payload through the same pure functions the HTTP
 *       handlers delegate to, and saves them as static artefacts;</li>
 *   <li>verifies the flame graph's widest path and cross-checks the JSON
 *       numbers against the {@link Metrics} they were captured from.</li>
 * </ol>
 *
 * Usage: {@code RenderProof <PID> <seconds> <outDir>}
 */
public final class RenderProof {

    public static void main(String[] args) throws Exception {
        String pid = args[0];
        int seconds = Integer.parseInt(args[1]);
        Path outDir = Path.of(args[2]);
        Files.createDirectories(outDir);

        TargetVm target = TargetVm.attach(pid);
        Snapshot snapshot;
        Metrics metrics;
        try (target) {
            TargetInfo info = TargetInfo.discover(target);
            metrics = new Metrics();
            OsCollector os = OsCollector.create();
            try (LiveJfr jfr = new LiveJfr(target, metrics)) {
                jfr.start();
                metrics.setAllocationWarmup(true);
                Thread.sleep(2000);
                jfr.pollOnce();
                metrics.setAllocationWarmup(false);

                long started = System.nanoTime();
                long prevAlloc = metrics.allocatedBytes();
                Instant prevWall = Instant.now();
                double rate = 0;
                double elapsed = 0;
                while (elapsed < seconds) {
                    Thread.sleep(5000);
                    jfr.pollOnce();
                    Instant now = Instant.now();
                    double window = Duration.between(prevWall, now).toMillis() / 1000.0;
                    rate = window > 0 ? (metrics.allocatedBytes() - prevAlloc) / window : 0;
                    prevAlloc = metrics.allocatedBytes();
                    prevWall = now;
                    elapsed = (System.nanoTime() - started) / 1_000_000_000.0;
                }
                snapshot = Snapshot.capture(info, metrics, os, jfr.watermark(), rate, elapsed);
                jfr.stop();
            }
        }

        System.out.println("--- RenderProof: captured snapshot from live pipeline ---");
        System.out.println("metrics: youngGc=" + metrics.youngGcCount()
                + " oldGc=" + metrics.oldGcCount()
                + " allocBytes=" + metrics.allocatedBytes()
                + " executionSamples=" + metrics.executionSamples()
                + " stackSamples=" + metrics.stackSamples()
                + " topLeaf=" + metrics.topLeafMethods(1));

        // Rung 2 of the ladder: a real HTTP round-trip from this JVM.
        boolean httpOk = false;
        try (UiServer ui = new UiServer(() -> snapshot, 0)) {
            ui.start();
            System.out.println("--- RenderProof: UiServer started at " + ui.baseUrl() + " ---");
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5)).build();
            for (String path : List.of("/", "/flame", "/api/summary", "/api/gc",
                    "/api/cpu", "/api/cores", "/api/stacks", "/nope")) {
                try {
                    HttpResponse<String> resp = client.send(
                            HttpRequest.newBuilder(URI.create(ui.baseUrl() + path))
                                    .timeout(Duration.ofSeconds(5)).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    System.out.println("GET " + path + " -> HTTP " + resp.statusCode()
                            + " (" + resp.body().length() + " chars)");
                    if (resp.statusCode() == 200) {
                        httpOk = true;
                    }
                } catch (Exception e) {
                    System.out.println("GET " + path + " -> FAILED: " + e);
                }
            }
        } catch (Exception e) {
            System.out.println("UiServer start FAILED: " + e);
        }
        System.out.println("in-process HTTP round-trip: " + (httpOk ? "WORKED" : "did not work"));

        // Renderer artefacts (the exact functions the handlers delegate to).
        Files.writeString(outDir.resolve("dashboard.html"), Pages.dashboardHtml());
        Files.writeString(outDir.resolve("flame.html"), Pages.flamePage(snapshot));
        Files.writeString(outDir.resolve("flame.svg"),
                FlameGraph.svg(snapshot.collapsedStacks(), FlameGraph.DEFAULT_WIDTH));
        Files.writeString(outDir.resolve("api-summary.json"), JsonApi.summary(snapshot));
        Files.writeString(outDir.resolve("api-gc.json"), JsonApi.gc(snapshot));
        Files.writeString(outDir.resolve("api-cpu.json"), JsonApi.cpu(snapshot));
        Files.writeString(outDir.resolve("api-cores.json"), JsonApi.cores(snapshot));
        Files.writeString(outDir.resolve("api-stacks.json"), JsonApi.stacks(snapshot));
        System.out.println("artefacts written to " + outDir);

        // Flame-graph sanity: widest root-to-leaf path must end at the hot leaf.
        List<String> widest = FlameGraph.widestPath(snapshot.collapsedStacks());
        System.out.println("flame widest path: " + String.join(" -> ", widest));
        String leaf = widest.isEmpty() ? "" : widest.get(widest.size() - 1);
        System.out.println("flame widest leaf: " + leaf
                + " | metrics top leaf: " + metrics.topLeafMethods(1).get(0).getKey());
        if (!leaf.equals(metrics.topLeafMethods(1).get(0).getKey())) {
            throw new AssertionError("widest flame path does not end at the metrics top leaf");
        }

        // Consistency: JSON rendered from the snapshot must carry the metrics' numbers.
        String gcJson = JsonApi.gc(snapshot);
        String cpuJson = JsonApi.cpu(snapshot);
        assertContains(gcJson, "\"count\":" + metrics.youngGcCount(), "gc json young count");
        assertContains(cpuJson, "\"executionSamples\":" + metrics.executionSamples(),
                "cpu json execution samples");
        assertContains(cpuJson, metrics.topLeafMethods(1).get(0).getKey(), "cpu json top leaf name");
        System.out.println("consistency: JSON payloads match the Metrics they were captured from");
        System.out.println("--- RenderProof: DONE ---");
    }

    private static void assertContains(String haystack, String needle, String label) {
        if (!haystack.contains(needle)) {
            throw new AssertionError("consistency check failed (" + label + "): expected to find "
                    + needle + " in " + haystack);
        }
        System.out.println("consistency ok: " + label);
    }
}

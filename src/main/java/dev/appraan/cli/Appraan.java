package dev.appraan.cli;

import dev.appraan.attach.TargetVm;
import dev.appraan.discover.TargetInfo;
import dev.appraan.jfr.LiveJfr;
import dev.appraan.kernel.KernelData;
import dev.appraan.kernel.KernelFeed;
import dev.appraan.metrics.Metrics;
import dev.appraan.os.OsCollector;
import dev.appraan.os.ProcessIo;
import dev.appraan.ui.Snapshot;
import dev.appraan.ui.UiServer;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Appraan — live JVM observer.
 *
 * <p>Attaches to a target JVM by PID (Attach API, no TCP/JMX), starts a JFR
 * recording inside the target, and every interval dumps a snapshot, folds the
 * new events into running metrics, and prints one compact status block.
 */
public final class Appraan {

    // ---- graceful-shutdown coordination (SIGINT/SIGTERM) ----
    // The hook interrupts the main thread (breaking its sleep); the main
    // thread then runs the normal cleanup path (stop JFR, close UI, print
    // the final summary) and counts the latch down. Only if main does not
    // finish within a few seconds does the hook stop things itself.
    private static final CountDownLatch CLEANUP_DONE = new CountDownLatch(1);
    private static final AtomicBoolean HOOK_REGISTERED = new AtomicBoolean();
    private static volatile Thread mainThread;
    private static volatile LiveJfr hookJfr;
    private static volatile UiServer hookUi;
    private static volatile AtomicReference<Snapshot> hookSnapshots;

    private static void registerShutdownHook() {
        if (!HOOK_REGISTERED.compareAndSet(false, true)) {
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Thread m = mainThread;
            if (m != null) {
                m.interrupt();
            }
            boolean cleaned = false;
            try {
                cleaned = CLEANUP_DONE.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                // Fall through to emergency cleanup.
            }
            if (!cleaned) {
                try {
                    if (hookJfr != null) {
                        hookJfr.stop();
                    }
                } catch (Exception ignored) {
                    // Best effort only — the JVM is going down.
                }
                try {
                    if (hookUi != null) {
                        hookUi.close();
                    }
                } catch (Exception ignored) {
                    // Best effort only.
                }
                Snapshot s = hookSnapshots != null ? hookSnapshots.get() : null;
                if (s != null) {
                    printFinalSummary(s, true);
                }
                System.out.println("Appraan: shutdown hook completed cleanup (interrupted).");
            }
        }, "appraan-shutdown"));
    }

    private static void printFinalSummary(Snapshot s, boolean interrupted) {
        System.out.printf("%nAppraan final summary%s: PID %s observed %.0fs — young GCs %d, "
                        + "old GCs %d, allocated %s, CPU samples %d.%n",
                interrupted ? " (interrupted)" : "",
                s.pid(), s.elapsedSeconds(), s.youngGcCount(), s.oldGcCount(),
                Metrics.formatBytes(s.allocatedBytes()), s.executionSamples());
    }

    public static void main(String[] args) {
        CliOptions options;
        try {
            options = CliOptions.parse(args);
        } catch (CliOptions.Usage usage) {
            if (usage.help) {
                System.out.println(usage.getMessage());
            } else {
                System.err.println(usage.getMessage());
                System.exit(2);
            }
            return;
        }

        int exitCode = run(options);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static int run(CliOptions options) {
        TargetVm target;
        try {
            target = TargetVm.attach(options.pid());
        } catch (TargetVm.AttachFailure e) {
            System.err.println("Appraan: " + e.getMessage());
            return 1;
        }

        try (target) {
            TargetInfo info = TargetInfo.discover(target);
            System.out.println("=== Appraan: observing PID " + options.pid() + " ===");
            System.out.print(info.summary());

            Metrics metrics = new Metrics();
            OsCollector os = OsCollector.create();
            System.out.println("OS collector: " + os.name());
            if (options.seconds() == 0) {
                System.out.println("Observing until Ctrl+C (UI mode), status every "
                        + options.intervalSeconds() + "s.\n");
            } else {
                System.out.println("Observing for " + options.seconds() + "s, status every "
                        + options.intervalSeconds() + "s. Ctrl-C to stop early.\n");
            }

            // Kernel feed (appraanagent): push arrives via the UI's ingest
            // endpoint; --kernel-file is the fallback poll. Appraan itself
            // never needs elevation — the agent runs elevated on its own.
            boolean windows = System.getProperty("os.name", "")
                    .toLowerCase(Locale.ROOT).contains("win");
            KernelFeed kernelFeed = new KernelFeed(
                    options.kernelFile() == null ? null : Path.of(options.kernelFile()),
                    windows, options.pid(), options.port());

            // Embedded web UI over the same metric model: handlers read
            // immutable snapshots refreshed after every poll. A UI failure
            // (e.g. port in use) must never take the observer down with it.
            AtomicReference<Snapshot> liveSnapshot = new AtomicReference<>(
                    Snapshot.capture(info, metrics, os, Instant.now(), 0, 0));
            UiServer ui = null;
            if (options.uiEnabled()) {
                try {
                    ui = new UiServer(liveSnapshot::get, options.port(), kernelFeed);
                    ui.start();
                    kernelFeed.setUiPort(ui.port());
                    System.out.println("Web UI: " + ui.baseUrl() + "/  (kernel: "
                            + ui.baseUrl() + "/kernel, flame graph: "
                            + ui.baseUrl() + "/flame)");
                } catch (IOException e) {
                    System.err.println("Appraan: web UI unavailable on port " + options.port()
                            + " (" + e.getMessage() + ") — continuing with console output only.");
                }
            }
            if (options.kernelFile() != null) {
                System.out.println("Kernel feed: polling appraanagent file " + options.kernelFile()
                        + " (a push to /api/kernel/ingest still wins when it arrives).");
            } else if (ui != null) {
                System.out.println("Kernel feed: appraanagent can push to "
                        + ui.baseUrl() + "/api/kernel/ingest");
            }

            mainThread = Thread.currentThread();
            hookUi = ui;
            hookSnapshots = liveSnapshot;
            registerShutdownHook();

            try (LiveJfr jfr = new LiveJfr(target, metrics)) {
                hookJfr = jfr;
                jfr.start();
                // Sampler warm-up: the allocation sampler's weights are inflated
                // while its throttle adapts right after recording start. Poll once
                // with allocation accounting suspended so totals/rates stay honest.
                metrics.setAllocationWarmup(true);
                boolean interrupted = false;
                try {
                    Thread.sleep(2000);
                    jfr.pollOnce();
                    metrics.setAllocationWarmup(false);
                    runLoop(options, jfr, metrics, os, info, liveSnapshot, kernelFeed);
                } catch (InterruptedException e) {
                    // Ctrl+C: the shutdown hook interrupted us — finish cleanly.
                    interrupted = true;
                    metrics.setAllocationWarmup(false);
                }
                jfr.stop();
                printFinalSummary(liveSnapshot.get(), interrupted);
                System.out.println("\nJFR recording 'appraan' stopped in target; detached.");
            } finally {
                if (ui != null) {
                    ui.close();
                }
                CLEANUP_DONE.countDown();
            }
            return 0;
        } catch (Exception e) {
            System.err.println("Appraan: failed while observing PID " + options.pid() + ": " + e);
            return 1;
        }
    }

    private static void runLoop(CliOptions options, LiveJfr jfr, Metrics metrics, OsCollector os,
                                TargetInfo info, AtomicReference<Snapshot> liveSnapshot,
                                KernelFeed kernelFeed)
            throws Exception {
        long startedNanos = System.nanoTime();
        // --seconds 0 = run until Ctrl+C: an effectively unbounded total;
        // the existing remaining/sleep math then always picks the interval.
        long totalNanos = options.seconds() == 0
                ? Long.MAX_VALUE
                : Duration.ofSeconds(options.seconds()).toNanos();
        long prevAllocBytes = 0;
        long prevYoungGc = 0;
        long prevPromotionBytes = 0;
        long prevSamples = 0;
        boolean kernelHintPrinted = false;
        Instant prevWall = Instant.now();

        int round = 0;
        while (System.nanoTime() - startedNanos < totalNanos) {
            long remainingMs = (totalNanos - (System.nanoTime() - startedNanos)) / 1_000_000;
            long sleepMs = Math.min(Duration.ofSeconds(options.intervalSeconds()).toMillis(),
                    Math.max(remainingMs, 0));
            if (sleepMs > 0) {
                Thread.sleep(sleepMs);
            }
            try {
                jfr.pollOnce();
            } catch (IOException e) {
                System.out.println("\nTarget JVM exited during observation — ending early. ("
                        + e.getMessage() + ")");
                break;
            }
            round++;

            Instant wallNow = Instant.now();
            double windowSeconds = Duration.between(prevWall, wallNow).toMillis() / 1000.0;
            long allocBytes = metrics.allocatedBytes();
            double allocRate = windowSeconds > 0 ? (allocBytes - prevAllocBytes) / windowSeconds : 0;

            double elapsed = (System.nanoTime() - startedNanos) / 1_000_000_000.0;
            System.out.printf("--- t=+%.0fs ------------------------------------------------------%n", elapsed);
            System.out.printf("GC:    young=%d (+%d)  old=%d  |  pause total=%s  max=%s  |  last GC %s%n",
                    metrics.youngGcCount(), metrics.youngGcCount() - prevYoungGc,
                    metrics.oldGcCount(),
                    Metrics.formatDuration(metrics.totalPause()),
                    Metrics.formatDuration(metrics.maxPause()),
                    metrics.sinceLastGc(jfr.watermark()));
            var g1 = metrics.g1();
            if (g1.available()) {
                System.out.printf("G1:    eden=%s  survivor=%s  old=%s  regions=%d  |  promotion(est.) %s total (+%s this window, %d young GCs)  |  humongous: %d regions, %s used%n",
                        Metrics.formatBytes(g1.edenUsedBytes()),
                        Metrics.formatBytes(g1.survivorUsedBytes()),
                        Metrics.formatBytes(g1.oldUsedBytes()),
                        g1.regionCount(),
                        Metrics.formatBytes(g1.promotionBytes()),
                        Metrics.formatBytes(g1.promotionBytes() - prevPromotionBytes),
                        g1.promotionGcCount(),
                        g1.humongousRegionCount(),
                        Metrics.formatBytes(g1.humongousUsedBytes()));
            } else {
                System.out.println("G1:    n/a (target is not running the G1 collector)");
            }
            System.out.printf("Alloc: rate=%s/s  total=%s  |  heap used (latest JFR)=%s  |  source: %s%n",
                    Metrics.formatBytes((long) allocRate),
                    Metrics.formatBytes(allocBytes),
                    Metrics.formatBytes(metrics.lastHeapUsed()),
                    metrics.allocationSource());
            long windowSamples = metrics.executionSamples() - prevSamples;
            System.out.printf("CPU:   %d execution samples total (+%d this window)  top leaf methods:%n",
                    metrics.executionSamples(), windowSamples);
            for (Map.Entry<String, Long> e : metrics.topLeafMethods(5)) {
                System.out.printf("         %6d  %s%n", e.getValue(), e.getKey());
            }
            double[] cores = os.perCoreCpuBusyPercent();
            if (cores.length > 0) {
                StringBuilder sb = new StringBuilder("Cores: ");
                for (int i = 0; i < cores.length; i++) {
                    sb.append(String.format("[%d]=%.0f%% ", i, cores[i]));
                }
                System.out.println(sb.toString().trim());
            } else {
                System.out.println("Cores: n/a on this platform/collector");
            }

            // Kernel block: freshest appraanagent snapshot (push wins over
            // file), else the Linux /proc parity line, else — on Windows
            // only, once, after ~15 s — the elevated-agent guidance.
            kernelFeed.refreshFile();
            KernelFeed.View kv = kernelFeed.view();
            if (kv.data() != null) {
                printKernelBlock(kv);
            } else {
                ProcessIo pio = null;
                try {
                    pio = os.processIo(Long.parseLong(info.pid()));
                } catch (NumberFormatException ignored) {
                    // Non-numeric pid cannot happen (CLI validates digits).
                }
                if (pio != null) {
                    System.out.printf("Kernel: no ETW feed — /proc process IO (cumulative since "
                                    + "process start): read %s, write %s, syscalls r/w %d/%d%n",
                            Metrics.formatBytes(pio.readBytes()),
                            Metrics.formatBytes(pio.writeBytes()),
                            pio.syscr(), pio.syscw());
                }
                if (!kernelHintPrinted && elapsed >= 15 && kv.hint() != null
                        && kv.hint().command() != null) {
                    System.out.println("Kernel: " + kv.hint().message());
                    System.out.println("        " + kv.hint().command());
                    kernelHintPrinted = true;
                }
            }
            System.out.flush();

            // Publish the same numbers the console just printed to the web UI,
            // reusing this round's single per-core read (the collector is
            // delta-based; a second read would measure a millisecond window).
            liveSnapshot.set(Snapshot.capture(info, metrics, os, jfr.watermark(),
                    allocRate, elapsed, cores));

            prevAllocBytes = allocBytes;
            prevYoungGc = metrics.youngGcCount();
            prevPromotionBytes = metrics.g1().promotionBytes();
            prevSamples = metrics.executionSamples();
            prevWall = wallNow;
        }
    }

    /** The compact console {@code Kernel:} block, in the existing line style. */
    private static void printKernelBlock(KernelFeed.View kv) {
        KernelData d = kv.data();
        KernelData.OffCpu oc = d.offCpu();
        String topReason = oc.reasons().isEmpty()
                ? "n/a"
                : oc.reasons().get(0).name() + " " + oc.reasons().get(0).pct() + "%";
        System.out.printf("Kernel:%s source=%s age=%ds | off-CPU %.1f%% (on %.0f ms, off %.0f ms) "
                        + "episodes=%d max=%.0f ms | top reason %s%n",
                kv.stale() ? " (stale)" : "", kv.source(), kv.ageSeconds(),
                oc.offCpuPct(), oc.onCpuMs(), oc.offCpuMs(), oc.episodes(),
                oc.maxEpisodeMs(), topReason);
        StringBuilder opsLine = new StringBuilder("        ops:");
        String sep = " ";
        for (KernelData.Category c : d.categories()) {
            opsLine.append(sep).append(c.name()).append('=').append(c.count());
            if (c.avgUs() != null) {
                opsLine.append(" avg ").append(c.avgUs()).append(" us max ")
                        .append(c.maxUs()).append(" us");
            }
            sep = " | ";
        }
        System.out.println(opsLine);
        System.out.printf("        tcp: closed=%d tx=%s rx=%s open=%d%n",
                d.tcp().closedTotal(),
                Metrics.formatBytes(d.tcp().txBytes()),
                Metrics.formatBytes(d.tcp().rxBytes()),
                d.tcp().stillOpen());
    }
}

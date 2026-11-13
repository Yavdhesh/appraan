package dev.appraan.cli;

import dev.appraan.attach.TargetVm;
import dev.appraan.discover.TargetInfo;
import dev.appraan.jfr.LiveJfr;
import dev.appraan.metrics.Metrics;
import dev.appraan.os.OsCollector;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Appraan — live JVM observer.
 *
 * <p>Attaches to a target JVM by PID (Attach API, no TCP/JMX), starts a JFR
 * recording inside the target, and every interval dumps a snapshot, folds the
 * new events into running metrics, and prints one compact status block.
 */
public final class Appraan {

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
            System.out.println("Observing for " + options.seconds() + "s, status every "
                    + options.intervalSeconds() + "s. Ctrl-C to stop early.\n");

            try (LiveJfr jfr = new LiveJfr(target, metrics)) {
                jfr.start();
                // Sampler warm-up: the allocation sampler's weights are inflated
                // while its throttle adapts right after recording start. Poll once
                // with allocation accounting suspended so totals/rates stay honest.
                metrics.setAllocationWarmup(true);
                Thread.sleep(2000);
                jfr.pollOnce();
                metrics.setAllocationWarmup(false);
                runLoop(options, jfr, metrics, os);
                jfr.stop();
                System.out.println("\nJFR recording 'appraan' stopped in target; detached.");
            }
            return 0;
        } catch (Exception e) {
            System.err.println("Appraan: failed while observing PID " + options.pid() + ": " + e);
            return 1;
        }
    }

    private static void runLoop(CliOptions options, LiveJfr jfr, Metrics metrics, OsCollector os)
            throws Exception {
        long startedNanos = System.nanoTime();
        long totalNanos = Duration.ofSeconds(options.seconds()).toNanos();
        long prevAllocBytes = 0;
        long prevYoungGc = 0;
        long prevSamples = 0;
        Instant prevWall = Instant.now();

        int round = 0;
        while (System.nanoTime() - startedNanos < totalNanos) {
            long remainingMs = (totalNanos - (System.nanoTime() - startedNanos)) / 1_000_000;
            long sleepMs = Math.min(Duration.ofSeconds(options.intervalSeconds()).toMillis(),
                    Math.max(remainingMs, 0));
            if (sleepMs > 0) {
                Thread.sleep(sleepMs);
            }
            jfr.pollOnce();
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
            System.out.flush();

            prevAllocBytes = allocBytes;
            prevYoungGc = metrics.youngGcCount();
            prevSamples = metrics.executionSamples();
            prevWall = wallNow;
        }
    }
}

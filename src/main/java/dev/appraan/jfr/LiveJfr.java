package dev.appraan.jfr;

import dev.appraan.attach.TargetVm;
import dev.appraan.metrics.Metrics;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Drives a named JFR recording inside the target JVM via jcmd
 * ({@code JFR.start}/{@code JFR.dump}/{@code JFR.stop}) and incrementally parses
 * snapshot dumps with {@link RecordingFile}.
 *
 * <p>Each dump contains the recording from its start, so events are filtered by a
 * high-water mark: only events starting after the newest timestamp processed in
 * previous rounds are fed to {@link Metrics} (GC events are additionally deduped
 * by type+startTime inside Metrics, since a GC event is emitted on completion and
 * may start before the watermark).
 */
public final class LiveJfr implements AutoCloseable {

    public static final String RECORDING_NAME = "appraan";

    private final TargetVm target;
    private final Metrics metrics;
    private final Path dumpDir;
    private Instant watermark = Instant.EPOCH;
    private int dumpSeq;
    private boolean running;

    public LiveJfr(TargetVm target, Metrics metrics) throws IOException {
        this.target = target;
        this.metrics = metrics;
        this.dumpDir = Files.createTempDirectory("appraan-" + target.pid() + "-");
    }

    public void start() throws IOException {
        // Clean up a recording left behind by a previous crashed Appraan run.
        try {
            target.jcmd("JFR.stop name=" + RECORDING_NAME);
        } catch (Exception ignored) {
            // expected when no such recording exists
        }
        String out = target.jcmd("JFR.start name=" + RECORDING_NAME + " settings=profile disk=true");
        running = true;
        System.out.println("JFR recording started in target: " + firstLine(out));
    }

    /** Dumps a snapshot of the target recording and processes only new events. */
    public void pollOnce() throws IOException {
        Path snapshot = dumpDir.resolve("snapshot-" + (++dumpSeq) + ".jfr");
        target.jcmd("JFR.dump name=" + RECORDING_NAME + " filename=" + snapshot.toAbsolutePath());
        try (RecordingFile file = new RecordingFile(snapshot)) {
            Instant maxSeen = watermark;
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                Instant start = event.getStartTime();
                if (start.isAfter(maxSeen)) {
                    maxSeen = start;
                }
                boolean isGcLifecycle = event.getEventType().getName().endsWith("GarbageCollection");
                if (start.isAfter(watermark) || isGcLifecycle) {
                    // GC lifecycle events pass the watermark filter too and are
                    // deduped inside Metrics: an event is written when the GC
                    // completes, which can be after its start time was passed.
                    metrics.onEvent(event);
                }
            }
            watermark = maxSeen;
        } finally {
            Files.deleteIfExists(snapshot);
        }
    }

    /** Newest event timestamp processed so far — the "now" of the target's event clock. */
    public Instant watermark() {
        return watermark;
    }

    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        try {
            target.jcmd("JFR.stop name=" + RECORDING_NAME);
        } catch (Exception ignored) {
            // target may already be gone; shutdown continues regardless
        }
    }

    @Override
    public void close() {
        stop();
        try {
            Files.deleteIfExists(dumpDir);
        } catch (IOException ignored) {
            // temp dir cleanup is best-effort
        }
    }

    private static String firstLine(String s) {
        return s.lines().map(String::trim).filter(l -> !l.isEmpty()).findFirst().orElse("");
    }
}

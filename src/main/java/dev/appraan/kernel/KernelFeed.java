package dev.appraan.kernel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Holds the freshest kernel snapshot available to Appraan, from either of
 * the appraanagent handoff paths:
 *
 * <ul>
 *   <li><b>push</b> (primary) — the agent POSTs each snapshot to
 *       {@code /api/kernel/ingest}; stored here with its receive time.</li>
 *   <li><b>file</b> (fallback) — with {@code --kernel-file}, the agent's
 *       {@code --out} file is polled; a changed mtime triggers a re-read.
 *       The writer rewrites atomically, and a missing/half-written file is
 *       tolerated by keeping the last good snapshot.</li>
 * </ul>
 *
 * Push wins when both exist (it is fresher by construction); otherwise the
 * more recently timestamped snapshot wins. A snapshot older than
 * {@link #STALE_AFTER_SECONDS} seconds is reported as stale.
 *
 * <p>This class also composes the platform-aware {@link Hint} the Kernel
 * panel renders when there is no (or no fresh) data — including, on
 * Windows, the exact elevated {@code appraanagent} command for this
 * target PID and UI port. On Linux the hint never mentions appraanagent
 * (the agent is Windows/ETW-only); the panel shows /proc data instead.
 */
public final class KernelFeed {

    /** A snapshot older than this (seconds) is labelled stale. */
    public static final long STALE_AFTER_SECONDS = 15;

    /** Where the freshest snapshot came from: {@code "push"}, {@code "file"} or {@code "none"}. */
    public record View(KernelData data, String source, long ageSeconds, boolean stale, Hint hint) {
    }

    /**
     * Server-composed guidance for the Kernel panel. {@code state} is one of
     * {@code live}, {@code stale}, {@code none}, {@code waiting-file};
     * {@code message}/{@code command} are {@code null} when not applicable.
     */
    public record Hint(String state, String message, String command) {
    }

    private final Path kernelFile;   // nullable
    private final boolean windows;
    private final String pid;
    private volatile int uiPort;

    private KernelData pushed;
    private Instant pushedAt;
    private KernelData fromFile;
    private Instant fileStamp;       // the file's last-modified time
    private long lastMtimeMillis = -1;

    public KernelFeed(Path kernelFile, boolean windows, String pid, int configuredPort) {
        this.kernelFile = kernelFile;
        this.windows = windows;
        this.pid = pid;
        this.uiPort = configuredPort;
    }

    /** The port the UI actually bound (differs from the configured one when 0 was requested). */
    public void setUiPort(int port) {
        if (port > 0) {
            this.uiPort = port;
        }
    }

    public boolean windows() {
        return windows;
    }

    /** Stores a pushed snapshot (from {@code POST /api/kernel/ingest}). */
    public synchronized void ingest(KernelData data, Instant receivedAt) {
        this.pushed = data;
        this.pushedAt = receivedAt;
    }

    /**
     * Re-reads the {@code --kernel-file} file if its mtime changed since the
     * last successful read. Never throws: a missing file, a torn read or a
     * malformed document simply keeps the last good snapshot.
     */
    public synchronized void refreshFile() {
        if (kernelFile == null) {
            return;
        }
        try {
            long mtime = Files.getLastModifiedTime(kernelFile).toMillis();
            if (mtime == lastMtimeMillis) {
                return;
            }
            KernelData parsed = KernelData.parse(Files.readString(kernelFile));
            fromFile = parsed;
            fileStamp = Instant.ofEpochMilli(mtime);
            lastMtimeMillis = mtime;
        } catch (IOException | RuntimeException e) {
            // Missing / half-written / malformed: keep last good snapshot.
        }
    }

    public View view() {
        return viewAt(Instant.now());
    }

    /** The current view as of {@code now} (parameterised for tests). */
    public synchronized View viewAt(Instant now) {
        KernelData data = null;
        String source = "none";
        Instant stamp = null;
        if (pushed != null && (fileStamp == null || !pushedAt.isBefore(fileStamp))) {
            data = pushed;
            source = "push";
            stamp = pushedAt;
        } else if (fromFile != null) {
            data = fromFile;
            source = "file";
            stamp = fileStamp;
        }
        if (data == null) {
            return new View(null, "none", -1, false, noDataHint());
        }
        long age = Math.max(0, now.getEpochSecond() - stamp.getEpochSecond());
        boolean stale = age > STALE_AFTER_SECONDS;
        return new View(data, source, age, stale,
                stale ? staleHint(age, source) : new Hint("live", null, null));
    }

    private Hint noDataHint() {
        if (kernelFile != null) {
            String message = windows
                    ? "Waiting for kernel data in file " + kernelFile
                            + " — start appraanagent in an elevated (Administrator) shell to produce it."
                    : "Waiting for kernel data in file " + kernelFile + "…";
            return new Hint("waiting-file", message, windows ? outCommand() : null);
        }
        if (windows) {
            return new Hint("none",
                    "No kernel data yet — please run appraanagent in an elevated "
                            + "(Administrator) shell, watching this same PID (" + pid + ").",
                    pushCommand());
        }
        return new Hint("none",
                "No kernel collector feed on this platform: kernel metrics are "
                        + "produced by the Windows/ETW sidecar; on Linux this panel "
                        + "shows the target's /proc process I/O counters instead, "
                        + "when available.",
                null);
    }

    private Hint staleHint(long ageSeconds, String source) {
        if (!windows) {
            return new Hint("stale",
                    "Kernel data is stale — last update " + ageSeconds + "s ago.", null);
        }
        return new Hint("stale",
                "appraanagent seems to have stopped — showing last data from " + ageSeconds
                        + "s ago. Restart appraanagent in an elevated shell to resume "
                        + "live kernel data.",
                "file".equals(source) ? outCommand() : pushCommand());
    }

    private String pushCommand() {
        return "appraanagent " + pid + " --push http://127.0.0.1:" + uiPort
                + "/api/kernel/ingest --interval 2";
    }

    private String outCommand() {
        return "appraanagent " + pid + " --out " + kernelFile + " --interval 2";
    }
}

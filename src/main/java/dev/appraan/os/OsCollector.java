package dev.appraan.os;

import java.util.Locale;

/**
 * OS-level metrics seam. All platform-specific code lives behind this interface;
 * the core (CLI, discovery, JFR, metrics, UI) is identical on Linux and Windows.
 *
 * <p>Implementations are selected by {@code os.name}. A metric that a platform
 * cannot provide returns an empty array / {@code -1} rather than a fabricated
 * value, and callers render it as "n/a".
 */
public interface OsCollector {

    /** Human-readable name of this collector, for the status output. */
    String name();

    /**
     * Per-core CPU busy percentages (0–100), one entry per logical core, or an
     * empty array when the platform cannot supply per-core figures.
     * Implementations may return a single whole-system value where that is the
     * best the platform offers (documented per implementation).
     */
    double[] perCoreCpuBusyPercent();

    static OsCollector create() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return new WindowsOsCollector();
        }
        if (os.contains("nux") || os.contains("nix")) {
            return new LinuxOsCollector();
        }
        return new UnsupportedOsCollector(os);
    }
}

package dev.appraan.os;

/** Fallback for operating systems without a collector yet: every metric is "n/a". */
final class UnsupportedOsCollector implements OsCollector {

    private final String osName;

    UnsupportedOsCollector(String osName) {
        this.osName = osName;
    }

    @Override
    public String name() {
        return "unsupported (" + osName + ")";
    }

    @Override
    public double[] perCoreCpuBusyPercent() {
        return new double[0];
    }
}

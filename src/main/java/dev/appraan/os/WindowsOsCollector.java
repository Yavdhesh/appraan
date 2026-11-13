package dev.appraan.os;

import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;

/**
 * Windows collector. Milestone 1 approximation: the JDK's
 * {@link OperatingSystemMXBean#getCpuLoad()} exposes only a whole-system CPU
 * figure, so {@link #perCoreCpuBusyPercent()} returns a single-element array
 * (system-wide busy%) rather than true per-core values.
 *
 * <p>Later milestones add ETW / eBPF-for-Windows collectors behind this same SPI
 * for true per-core and per-process figures, with no change to core code or UI.
 * This class compiles on every OS and is only selected when {@code os.name}
 * indicates Windows.
 */
final class WindowsOsCollector implements OsCollector {

    @Override
    public String name() {
        return "windows-jdk-mxbean (system-wide approximation)";
    }

    @Override
    public double[] perCoreCpuBusyPercent() {
        try {
            OperatingSystemMXBean bean =
                    ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
            double load = bean.getCpuLoad(); // 0.0..1.0, negative when unavailable
            if (load < 0) {
                return new double[0];
            }
            return new double[]{load * 100.0};
        } catch (Exception e) {
            return new double[0];
        }
    }
}

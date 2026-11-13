package dev.appraan.discover;

import dev.appraan.attach.TargetVm;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything Appraan learns about a target JVM before recording starts,
 * gathered through jcmd diagnostic commands over the Attach API.
 */
public record TargetInfo(
        String pid,
        String versionLine,
        String javaVersion,
        String gcCollector,
        Map<String, String> flagsOfInterest,
        String commandLine,
        String heapInfo) {

    /** Flags worth surfacing in the summary: GC selection + heap sizing. */
    private static final String[] INTERESTING_FLAGS = {
            "UseG1GC", "UseZGC", "UseShenandoahGC", "UseParallelGC", "UseSerialGC", "UseEpsilonGC",
            "InitialHeapSize", "MaxHeapSize", "MinHeapSize", "NewSize", "MaxNewSize",
            "G1HeapRegionSize", "MaxTenuringThreshold", "UseCompressedOops", "UseStringDeduplication"
    };

    private static final Pattern FLAG_LINE =
            Pattern.compile("^\\s*\\S+\\s+(\\S+)\\s*=\\s*(\\S+)");
    private static final Pattern VERSION =
            Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)(?:[_+](\\S+))?");

    public static TargetInfo discover(TargetVm target) throws IOException {
        String versionOut = target.jcmd("VM.version");
        String flagsOut = target.jcmd("VM.flags");
        String heapOut = safeJcmd(target, "GC.heap_info");
        String cmdLineOut = safeJcmd(target, "VM.command_line");

        String versionLine = versionOut.lines()
                .map(String::trim)
                .filter(l -> !l.isEmpty())
                .findFirst()
                .orElse("(unknown version)");

        String javaVersion = versionLine;
        Matcher vm = VERSION.matcher(versionLine);
        if (vm.find()) {
            javaVersion = vm.group(1) + "." + vm.group(2) + "." + vm.group(3);
        }

        Map<String, String> flags = new LinkedHashMap<>();
        for (String line : flagsOut.split("\\R")) {
            Matcher m = FLAG_LINE.matcher(line);
            if (m.find()) {
                for (String name : INTERESTING_FLAGS) {
                    if (name.equals(m.group(1))) {
                        flags.put(name, m.group(2));
                    }
                }
            }
        }

        String gc = determineGc(flags, heapOut);
        String commandLine = extractCommandLine(cmdLineOut);
        return new TargetInfo(target.pid(), versionLine, javaVersion, gc, flags, commandLine, heapOut.trim());
    }

    private static String safeJcmd(TargetVm target, String command) {
        try {
            return target.jcmd(command);
        } catch (Exception e) {
            return "(unavailable: " + command + " -> " + e.getMessage() + ")";
        }
    }

    private static String determineGc(Map<String, String> flags, String heapInfo) {
        if ("true".equals(flags.get("UseG1GC"))) return "G1";
        if ("true".equals(flags.get("UseZGC"))) return "ZGC";
        if ("true".equals(flags.get("UseShenandoahGC"))) return "Shenandoah";
        if ("true".equals(flags.get("UseParallelGC"))) return "Parallel";
        if ("true".equals(flags.get("UseSerialGC"))) return "Serial";
        if ("true".equals(flags.get("UseEpsilonGC"))) return "Epsilon (no-op)";
        String h = heapInfo.toLowerCase();
        if (h.contains("garbage-first")) return "G1";
        if (h.contains("parallel")) return "Parallel";
        return "unknown (no GC selection flag reported)";
    }

    private static String extractCommandLine(String cmdLineOut) {
        // VM.command_line prints sections; the interesting bits are the JVM args + java command.
        StringBuilder sb = new StringBuilder();
        for (String line : cmdLineOut.split("\\R")) {
            String t = line.trim();
            if (t.startsWith("jvm_args:") || t.startsWith("java_command:")
                    || t.startsWith("java_class_path") || t.startsWith("Launcher Type:")) {
                if (!sb.isEmpty()) sb.append("\n");
                sb.append(t);
            }
        }
        return sb.isEmpty() ? "(unavailable)" : sb.toString();
    }

    private static String humanBytes(String raw) {
        try {
            long b = Long.parseLong(raw);
            if (b >= (1L << 30)) return (b / (1L << 30)) + " GiB (" + b + ")";
            if (b >= (1L << 20)) return (b / (1L << 20)) + " MiB (" + b + ")";
            if (b >= (1L << 10)) return (b / (1L << 10)) + " KiB (" + b + ")";
            return b + " B";
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Target JVM\n");
        sb.append("  pid:            ").append(pid).append('\n');
        sb.append("  version:        ").append(versionLine).append('\n');
        sb.append("  GC collector:   ").append(gcCollector).append('\n');
        for (Map.Entry<String, String> e : flagsOfInterest.entrySet()) {
            String v = e.getValue();
            if (e.getKey().endsWith("Size") || e.getKey().equals("G1HeapRegionSize")) {
                v = humanBytes(v);
            }
            sb.append("  flag ").append(e.getKey()).append(" = ").append(v).append('\n');
        }
        sb.append("  command line:\n");
        for (String line : commandLine.split("\\R")) {
            sb.append("    ").append(line).append('\n');
        }
        sb.append("  heap info:\n");
        for (String line : heapInfo.split("\\R")) {
            if (!line.isBlank()) {
                sb.append("    ").append(line.trim()).append('\n');
            }
        }
        return sb.toString();
    }
}

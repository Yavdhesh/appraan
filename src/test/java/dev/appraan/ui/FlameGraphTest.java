package dev.appraan.ui;

import java.util.List;
import java.util.Map;

/**
 * Manual test for {@link FlameGraph}: known collapsed stacks must produce a
 * tree whose root value is the sample total, whose widest path follows the
 * hot stack, and SVG output with properly escaped frame names.
 * Run via {@code ./run-tests.sh}.
 */
public final class FlameGraphTest {

    private static int checks;

    public static void main(String[] args) {
        List<Map.Entry<String, Long>> stacks = List.of(
                Map.entry("java.lang.Thread.run;dev.appraan.demo.LoadApp.burnCpu;"
                        + "dev.appraan.demo.LoadApp.isPrime", 90L),
                Map.entry("java.lang.Thread.run;dev.appraan.demo.LoadApp.churnAllocations;"
                        + "java.util.ArrayList.clear", 10L));

        check(FlameGraph.totalSamples(stacks) == 100, "total samples = 100");

        String svg = FlameGraph.svg(stacks, 1200);
        check(svg.contains("CPU flame graph — 100 samples"), "svg header carries total");
        check(svg.contains("dev.appraan.demo.LoadApp.isPrime — 90 samples (90.0%)"),
                "hot leaf title with count and percent");
        check(svg.contains("java.util.ArrayList.clear — 10 samples (10.0%)"),
                "cold leaf title with count and percent");
        // The hot path's frames must be the widest: isPrime rect width 90% of 1200 = 1080.
        check(svg.contains("width=\"1079.5\""), "hot leaf rect spans 90% of the width");

        String hostile = FlameGraph.svg(List.of(Map.entry("a<b>&\"c\"", 5L)), 600);
        check(hostile.contains("a&lt;b&gt;&amp;&quot;c&quot;"), "frame names XML-escaped in svg");
        check(!hostile.contains("<b>"), "no raw markup injected");

        check(FlameGraph.svg(List.of(), 600).contains("0 samples"), "empty input renders");

        check(FlameGraph.widestPath(stacks).equals(List.of(
                        "java.lang.Thread.run", "dev.appraan.demo.LoadApp.burnCpu",
                        "dev.appraan.demo.LoadApp.isPrime")),
                "widest path follows the hot stack down to isPrime");
        check(FlameGraph.widestPath(List.of()).isEmpty(), "widest path of nothing is empty");

        System.out.println("FlameGraphTest: all " + checks + " checks passed");
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) {
            throw new AssertionError("FlameGraphTest FAILED: " + label);
        }
    }
}

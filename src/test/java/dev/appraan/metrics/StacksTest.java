package dev.appraan.metrics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manual test for {@link Stacks}: known sample sets must produce known
 * collapsed stacks and counts. Run via {@code ./run-tests.sh}; exits non-zero
 * (AssertionError) on the first failure.
 */
public final class StacksTest {

    private static int checks;

    public static void main(String[] args) {
        collapseBasics();
        collapseAggregatesKnownSamples();
        frameNaming();
        leafExtraction();
        System.out.println("StacksTest: all " + checks + " checks passed");
    }

    private static void collapseBasics() {
        check(Stacks.collapseLeafToRoot(null) == null, "null input -> null");
        check(Stacks.collapseLeafToRoot(List.of()) == null, "empty input -> null");
        check("a.Main.main".equals(Stacks.collapseLeafToRoot(List.of("a.Main.main"))),
                "single frame stays as-is");
        // JFR order is leaf-first; collapsed form must be root-first.
        List<String> leafToRoot = List.of("com.acme.Hot.loop", "com.acme.Work.run", "java.lang.Thread.run");
        check("java.lang.Thread.run;com.acme.Work.run;com.acme.Hot.loop"
                        .equals(Stacks.collapseLeafToRoot(leafToRoot)),
                "leaf-first input reversed to root-first");
        List<String> withHole = new ArrayList<>(List.of("com.acme.Hot.loop"));
        withHole.add(null);
        check((Stacks.UNKNOWN_FRAME + ";com.acme.Hot.loop").equals(Stacks.collapseLeafToRoot(withHole)),
                "null frame name becomes " + Stacks.UNKNOWN_FRAME);
    }

    private static void collapseAggregatesKnownSamples() {
        // A known multiset of sampled stacks, as JFR would deliver them (leaf first).
        List<List<String>> samples = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            samples.add(List.of("dev.appraan.demo.LoadApp.isPrime",
                    "dev.appraan.demo.LoadApp.burnCpu",
                    "java.lang.Thread.run"));
        }
        for (int i = 0; i < 3; i++) {
            samples.add(List.of("java.util.ArrayList.clear",
                    "dev.appraan.demo.LoadApp.churnAllocations",
                    "java.lang.Thread.run"));
        }
        samples.add(List.of("dev.appraan.demo.LoadApp.isPrime",
                "dev.appraan.demo.LoadApp.burnCpu",
                "java.lang.Thread.run"));

        Map<String, Long> counts = new HashMap<>();
        for (List<String> sample : samples) {
            counts.merge(Stacks.collapseLeafToRoot(sample), 1L, Long::sum);
        }
        check(counts.size() == 2, "two distinct collapsed stacks, got " + counts);
        check(counts.get("java.lang.Thread.run;dev.appraan.demo.LoadApp.burnCpu;"
                + "dev.appraan.demo.LoadApp.isPrime") == 8L, "hot path counted 8x");
        check(counts.get("java.lang.Thread.run;dev.appraan.demo.LoadApp.churnAllocations;"
                + "java.util.ArrayList.clear") == 3L, "allocator path counted 3x");

        // Leaf tallies derived from collapsed stacks must match direct leaf counting.
        Map<String, Long> leaves = new HashMap<>();
        counts.forEach((stack, n) -> leaves.merge(Stacks.leafOf(stack), n, Long::sum));
        check(leaves.get("dev.appraan.demo.LoadApp.isPrime") == 8L, "leaf tally: isPrime=8");
        check(leaves.get("java.util.ArrayList.clear") == 3L, "leaf tally: ArrayList.clear=3");
    }

    private static void frameNaming() {
        check("com.acme.Foo.bar".equals(Stacks.frameName("com.acme.Foo", "bar")), "type.method");
        check("com.acme.Foo".equals(Stacks.frameName("com.acme.Foo", null)), "missing method -> type only");
        check(Stacks.UNKNOWN_FRAME.equals(Stacks.frameName(null, "bar")), "missing type -> unknown");
        check(Stacks.UNKNOWN_FRAME.equals(Stacks.frameName("  ", "bar")), "blank type -> unknown");
    }

    private static void leafExtraction() {
        check("c.C.m".equals(Stacks.leafOf("a.A.m;b.B.m;c.C.m")), "leaf of multi-frame stack");
        check("only.Frame".equals(Stacks.leafOf("only.Frame")), "leaf of single-frame stack");
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) {
            throw new AssertionError("StacksTest FAILED: " + label);
        }
    }
}

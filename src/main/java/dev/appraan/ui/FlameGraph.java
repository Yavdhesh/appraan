package dev.appraan.ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side SVG flame graph rendered from collapsed stacks.
 *
 * <p>Layout follows the classic flame-graph geometry: the root spans the
 * full width, each frame's width is proportional to the samples passing
 * through it, children tile their parent's width, and depth grows downward
 * from the root (icicle orientation is a CSS flip away; we keep the
 * traditional upward-growing form by drawing the root at the bottom).
 * Frames narrower than {@link #MIN_WIDTH_PX} are pruned — they carry no
 * readable information at this scale. Rendering is a pure function of the
 * collapsed-stack list, so it is unit-testable and snapshot-able.
 */
public final class FlameGraph {

    public static final int DEFAULT_WIDTH = 1200;
    private static final int FRAME_HEIGHT = 16;
    private static final double MIN_WIDTH_PX = 0.15;
    private static final int TOP_PAD = 24;

    private FlameGraph() {
    }

    private static final class Node {
        final String name;
        long value;
        final Map<String, Node> children = new LinkedHashMap<>();

        Node(String name) {
            this.name = name;
        }
    }

    /** Builds the frame tree from collapsed stacks; exposed for tests/proof checks. */
    static Node buildTree(List<Map.Entry<String, Long>> collapsedStacks) {
        Node root = new Node("all");
        for (Map.Entry<String, Long> e : collapsedStacks) {
            long count = e.getValue();
            root.value += count;
            Node node = root;
            for (String frame : e.getKey().split(";")) {
                node = node.children.computeIfAbsent(frame, Node::new);
                node.value += count;
            }
        }
        return root;
    }

    /** Total samples in the tree (sum over all collapsed stacks). */
    public static long totalSamples(List<Map.Entry<String, Long>> collapsedStacks) {
        long total = 0;
        for (Map.Entry<String, Long> e : collapsedStacks) {
            total += e.getValue();
        }
        return total;
    }

    private static int maxDepth(Node node) {
        int max = 0;
        for (Node child : node.children.values()) {
            max = Math.max(max, 1 + maxDepth(child));
        }
        return max;
    }

    /** Renders the flame graph as a standalone SVG document fragment. */
    public static String svg(List<Map.Entry<String, Long>> collapsedStacks, int width) {
        Node root = buildTree(collapsedStacks);
        long total = root.value;
        int depth = maxDepth(root);
        int height = TOP_PAD + (depth + 1) * FRAME_HEIGHT + 4;
        StringBuilder sb = new StringBuilder();
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(width)
                .append("\" height=\"").append(height)
                .append("\" viewBox=\"0 0 ").append(width).append(' ').append(height)
                .append("\" font-family=\"ui-monospace,Menlo,Consolas,monospace\" font-size=\"11\">\n");
        sb.append("<rect width=\"100%\" height=\"100%\" fill=\"#11151c\"/>\n");
        sb.append("<text x=\"6\" y=\"16\" fill=\"#e8ecf1\" font-size=\"13\">CPU flame graph — ")
                .append(total).append(" samples</text>\n");
        if (total > 0) {
            // Root at the bottom; children grow upward.
            int rootY = TOP_PAD + depth * FRAME_HEIGHT;
            render(sb, root, 0, rootY, width, total, depth);
        }
        sb.append("</svg>\n");
        return sb.toString();
    }

    private static void render(StringBuilder sb, Node node, double x, int y, double w,
                               long total, int levelsUp) {
        sb.append("<g><title>").append(esc(node.name)).append(" — ").append(node.value)
                .append(" samples (").append(String.format(java.util.Locale.ROOT, "%.1f", node.value * 100.0 / total))
                .append("%)</title>");
        sb.append("<rect x=\"").append(fmt(x)).append("\" y=\"").append(y)
                .append("\" width=\"").append(fmt(Math.max(w - 0.5, 0.5)))
                .append("\" height=\"").append(FRAME_HEIGHT - 1)
                .append("\" fill=\"").append(color(node.name)).append("\" rx=\"1\"/>");
        if (w > 32) {
            String label = node.name;
            int maxChars = (int) (w / 6.5);
            if (label.length() > maxChars && maxChars > 3) {
                label = label.substring(0, maxChars - 1) + "…";
            }
            sb.append("<text x=\"").append(fmt(x + 3)).append("\" y=\"").append(y + 12)
                    .append("\" fill=\"#10131a\">").append(esc(label)).append("</text>");
        }
        sb.append("</g>\n");
        double childX = x;
        for (Node child : node.children.values()) {
            double childW = w * child.value / (double) node.value;
            if (childW >= MIN_WIDTH_PX) {
                render(sb, child, childX, y - FRAME_HEIGHT, childW, total, levelsUp - 1);
            }
            childX += childW;
        }
    }

    /** Deterministic warm palette derived from the frame name (flame-graph tradition). */
    private static String color(String name) {
        int h = name.hashCode();
        int hue = 8 + Math.floorMod(h, 44);          // 8..51: red -> orange -> amber
        int sat = 78 + Math.floorMod(h >> 8, 18);    // 78..95%
        int light = 52 + Math.floorMod(h >> 16, 14); // 52..65%
        return "hsl(" + hue + "," + sat + "%," + light + "%)";
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    /** XML escaping for frame names in text/title content. */
    static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}

package dev.appraan.metrics;

import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;

import java.util.List;

/**
 * Collapsed-stack helpers shared by the console top-leaf table, the JSON API
 * and the flame graph, so all three always agree on frame naming.
 *
 * <p>A "collapsed stack" is one string per distinct call path, root frame
 * first, frames joined by {@code ';'} — the de-facto format popularised by
 * Brendan Gregg's FlameGraph tools:
 * {@code java.lang.Thread.run;com.example.Work;com.example.Hot}.
 *
 * <p>The collapsing itself is a pure function over frame-name lists so it can
 * be unit-tested without a live JFR recording.
 */
public final class Stacks {

    /** Placeholder for frames whose method could not be resolved. */
    public static final String UNKNOWN_FRAME = "unknown";

    private Stacks() {
    }

    /** Display name of one frame: {@code fully.qualified.Type.method}. */
    public static String frameName(String typeName, String methodName) {
        if (typeName == null || typeName.isBlank()) {
            return UNKNOWN_FRAME;
        }
        if (methodName == null || methodName.isBlank()) {
            return typeName;
        }
        return typeName + "." + methodName;
    }

    /** Display name of a JFR frame; {@link #UNKNOWN_FRAME} when unresolvable. */
    public static String frameName(RecordedFrame frame) {
        if (frame == null) {
            return UNKNOWN_FRAME;
        }
        RecordedMethod method = frame.getMethod();
        if (method == null || method.getType() == null) {
            return UNKNOWN_FRAME;
        }
        return frameName(method.getType().getName(), method.getName());
    }

    /**
     * Collapses frame names given leaf-first (the order JFR returns them:
     * index 0 is the executing leaf) into root-first {@code ';'}-joined form.
     *
     * @return the collapsed stack, or {@code null} for an empty/null input
     */
    public static String collapseLeafToRoot(List<String> leafToRootNames) {
        if (leafToRootNames == null || leafToRootNames.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = leafToRootNames.size() - 1; i >= 0; i--) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            String name = leafToRootNames.get(i);
            sb.append(name == null || name.isBlank() ? UNKNOWN_FRAME : name);
        }
        return sb.toString();
    }

    /** The leaf (last, executing) frame of a collapsed stack. */
    public static String leafOf(String collapsedStack) {
        int idx = collapsedStack.lastIndexOf(';');
        return idx < 0 ? collapsedStack : collapsedStack.substring(idx + 1);
    }
}

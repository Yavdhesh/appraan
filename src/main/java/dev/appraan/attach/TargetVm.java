package dev.appraan.attach;

import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import sun.tools.attach.HotSpotVirtualMachine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * A live Attach-API connection to a target JVM.
 *
 * <p>Uses the local attach mechanism (Unix domain socket / named pipe), never TCP,
 * so it behaves identically on Linux and Windows and is unaffected by network policy.
 * {@code executeJCmd} is the same channel the {@code jcmd} tool uses.
 */
public final class TargetVm implements AutoCloseable {

    private final String pid;
    private final VirtualMachine vm;
    private final HotSpotVirtualMachine hotSpotVm;

    private TargetVm(String pid, VirtualMachine vm) {
        this.pid = pid;
        this.vm = vm;
        this.hotSpotVm = (HotSpotVirtualMachine) vm;
    }

    /** Thrown when a PID cannot be attached to, with a user-facing explanation. */
    public static final class AttachFailure extends Exception {
        public AttachFailure(String message) {
            super(message);
        }
    }

    public static TargetVm attach(String pid) throws AttachFailure {
        final VirtualMachine vm;
        try {
            vm = VirtualMachine.attach(pid);
        } catch (AttachNotSupportedException e) {
            throw new AttachFailure("PID " + pid + " is not a JVM Appraan can attach to "
                    + "(the process did not respond to the attach protocol). "
                    + "Original error: " + e.getMessage());
        } catch (IOException e) {
            String msg = String.valueOf(e.getMessage());
            String lower = msg.toLowerCase();
            if (lower.contains("no such process") || lower.contains("non existent")
                    || lower.contains("not found")) {
                throw new AttachFailure("No JVM process with PID " + pid
                        + " exists (the PID is wrong, or the process already exited).");
            }
            throw new AttachFailure("Could not attach to PID " + pid + ": " + msg
                    + " (is it a JVM started by the same user, without -XX:+DisableAttachMechanism?)");
        }
        if (!(vm instanceof HotSpotVirtualMachine)) {
            try {
                vm.detach();
            } catch (IOException ignored) {
                // nothing useful to do while already failing
            }
            throw new AttachFailure("PID " + pid + " is a JVM but not a HotSpot-based one; "
                    + "Appraan needs the HotSpot attach protocol (OpenJDK/Temurin/Oracle JDK).");
        }
        return new TargetVm(pid, vm);
    }

    public String pid() {
        return pid;
    }

    /** Runs a jcmd command in the target VM and returns its full text output. */
    public String jcmd(String command) throws IOException {
        try (InputStream in = hotSpotVm.executeJCmd(command)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Override
    public void close() {
        try {
            vm.detach();
        } catch (IOException ignored) {
            // detach is best-effort on shutdown
        }
    }
}

package dev.appraan.os;

/**
 * Per-process I/O counters for the target process, as read from
 * {@code /proc/<pid>/io} on Linux.
 *
 * <p><b>Semantics differ from the Windows ETW feed:</b> these counters are
 * cumulative since process start (not per-window), and {@code readBytes} /
 * {@code writeBytes} count bytes the process caused to be sent to the
 * storage layer (including page-cache traffic), per proc(5). They are the
 * honest Linux parity subset for the Kernel panel today; the UI labels
 * them as cumulative /proc counters wherever they appear.
 */
public record ProcessIo(long syscr, long syscw, long readBytes, long writeBytes,
                        long cancelledWriteBytes) {
}

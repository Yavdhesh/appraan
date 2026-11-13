package dev.appraan.ui;

import dev.appraan.kernel.KernelData;
import dev.appraan.kernel.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

/**
 * Manual test for the ingest body's gzip path ({@link UiServer#decodeBody}):
 * the appraanagent pushes gzip-compressed JSON (max compression); Appraan
 * must inflate it transparently, accept plain JSON identically, and reject
 * corrupt gzip — the gzipped fixture and the plain fixture must land as
 * the SAME parsed snapshot.
 * Run via {@code ./run-tests.sh}; exits non-zero (AssertionError) on failure.
 */
public final class KernelIngestTest {

    private static int checks;

    private static final String DOC = "{\"pid\":18772,\"tEpochMs\":1794596400000,"
            + "\"windowSeconds\":40.0,\"ops\":{\"categories\":[{\"name\":\"File\","
            + "\"count\":4040,\"latOps\":4040,\"avgUs\":139.0,\"maxUs\":17270.1,"
            + "\"kinds\":{\"Write\":1172},\"hist\":{\"<10us\":2706}}],\"diagnostics\":"
            + "{\"pendingFileOps\":0,\"unmatchedCompletions\":22,\"duplicateStarts\":0,"
            + "\"evictedPending\":0}},\"tcp\":{\"closedTotal\":262,\"txBytes\":8384,"
            + "\"rxBytes\":8384,\"stillOpen\":0,\"unmatchedDisconnects\":0,\"recent\":[]},"
            + "\"offCpu\":{\"onCpuMs\":584.0,\"offCpuMs\":138427.2,\"offCpuPct\":99.6,"
            + "\"episodes\":4113,\"maxEpisodeMs\":5671.6,\"reasons\":[],\"threads\":[]}}";

    public static void main(String[] args) throws Exception {
        plainPassesThrough();
        gzipRoundTripsToSameSnapshot();
        corruptGzipRejected();
        System.out.println("KernelIngestTest: all " + checks + " checks passed");
    }

    private static void plainPassesThrough() throws Exception {
        byte[] body = DOC.getBytes(StandardCharsets.UTF_8);
        check(UiServer.decodeBody(body, null) == body, "no encoding header -> body untouched");
        check(UiServer.decodeBody(body, "identity") == body, "identity -> body untouched");
    }

    private static void gzipRoundTripsToSameSnapshot() throws Exception {
        byte[] plain = DOC.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(plain);
        }
        byte[] decoded = UiServer.decodeBody(bos.toByteArray(), "gzip");
        check(java.util.Arrays.equals(decoded, plain), "gzip inflates byte-identical");
        KernelData fromGzip = KernelData.parse(new String(decoded, StandardCharsets.UTF_8));
        KernelData fromPlain = KernelData.parse(DOC);
        check(fromGzip.equals(fromPlain), "gzipped fixture == plain fixture as snapshots");
        check(fromGzip.category("File").hist().get("<10us") == 2706,
                "hist survives the gzip path");
    }

    private static void corruptGzipRejected() throws Exception {
        checks++;
        try {
            UiServer.decodeBody(new byte[]{0x1f, 0x0b, 1, 2, 3}, "gzip");
            throw new AssertionError("KernelIngestTest FAILED: corrupt gzip accepted");
        } catch (IOException expected) {
            // -> the handler answers 400, previous snapshot kept.
        }
        // Valid gzip around invalid JSON inflates fine; the parser rejects it.
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write("{oops".getBytes(StandardCharsets.UTF_8));
        }
        byte[] decoded = UiServer.decodeBody(bos.toByteArray(), "gzip");
        checks++;
        try {
            KernelData.parse(new String(decoded, StandardCharsets.UTF_8));
            throw new AssertionError("KernelIngestTest FAILED: invalid JSON accepted");
        } catch (JsonParser.ParseException expected) {
            // -> the handler answers 400, previous snapshot kept.
        }
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) {
            throw new AssertionError("KernelIngestTest FAILED: " + label);
        }
    }
}

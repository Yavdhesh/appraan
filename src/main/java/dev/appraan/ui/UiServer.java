package dev.appraan.ui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.appraan.kernel.KernelData;
import dev.appraan.kernel.KernelFeed;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;

/**
 * The embedded web UI: a JDK {@link HttpServer} bound to the loopback
 * interface, serving the dashboard, the flame-graph page and the JSON API.
 * All rendering is delegated to the pure functions in {@link Pages},
 * {@link JsonApi} and {@link FlameGraph} over an immutable {@link Snapshot};
 * this class is only routing + bytes.
 *
 * <p>Loopback-only by design in Milestone 2: Appraan is a local observer,
 * and the UI exposes live internals of the observed process.
 */
public final class UiServer implements AutoCloseable {

    /** Ingest bodies are a few KB; anything larger is not a kernel snapshot. */
    private static final int MAX_INGEST_BYTES = 1 << 20;

    private final HttpServer server;
    private final Supplier<Snapshot> snapshots;
    private final KernelFeed kernelFeed;   // nullable in tests/tools

    public UiServer(Supplier<Snapshot> snapshots, int port) throws IOException {
        this(snapshots, port, null);
    }

    public UiServer(Supplier<Snapshot> snapshots, int port, KernelFeed kernelFeed) throws IOException {
        this.snapshots = snapshots;
        this.kernelFeed = kernelFeed;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/", this::route);
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread t = new Thread(runnable, "appraan-ui");
            t.setDaemon(true);
            return t;
        }));
    }

    public void start() {
        server.start();
    }

    /** The port actually bound (relevant when constructed with port 0). */
    public int port() {
        return server.getAddress().getPort();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    private void route(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            switch (path) {
                case "/", "/index.html" -> respond(exchange, 200, "text/html; charset=utf-8",
                        Pages.dashboardHtml());
                case "/flame" -> respond(exchange, 200, "text/html; charset=utf-8",
                        Pages.flamePage(snapshots.get()));
                case "/api/summary" -> respond(exchange, 200, "application/json",
                        JsonApi.summary(snapshots.get()));
                case "/api/gc" -> respond(exchange, 200, "application/json",
                        JsonApi.gc(snapshots.get()));
                case "/api/cpu" -> respond(exchange, 200, "application/json",
                        JsonApi.cpu(snapshots.get()));
                case "/api/cores" -> respond(exchange, 200, "application/json",
                        JsonApi.cores(snapshots.get()));
                case "/api/stacks" -> respond(exchange, 200, "application/json",
                        JsonApi.stacks(snapshots.get()));
                case "/kernel" -> respond(exchange, 200, "text/html; charset=utf-8",
                        Pages.kernelPage(kernelView(), snapshots.get()));
                case "/api/kernel" -> respond(exchange, 200, "application/json",
                        JsonApi.kernel(kernelView(), snapshots.get().procIo()));
                case "/api/kernel/ingest" -> ingest(exchange);
                default -> respond(exchange, 404, "application/json",
                        Json.obj().put("error", "not found").put("path", path).toString());
            }
        } finally {
            exchange.close();
        }
    }

    /** The freshest kernel view, refreshing the file feed first when in file mode. */
    private KernelFeed.View kernelView() {
        if (kernelFeed == null) {
            return new KernelFeed.View(null, "none", -1, false,
                    new KernelFeed.Hint("none", "Kernel feed is not configured.", null));
        }
        kernelFeed.refreshFile();
        return kernelFeed.view();
    }

    /**
     * {@code POST /api/kernel/ingest} — the appraanagent push handoff.
     * Loopback-only like the whole UI (same trust boundary: any local
     * process could post a snapshot — acceptable for a local developer
     * tool, and documented as such). A malformed body gets a 400 and the
     * previous snapshot is kept.
     */
    private void ingest(HttpExchange exchange) throws IOException {
        if (kernelFeed == null) {
            respond(exchange, 404, "application/json",
                    Json.obj().put("error", "kernel feed is not configured").toString());
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "application/json",
                    Json.obj().put("error", "POST required").toString());
            return;
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_INGEST_BYTES + 1);
        if (body.length > MAX_INGEST_BYTES) {
            respond(exchange, 400, "application/json",
                    Json.obj().put("error", "kernel snapshot too large").toString());
            return;
        }
        byte[] plain;
        try {
            plain = decodeBody(body, exchange.getRequestHeaders().getFirst("Content-Encoding"));
        } catch (IOException e) {
            respond(exchange, 400, "application/json",
                    Json.obj().put("error", "malformed gzip body").toString());
            return;
        }
        try {
            KernelData data = KernelData.parse(new String(plain, StandardCharsets.UTF_8));
            kernelFeed.ingest(data, Instant.now());
            respond(exchange, 200, "application/json",
                    Json.obj().put("ok", true).put("pid", data.pid()).toString());
        } catch (RuntimeException e) {
            respond(exchange, 400, "application/json",
                    Json.obj().put("error", "malformed kernel snapshot").toString());
        }
    }

    /**
     * Transparently inflates a {@code Content-Encoding: gzip} body (the
     * agent's push format); any other encoding passes through unchanged.
     * The inflated size is capped like the raw body. Package-private for
     * the unit test.
     */
    static byte[] decodeBody(byte[] body, String contentEncoding) throws IOException {
        if (contentEncoding == null
                || !contentEncoding.toLowerCase(Locale.ROOT).contains("gzip")) {
            return body;
        }
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(body))) {
            byte[] plain = gz.readNBytes(MAX_INGEST_BYTES + 1);
            if (plain.length > MAX_INGEST_BYTES) {
                throw new IOException("inflated kernel snapshot too large");
            }
            return plain;
        }
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

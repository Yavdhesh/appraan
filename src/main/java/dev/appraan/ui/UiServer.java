package dev.appraan.ui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import dev.appraan.kernel.KernelCrypto;
import dev.appraan.kernel.KernelData;
import dev.appraan.kernel.KernelFeed;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
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
    private final byte[] kernelKey;        // nullable: shared-key ingest mode
    private final boolean tls;

    public UiServer(Supplier<Snapshot> snapshots, int port) throws IOException {
        this(snapshots, port, null, null, null, null);
    }

    public UiServer(Supplier<Snapshot> snapshots, int port, KernelFeed kernelFeed) throws IOException {
        this(snapshots, port, kernelFeed, null, null, null);
    }

    /**
     * @param kernelKey           when non-null, ingest is fail-closed: only
     *                            AES-256-GCM envelopes from the agent are
     *                            accepted (see {@link KernelCrypto})
     * @param httpsKeystore       when non-null, the whole UI runs HTTPS
     *                            with this PKCS12 keystore
     * @param httpsKeystorePassword password for the keystore
     */
    public UiServer(Supplier<Snapshot> snapshots, int port, KernelFeed kernelFeed,
                    byte[] kernelKey, Path httpsKeystore, char[] httpsKeystorePassword)
            throws IOException {
        this.snapshots = snapshots;
        this.kernelFeed = kernelFeed;
        this.kernelKey = kernelKey;
        this.tls = httpsKeystore != null;
        InetSocketAddress address = new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
        if (tls) {
            HttpsServer https = HttpsServer.create(address, 0);
            https.setHttpsConfigurator(new HttpsConfigurator(loadSslContext(httpsKeystore, httpsKeystorePassword)));
            this.server = https;
        } else {
            this.server = HttpServer.create(address, 0);
        }
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
        return (tls ? "https://127.0.0.1:" : "http://127.0.0.1:") + port();
    }

    private static SSLContext loadSslContext(Path keystorePath, char[] password) throws IOException {
        try (InputStream in = Files.newInputStream(keystorePath)) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(in, password);
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, password);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);
            return ctx;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("cannot load HTTPS keystore " + keystorePath + ": " + e.getMessage(), e);
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            switch (path) {
                case "/", "/index.html" -> respond(exchange, 200, "text/html; charset=utf-8",
                        Pages.dashboardHtml(snapshots.get()));
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
        String contentType = contentTypeOf(exchange);
        byte[] plain;
        if (kernelKey != null) {
            // Fail-closed shared-key mode: ONLY a valid AES-256-GCM
            // envelope (application/octet-stream) is accepted — plain or
            // merely-gzipped posts are rejected like any other forgery.
            if (!"application/octet-stream".equals(contentType)) {
                respond(exchange, 400, "application/json", Json.obj()
                        .put("error", "encrypted envelope required (application/octet-stream)")
                        .toString());
                return;
            }
            try {
                KernelCrypto.Decrypted decrypted = KernelCrypto.decrypt(kernelKey, body);
                // The envelope's flags byte says whether the sealed
                // payload was gzipped (agent --gzip) or is plain JSON.
                plain = decrypted.gzipped()
                        ? decodeBody(decrypted.payload(), "gzip")
                        : decrypted.payload();
            } catch (RuntimeException | IOException e) {
                respond(exchange, 400, "application/json",
                        Json.obj().put("error", "undecryptable kernel snapshot").toString());
                return;
            }
        } else {
            if ("application/octet-stream".equals(contentType)) {
                respond(exchange, 400, "application/json", Json.obj()
                        .put("error", "encrypted feed not configured (no --kernel-key-file)")
                        .toString());
                return;
            }
            try {
                plain = decodeBody(body, exchange.getRequestHeaders().getFirst("Content-Encoding"));
            } catch (IOException e) {
                respond(exchange, 400, "application/json",
                        Json.obj().put("error", "malformed gzip body").toString());
                return;
            }
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

    /** The request's Content-Type, lowercased, parameters stripped (or null). */
    private static String contentTypeOf(HttpExchange exchange) {
        String ct = exchange.getRequestHeaders().getFirst("Content-Type");
        if (ct == null) {
            return null;
        }
        int semi = ct.indexOf(';');
        return (semi >= 0 ? ct.substring(0, semi) : ct).trim().toLowerCase(Locale.ROOT);
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

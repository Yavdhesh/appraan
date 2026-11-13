package dev.appraan.ui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

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

    private final HttpServer server;
    private final Supplier<Snapshot> snapshots;

    public UiServer(Supplier<Snapshot> snapshots, int port) throws IOException {
        this.snapshots = snapshots;
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
                default -> respond(exchange, 404, "application/json",
                        Json.obj().put("error", "not found").put("path", path).toString());
            }
        } finally {
            exchange.close();
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

package com.sheltr.ingestion.infrastructure;

import com.sheltr.ingestion.domain.RawContent;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HtmlPageConnectorTest {

    private static final String PAGE = """
            <!doctype html>
            <html><body>
              <nav>Navigation to drop</nav>
              <main>
                <h1>Flood guidance</h1>
                <p>Move   valuables upstairs.</p>
              </main>
              <footer>Footer to drop</footer>
            </body></html>
            """;
    private static final String EXTRACTED = "Flood guidance\nMove valuables upstairs.";

    private HttpServer server;
    private ExecutorService serverExecutor;
    private final CountDownLatch release = new CountDownLatch(1);
    private final List<String> requestedPaths = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        serverExecutor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(serverExecutor);

        server.createContext("/page", exchange -> respond(exchange, 200, PAGE, null));
        server.createContext("/moved", exchange -> respond(exchange, 301, "", "/page"));
        server.createContext("/offsite", exchange -> respond(exchange, 301, "",
                "http://localhost:" + port() + "/page"));
        // Exactly three redirects, then the page: allowed.
        server.createContext("/hop1", exchange -> respond(exchange, 301, "", "/hop2"));
        server.createContext("/hop2", exchange -> respond(exchange, 302, "", "/hop3"));
        server.createContext("/hop3", exchange -> respond(exchange, 307, "", "/page"));
        // Four redirects in total: the fourth 3xx (from /r4) must be refused, so /page is never requested.
        server.createContext("/r1", exchange -> respond(exchange, 301, "", "/r2"));
        server.createContext("/r2", exchange -> respond(exchange, 302, "", "/r3"));
        server.createContext("/r3", exchange -> respond(exchange, 307, "", "/r4"));
        server.createContext("/r4", exchange -> respond(exchange, 308, "", "/page"));
        server.createContext("/missing", exchange -> respond(exchange, 404, "not here", null));
        server.createContext("/slow", exchange -> {
            try {
                release.await(70, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, PAGE, null);
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        release.countDown();
        server.stop(0);
        serverExecutor.shutdownNow();
    }

    private int port() {
        return server.getAddress().getPort();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port() + path;
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body, String location)
            throws IOException {
        requestedPaths.add(exchange.getRequestURI().getPath());
        if (location != null) {
            exchange.getResponseHeaders().add("Location", location);
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    /** Allows only URIs whose host is 127.0.0.1 on this server, mirroring an allowlist of one host. */
    private com.sheltr.ingestion.domain.SourcePolicy allowOnlyLoopback() {
        String prefix = "http://127.0.0.1:" + port();
        return uri -> uri.startsWith(prefix + "/");
    }

    private HtmlPageConnector connector() {
        return new HtmlPageConnector(allowOnlyLoopback(), Duration.ofSeconds(2), Duration.ofSeconds(5));
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Test
    void returnsExtractedTextAndChecksumOfThatText() throws Exception {
        RawContent content = connector().fetch(url("/page"));

        byte[] expected = EXTRACTED.getBytes(StandardCharsets.UTF_8);
        assertThat(content.bytes()).isEqualTo(expected);
        assertThat(content.checksum().hex()).isEqualTo(sha256Hex(expected));
    }

    @Test
    void followsRedirectToSameHost() throws Exception {
        RawContent content = connector().fetch(url("/moved"));

        assertThat(new String(content.bytes(), StandardCharsets.UTF_8)).isEqualTo(EXTRACTED);
        assertThat(requestedPaths).containsExactly("/moved", "/page");
    }

    @Test
    void followsExactlyThreeRedirects() throws Exception {
        RawContent content = connector().fetch(url("/hop1"));

        assertThat(new String(content.bytes(), StandardCharsets.UTF_8)).isEqualTo(EXTRACTED);
        assertThat(requestedPaths).containsExactly("/hop1", "/hop2", "/hop3", "/page");
    }

    @Test
    void refusesRedirectToHostNotOnAllowlistWithoutSecondRequest() {
        assertThatThrownBy(() -> connector().fetch(url("/offsite")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Redirect")
                .hasMessageContaining("localhost");

        assertThat(requestedPaths).containsExactly("/offsite");
    }

    @Test
    void refusesAfterThreeRedirects() {
        assertThatThrownBy(() -> connector().fetch(url("/r1")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Too many redirects");

        assertThat(requestedPaths).containsExactly("/r1", "/r2", "/r3", "/r4");
    }

    @Test
    void failsOnNotFound() {
        assertThatThrownBy(() -> connector().fetch(url("/missing")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("HTTP 404");
    }

    @Test
    void failsWhenRequestExceedsRequestTimeout() {
        HtmlPageConnector connector = new HtmlPageConnector(allowOnlyLoopback(), Duration.ofSeconds(1),
                Duration.ofSeconds(1));

        long startNanos = System.nanoTime();
        assertThatThrownBy(() -> connector.fetch(url("/slow")))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(java.net.http.HttpTimeoutException.class);
        long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startNanos);

        assertThat(elapsedSeconds).isLessThan(65);
    }
}

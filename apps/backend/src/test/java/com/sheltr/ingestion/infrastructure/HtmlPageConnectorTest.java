package com.sheltr.ingestion.infrastructure;

import com.sheltr.ingestion.config.HostAllowlistSourcePolicy;
import com.sheltr.ingestion.config.IngestionProperties;
import com.sheltr.ingestion.domain.RawContent;
import com.sheltr.ingestion.domain.SourcePolicy;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HtmlPageConnectorTest {

    private static final String UTF8_HTML = "text/html; charset=utf-8";

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

    private static final String EMPTY_MAIN_PAGE = "<html><body><main><nav>Menu only</nav></main></body></html>";

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
        // A 302 with no Location header.
        server.createContext("/noloc", exchange -> respond(exchange, 302, "", null));
        server.createContext("/missing", exchange -> respond(exchange, 404, "not here", null));
        server.createContext("/empty", exchange -> respond(exchange, 200, EMPTY_MAIN_PAGE, null));
        server.createContext("/chrome-in-main", exchange -> respond(exchange, 200,
                resource("chrome-inside-main.html"), null, UTF8_HTML));
        server.createContext("/chrome-in-article", exchange -> respond(exchange, 200,
                resource("chrome-inside-article.html"), null, UTF8_HTML));
        // ISO-8859-1 bytes with the charset declared in the header.
        server.createContext("/latin1", exchange -> respond(exchange, 200,
                "<html><body><main><p>Café écoles advice.</p></main></body></html>"
                        .getBytes(StandardCharsets.ISO_8859_1), null, "text/html; charset=ISO-8859-1"));
        // UTF-8 bytes with a charset name that does not exist.
        server.createContext("/bogus-charset", exchange -> respond(exchange, 200,
                "<html><body><main><p>Café advice.</p></main></body></html>"
                        .getBytes(StandardCharsets.UTF_8), null, "text/html; charset=x-not-a-real-charset"));
        // UTF-8 bytes with no charset declared.
        server.createContext("/no-charset", exchange -> respond(exchange, 200,
                "<html><body><main><p>Café advice.</p></main></body></html>"
                        .getBytes(StandardCharsets.UTF_8), null, "text/html"));
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

    private void respond(HttpExchange exchange, int status, String body, String location) throws IOException {
        respond(exchange, status, body.getBytes(StandardCharsets.UTF_8), location, UTF8_HTML);
    }

    private void respond(HttpExchange exchange, int status, byte[] bytes, String location, String contentType)
            throws IOException {
        requestedPaths.add(exchange.getRequestURI().getPath());
        if (location != null) {
            exchange.getResponseHeaders().add("Location", location);
        }
        if (contentType != null) {
            exchange.getResponseHeaders().add("Content-Type", contentType);
        }
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private static byte[] resource(String name) throws IOException {
        try (var in = HtmlPageConnectorTest.class.getResourceAsStream("/html/" + name)) {
            assertThat(in).as("fixture %s", name).isNotNull();
            return in.readAllBytes();
        }
    }

    /** Allows only URIs whose host is 127.0.0.1 on this server, mirroring an allowlist of one host. */
    private SourcePolicy allowOnlyLoopback() {
        String prefix = "http://127.0.0.1:" + port();
        return uri -> uri.startsWith(prefix + "/");
    }

    /** The production policy, configured with the given hosts. */
    private static SourcePolicy realPolicy(String... hosts) {
        return new HostAllowlistSourcePolicy(new IngestionProperties(List.of(hosts), null, null));
    }

    private HtmlPageConnector connector() {
        return new HtmlPageConnector(allowOnlyLoopback(), Duration.ofSeconds(2), Duration.ofSeconds(5));
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(bytes)) {
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
    void failsWhenRedirectHasNoLocationHeaderAfterOneRequest() {
        assertThatThrownBy(() -> connector().fetch(url("/noloc")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Redirect without a Location header");

        assertThat(requestedPaths).containsExactly("/noloc");
    }

    @Test
    void initialUrlDeniedByRealPolicyMakesNoRequest() {
        // The real policy requires https, so this plain-http loopback URL is denied before any request.
        HtmlPageConnector connector = new HtmlPageConnector(realPolicy("127.0.0.1"),
                Duration.ofSeconds(2), Duration.ofSeconds(5));

        assertThatThrownBy(() -> connector.fetch(url("/page")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Source host not allowed: 127.0.0.1");

        assertThat(requestedPaths).isEmpty();
    }

    @Test
    void initialUrlOnUnlistedHostIsRefusedByRealPolicy() {
        HtmlPageConnector connector = new HtmlPageConnector(realPolicy("allowed-host"),
                Duration.ofSeconds(2), Duration.ofSeconds(5));

        assertThatThrownBy(() -> connector.fetch("https://evil.example/page"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Source host not allowed: evil.example");
    }

    @Test
    void redirectTargetOnUnlistedHostIsRejectedByRealPolicy() {
        HtmlPageConnector connector = new HtmlPageConnector(realPolicy("allowed-host"),
                Duration.ofSeconds(2), Duration.ofSeconds(5));

        assertThatThrownBy(() -> connector.checkRedirectTarget(URI.create("https://evil.example/page")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Redirect to a disallowed target")
                .hasMessageContaining("evil.example");
    }

    @Test
    void redirectTargetOnAnotherPortIsRejectedByRealPolicy() {
        HtmlPageConnector connector = new HtmlPageConnector(realPolicy("allowed-host"),
                Duration.ofSeconds(2), Duration.ofSeconds(5));

        assertThatThrownBy(() -> connector.checkRedirectTarget(URI.create("https://allowed-host:8443/page")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Redirect to a disallowed target");
    }

    @Test
    void redirectTargetOverPlainHttpIsRejectedByRealPolicy() {
        HtmlPageConnector connector = new HtmlPageConnector(realPolicy("allowed-host"),
                Duration.ofSeconds(2), Duration.ofSeconds(5));

        assertThatThrownBy(() -> connector.checkRedirectTarget(URI.create("http://allowed-host/page")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Redirect to a disallowed target");
    }

    @Test
    void redirectTargetWithUserinfoIsRejectedByRealPolicyWithoutEchoingCredentials() {
        HtmlPageConnector connector = new HtmlPageConnector(realPolicy("allowed-host"),
                Duration.ofSeconds(2), Duration.ofSeconds(5));

        assertThatThrownBy(() -> connector.checkRedirectTarget(URI.create("https://user:secret@allowed-host/page")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Redirect to a disallowed target")
                .hasMessageContaining("must not contain credentials")
                .hasMessageNotContaining("secret");
    }

    @Test
    void redirectTargetOnAllowedHostIsAcceptedByRealPolicy() {
        HtmlPageConnector connector = new HtmlPageConnector(realPolicy("allowed-host"),
                Duration.ofSeconds(2), Duration.ofSeconds(5));

        assertThatCode(() -> connector.checkRedirectTarget(URI.create("https://allowed-host/guide/page")))
                .doesNotThrowAnyException();
    }

    @Test
    void failsOnNotFound() {
        assertThatThrownBy(() -> connector().fetch(url("/missing")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("HTTP 404");
    }

    @Test
    void emptyMainTextReturnsEmptyContentWithoutThrowing() throws Exception {
        RawContent content = connector().fetch(url("/empty"));

        assertThat(content.bytes()).isEmpty();
        assertThat(content.checksum().hex()).isEqualTo(sha256Hex(new byte[0]));
    }

    @Test
    void dropsCookieNavAndHeaderInsideMain() throws Exception {
        RawContent content = connector().fetch(url("/chrome-in-main"));

        String text = new String(content.bytes(), StandardCharsets.UTF_8);
        assertThat(text).contains("Prepare for floods").contains("Move valuables to an upper floor.");
        assertThat(text).contains("Article paragraph kept.");
        assertThat(text).doesNotContain("We use cookies to track you.");
        assertThat(text).doesNotContain("Accept all cookies.");
        assertThat(text).doesNotContain("Cookie settings.");
        assertThat(text).doesNotContain("Site header inside main.");
        assertThat(text).doesNotContain("Article header.");
        assertThat(text).doesNotContain("Main navigation links.");
        assertThat(text).doesNotContain("Related links.");
    }

    @Test
    void dropsCookieNavAndHeaderInsideArticleWhenNoMain() throws Exception {
        RawContent content = connector().fetch(url("/chrome-in-article"));

        String text = new String(content.bytes(), StandardCharsets.UTF_8);
        assertThat(text).contains("Winter storms").contains("Check the forecast before you travel.");
        assertThat(text).doesNotContain("We use cookies here.");
        assertThat(text).doesNotContain("Article site header.");
        assertThat(text).doesNotContain("Article navigation links.");
    }

    @Test
    void decodesBodyWithDeclaredIso88591Charset() throws Exception {
        RawContent content = connector().fetch(url("/latin1"));

        assertThat(new String(content.bytes(), StandardCharsets.UTF_8)).isEqualTo("Café écoles advice.");
    }

    @Test
    void fallsBackToUtf8ForUnknownDeclaredCharsetWithoutThrowing() throws Exception {
        RawContent content = connector().fetch(url("/bogus-charset"));

        assertThat(new String(content.bytes(), StandardCharsets.UTF_8)).isEqualTo("Café advice.");
    }

    @Test
    void decodesAsUtf8WhenNoCharsetDeclared() throws Exception {
        RawContent content = connector().fetch(url("/no-charset"));

        assertThat(new String(content.bytes(), StandardCharsets.UTF_8)).isEqualTo("Café advice.");
    }

    @Test
    void charsetOfReadsDeclaredNameAndFallsBackToUtf8() {
        assertThat(HtmlPageConnector.charsetOf(null)).isEqualTo(StandardCharsets.UTF_8);
        assertThat(HtmlPageConnector.charsetOf("text/html")).isEqualTo(StandardCharsets.UTF_8);
        assertThat(HtmlPageConnector.charsetOf("text/html; charset=\"ISO-8859-1\""))
                .isEqualTo(Charset.forName("ISO-8859-1"));
        assertThat(HtmlPageConnector.charsetOf("text/html;charset=utf-8")).isEqualTo(StandardCharsets.UTF_8);
        assertThat(HtmlPageConnector.charsetOf("text/html; charset=x-not-a-real-charset"))
                .isEqualTo(StandardCharsets.UTF_8);
        assertThat(HtmlPageConnector.charsetOf("text/html; charset=@@@")).isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    void failsWhenRequestExceedsRequestTimeout() {
        HtmlPageConnector connector = new HtmlPageConnector(allowOnlyLoopback(), Duration.ofSeconds(1),
                Duration.ofSeconds(1));

        long startNanos = System.nanoTime();
        assertThatThrownBy(() -> connector.fetch(url("/slow")))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(java.net.http.HttpTimeoutException.class);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        // The request timeout is 1 second; the server would hold the request for up to 70 seconds.
        assertThat(elapsedMillis).isLessThan(10_000);
    }
}

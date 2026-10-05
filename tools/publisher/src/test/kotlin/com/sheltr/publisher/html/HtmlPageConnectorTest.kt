package com.sheltr.publisher.html

import com.sheltr.publisher.fetch.HostAllowlistSourcePolicy
import com.sheltr.publisher.fetch.RawContent
import com.sheltr.publisher.fetch.SourcePolicy
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpTimeoutException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HtmlPageConnectorTest {

    private val utf8Html = "text/html; charset=utf-8"

    private val page = """
        <!doctype html>
        <html><body>
          <nav>Navigation to drop</nav>
          <main>
            <h1>Flood guidance</h1>
            <p>Move   valuables upstairs.</p>
          </main>
          <footer>Footer to drop</footer>
        </body></html>
    """.trimIndent()
    private val extracted = "Flood guidance\nMove valuables upstairs."

    private val emptyMainPage = "<html><body><main><nav>Menu only</nav></main></body></html>"

    private lateinit var server: HttpServer
    private lateinit var serverExecutor: ExecutorService
    private val release = CountDownLatch(1)
    private val requestedPaths = CopyOnWriteArrayList<String>()

    @BeforeTest
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        serverExecutor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable).apply { isDaemon = true }
        }
        server.executor = serverExecutor

        server.createContext("/page") { exchange -> respond(exchange, 200, page, null) }
        server.createContext("/moved") { exchange -> respond(exchange, 301, "", "/page") }
        server.createContext("/offsite") { exchange ->
            respond(exchange, 301, "", "http://localhost:${port()}/page")
        }
        // Exactly three redirects, then the page: allowed.
        server.createContext("/hop1") { exchange -> respond(exchange, 301, "", "/hop2") }
        server.createContext("/hop2") { exchange -> respond(exchange, 302, "", "/hop3") }
        server.createContext("/hop3") { exchange -> respond(exchange, 307, "", "/page") }
        // Four redirects in total: the fourth 3xx (from /r4) must be refused, so /page is never requested.
        server.createContext("/r1") { exchange -> respond(exchange, 301, "", "/r2") }
        server.createContext("/r2") { exchange -> respond(exchange, 302, "", "/r3") }
        server.createContext("/r3") { exchange -> respond(exchange, 307, "", "/r4") }
        server.createContext("/r4") { exchange -> respond(exchange, 308, "", "/page") }
        // A 302 with no Location header.
        server.createContext("/noloc") { exchange -> respond(exchange, 302, "", null) }
        server.createContext("/missing") { exchange -> respond(exchange, 404, "not here", null) }
        server.createContext("/empty") { exchange -> respond(exchange, 200, emptyMainPage, null) }
        server.createContext("/chrome-in-main") { exchange ->
            respond(exchange, 200, resource("chrome-inside-main.html"), null, utf8Html)
        }
        server.createContext("/chrome-in-article") { exchange ->
            respond(exchange, 200, resource("chrome-inside-article.html"), null, utf8Html)
        }
        // ISO-8859-1 bytes with the charset declared in the header.
        server.createContext("/latin1") { exchange ->
            val body = "<html><body><main><p>Café écoles advice.</p></main></body></html>"
            respond(exchange, 200, body.toByteArray(StandardCharsets.ISO_8859_1), null, "text/html; charset=ISO-8859-1")
        }
        // UTF-8 bytes with a charset name that does not exist.
        server.createContext("/bogus-charset") { exchange ->
            val body = "<html><body><main><p>Café advice.</p></main></body></html>"
            respond(exchange, 200, body.toByteArray(StandardCharsets.UTF_8), null, "text/html; charset=x-not-a-real-charset")
        }
        // UTF-8 bytes with no charset declared.
        server.createContext("/no-charset") { exchange ->
            val body = "<html><body><main><p>Café advice.</p></main></body></html>"
            respond(exchange, 200, body.toByteArray(StandardCharsets.UTF_8), null, "text/html")
        }
        server.createContext("/slow") { exchange ->
            try {
                release.await(70, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            respond(exchange, 200, page, null)
        }
        server.start()
    }

    @AfterTest
    fun stopServer() {
        release.countDown()
        server.stop(0)
        serverExecutor.shutdownNow()
    }

    private fun port(): Int = server.address.port

    private fun url(path: String): String = "http://127.0.0.1:${port()}$path"

    private fun respond(exchange: HttpExchange, status: Int, body: String, location: String?) {
        respond(exchange, status, body.toByteArray(StandardCharsets.UTF_8), location, utf8Html)
    }

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        bytes: ByteArray,
        location: String?,
        contentType: String?,
    ) {
        requestedPaths.add(exchange.requestURI.path)
        if (location != null) {
            exchange.responseHeaders.add("Location", location)
        }
        if (contentType != null) {
            exchange.responseHeaders.add("Content-Type", contentType)
        }
        exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) {
            exchange.responseBody.write(bytes)
        }
        exchange.close()
    }

    private fun resource(name: String): ByteArray {
        val stream = HtmlPageConnectorTest::class.java.getResourceAsStream("/html/$name")
        assertNotNull(stream, "fixture $name")
        return stream.use { it.readAllBytes() }
    }

    /** Allows only URIs whose host is 127.0.0.1 on this server, mirroring an allowlist of one host. */
    private fun allowOnlyLoopback(): SourcePolicy {
        val prefix = "http://127.0.0.1:${port()}"
        return SourcePolicy { uri -> uri != null && uri.startsWith("$prefix/") }
    }

    /** The production policy, configured with the given hosts. */
    private fun realPolicy(vararg hosts: String): SourcePolicy = HostAllowlistSourcePolicy(hosts.toList())

    private fun connector(): HtmlPageConnector =
        HtmlPageConnector(allowOnlyLoopback(), Duration.ofSeconds(2), Duration.ofSeconds(5))

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun text(content: RawContent): String = String(content.bytes, StandardCharsets.UTF_8)

    /** Runs [block], which must throw a RuntimeException whose message contains [fragment]. */
    private fun assertFailsWithMessage(fragment: String, block: () -> Unit): RuntimeException {
        val failure = assertFailsWith<RuntimeException>(block = block)
        assertContains(failure.message.orEmpty(), fragment)
        return failure
    }

    @Test
    fun returnsExtractedTextAndChecksumOfThatText() {
        val content = connector().fetch(url("/page"))

        val expected = extracted.toByteArray(StandardCharsets.UTF_8)
        assertEquals(expected.toList(), content.bytes.toList())
        assertEquals(sha256Hex(expected), content.checksum.hex)
    }

    @Test
    fun followsRedirectToSameHost() {
        val content = connector().fetch(url("/moved"))

        assertEquals(extracted, text(content))
        assertEquals(listOf("/moved", "/page"), requestedPaths.toList())
    }

    @Test
    fun followsExactlyThreeRedirects() {
        val content = connector().fetch(url("/hop1"))

        assertEquals(extracted, text(content))
        assertEquals(listOf("/hop1", "/hop2", "/hop3", "/page"), requestedPaths.toList())
    }

    @Test
    fun refusesRedirectToHostNotOnAllowlistWithoutSecondRequest() {
        val failure = assertFailsWithMessage("Redirect") { connector().fetch(url("/offsite")) }
        assertContains(failure.message.orEmpty(), "localhost")

        assertEquals(listOf("/offsite"), requestedPaths.toList())
    }

    @Test
    fun refusesAfterThreeRedirects() {
        assertFailsWithMessage("Too many redirects") { connector().fetch(url("/r1")) }

        assertEquals(listOf("/r1", "/r2", "/r3", "/r4"), requestedPaths.toList())
    }

    @Test
    fun failsWhenRedirectHasNoLocationHeaderAfterOneRequest() {
        assertFailsWithMessage("Redirect without a Location header") { connector().fetch(url("/noloc")) }

        assertEquals(listOf("/noloc"), requestedPaths.toList())
    }

    @Test
    fun initialUrlDeniedByRealPolicyMakesNoRequest() {
        // The real policy requires https, so this plain-http loopback URL is denied before any request.
        val connector = HtmlPageConnector(realPolicy("127.0.0.1"), Duration.ofSeconds(2), Duration.ofSeconds(5))

        assertFailsWithMessage("Source host not allowed: 127.0.0.1") { connector.fetch(url("/page")) }

        assertTrue(requestedPaths.isEmpty())
    }

    @Test
    fun initialUrlOnUnlistedHostIsRefusedByRealPolicy() {
        val connector = HtmlPageConnector(realPolicy("allowed-host"), Duration.ofSeconds(2), Duration.ofSeconds(5))

        assertFailsWithMessage("Source host not allowed: evil.example") {
            connector.fetch("https://evil.example/page")
        }
    }

    @Test
    fun redirectTargetOnUnlistedHostIsRejectedByRealPolicy() {
        val connector = HtmlPageConnector(realPolicy("allowed-host"), Duration.ofSeconds(2), Duration.ofSeconds(5))

        val failure = assertFailsWithMessage("Redirect to a disallowed target") {
            connector.checkRedirectTarget(URI.create("https://evil.example/page"))
        }
        assertContains(failure.message.orEmpty(), "evil.example")
    }

    @Test
    fun redirectTargetOnAnotherPortIsRejectedByRealPolicy() {
        val connector = HtmlPageConnector(realPolicy("allowed-host"), Duration.ofSeconds(2), Duration.ofSeconds(5))

        assertFailsWithMessage("Redirect to a disallowed target") {
            connector.checkRedirectTarget(URI.create("https://allowed-host:8443/page"))
        }
    }

    @Test
    fun redirectTargetOverPlainHttpIsRejectedByRealPolicy() {
        val connector = HtmlPageConnector(realPolicy("allowed-host"), Duration.ofSeconds(2), Duration.ofSeconds(5))

        assertFailsWithMessage("Redirect to a disallowed target") {
            connector.checkRedirectTarget(URI.create("http://allowed-host/page"))
        }
    }

    @Test
    fun redirectTargetWithUserinfoIsRejectedByRealPolicyWithoutEchoingCredentials() {
        val connector = HtmlPageConnector(realPolicy("allowed-host"), Duration.ofSeconds(2), Duration.ofSeconds(5))

        val failure = assertFailsWithMessage("Redirect to a disallowed target") {
            connector.checkRedirectTarget(URI.create("https://user:secret@allowed-host/page"))
        }
        assertContains(failure.message.orEmpty(), "must not contain credentials")
        assertFalse(failure.message.orEmpty().contains("secret"))
    }

    @Test
    fun redirectTargetOnAllowedHostIsAcceptedByRealPolicy() {
        val connector = HtmlPageConnector(realPolicy("allowed-host"), Duration.ofSeconds(2), Duration.ofSeconds(5))

        connector.checkRedirectTarget(URI.create("https://allowed-host/guide/page"))
    }

    @Test
    fun failsOnNotFound() {
        assertFailsWithMessage("HTTP 404") { connector().fetch(url("/missing")) }
    }

    @Test
    fun emptyMainTextReturnsEmptyContentWithoutThrowing() {
        val content = connector().fetch(url("/empty"))

        assertTrue(content.bytes.isEmpty())
        assertEquals(sha256Hex(ByteArray(0)), content.checksum.hex)
    }

    @Test
    fun dropsCookieNavAndHeaderInsideMain() {
        val content = connector().fetch(url("/chrome-in-main"))

        val text = text(content)
        assertContains(text, "Prepare for floods")
        assertContains(text, "Move valuables to an upper floor.")
        assertContains(text, "Article paragraph kept.")
        assertFalse(text.contains("We use cookies to track you."))
        assertFalse(text.contains("Accept all cookies."))
        assertFalse(text.contains("Cookie settings."))
        assertFalse(text.contains("Site header inside main."))
        assertFalse(text.contains("Article header."))
        assertFalse(text.contains("Main navigation links."))
        assertFalse(text.contains("Related links."))
    }

    @Test
    fun dropsCookieNavAndHeaderInsideArticleWhenNoMain() {
        val content = connector().fetch(url("/chrome-in-article"))

        val text = text(content)
        assertContains(text, "Winter storms")
        assertContains(text, "Check the forecast before you travel.")
        assertFalse(text.contains("We use cookies here."))
        assertFalse(text.contains("Article site header."))
        assertFalse(text.contains("Article navigation links."))
    }

    @Test
    fun decodesBodyWithDeclaredIso88591Charset() {
        val content = connector().fetch(url("/latin1"))

        assertEquals("Café écoles advice.", text(content))
    }

    @Test
    fun fallsBackToUtf8ForUnknownDeclaredCharsetWithoutThrowing() {
        val content = connector().fetch(url("/bogus-charset"))

        assertEquals("Café advice.", text(content))
    }

    @Test
    fun decodesAsUtf8WhenNoCharsetDeclared() {
        val content = connector().fetch(url("/no-charset"))

        assertEquals("Café advice.", text(content))
    }

    @Test
    fun charsetOfReadsDeclaredNameAndFallsBackToUtf8() {
        assertEquals(StandardCharsets.UTF_8, HtmlPageConnector.charsetOf(null))
        assertEquals(StandardCharsets.UTF_8, HtmlPageConnector.charsetOf("text/html"))
        assertEquals(Charset.forName("ISO-8859-1"), HtmlPageConnector.charsetOf("text/html; charset=\"ISO-8859-1\""))
        assertEquals(StandardCharsets.UTF_8, HtmlPageConnector.charsetOf("text/html;charset=utf-8"))
        assertEquals(StandardCharsets.UTF_8, HtmlPageConnector.charsetOf("text/html; charset=x-not-a-real-charset"))
        assertEquals(StandardCharsets.UTF_8, HtmlPageConnector.charsetOf("text/html; charset=@@@"))
    }

    @Test
    fun failsWhenRequestExceedsRequestTimeout() {
        val connector = HtmlPageConnector(allowOnlyLoopback(), Duration.ofSeconds(1), Duration.ofSeconds(1))

        val startNanos = System.nanoTime()
        val failure = assertFailsWith<RuntimeException> { connector.fetch(url("/slow")) }
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)

        assertIs<HttpTimeoutException>(failure.cause)
        // The request timeout is 1 second; the server would hold the request for up to 70 seconds.
        assertTrue(elapsedMillis < 10_000)
    }
}

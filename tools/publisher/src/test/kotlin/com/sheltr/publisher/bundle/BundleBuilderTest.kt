package com.sheltr.publisher.bundle

import com.sheltr.publisher.fetch.SourcePolicy
import com.sheltr.publisher.fetch.sha256Hex
import com.sheltr.publisher.html.HtmlPageConnector
import com.sheltr.publisher.html.MainContentExtractor
import com.sheltr.publisher.manifest.CorpusManifest
import com.sheltr.publisher.manifest.SourceType
import com.sheltr.publisher.pdf.HttpPdfConnector
import com.sheltr.publisher.signing.generateKeyPair
import com.sheltr.publisher.signing.verify
import com.sun.net.httpserver.HttpServer
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.PublicKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BundleBuilderTest {

    private val pdfBytes = buildPdf("Boil water for one minute.", "Evacuate immediately if instructed.")
    private val htmlPage = resource("/html/article-page.html")
    private val htmlChecksum = sha256Hex(
        MainContentExtractor().extract(String(htmlPage, StandardCharsets.UTF_8)).toByteArray(StandardCharsets.UTF_8),
    )
    private val pdfChecksum = sha256Hex(pdfBytes)

    private val keyPair = generateKeyPair()
    private val publicKey: PublicKey = keyPair.public

    private lateinit var server: HttpServer
    private lateinit var serverExecutor: ExecutorService
    private val requestedPaths = CopyOnWriteArrayList<String>()
    private lateinit var workDir: Path
    private lateinit var keyPath: Path

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        serverExecutor = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
        server.executor = serverExecutor
        server.createContext("/guide.pdf") { exchange ->
            requestedPaths.add(exchange.requestURI.path)
            exchange.responseHeaders.add("Content-Type", "application/pdf")
            exchange.sendResponseHeaders(200, pdfBytes.size.toLong())
            exchange.responseBody.write(pdfBytes)
            exchange.close()
        }
        server.createContext("/page") { exchange ->
            requestedPaths.add(exchange.requestURI.path)
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, htmlPage.size.toLong())
            exchange.responseBody.write(htmlPage)
            exchange.close()
        }
        server.start()

        workDir = createTempDirectory("bundle-test-")
        // The key lives outside the output path; the builder reads it only from this path.
        keyPath = workDir.resolve("signing-key.der")
        keyPath.writeBytes(keyPair.private.encoded)
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        serverExecutor.shutdownNow()
        workDir.toFile().deleteRecursively()
    }

    @Test
    fun buildsASignedBundleFromOnePdfAndOneHtmlEntry() {
        val out = workDir.resolve("bundle")
        val result = builder().build(manifest(entry(pdfUri(), "pdf", pdfChecksum), entry(htmlUri(), "html", htmlChecksum)), out, keyPath)

        assertTrue(result.failures.isEmpty(), "failures: ${result.failures}")
        assertEquals(setOf("bundle.json", "bundle.json.sig", "documents"), out.listDirectoryEntries().map { it.name }.toSet())

        val bundleBytes = out.resolve(BUNDLE_MANIFEST_FILE).readBytes()
        assertTrue(verify(publicKey, bundleBytes, out.resolve(BUNDLE_SIGNATURE_FILE).readBytes()))

        val bundle = BundleJson.decodeFromString(BundleManifest.serializer(), String(bundleBytes, StandardCharsets.UTF_8))
        assertEquals(BUNDLE_FORMAT_VERSION, bundle.formatVersion)
        assertEquals("2026-10-05T12:00:00Z", bundle.bundleVersion)
        assertEquals(2, bundle.documents.size)

        val pdf = bundle.documents.single { it.sourceUri == pdfUri() }
        assertEquals(documentId(pdfUri()), pdf.id)
        assertEquals(32, pdf.id.length)
        assertEquals(pdfChecksum, pdf.checksum)
        assertEquals("pdf", pdf.sourceType)
        assertEquals("Agency", pdf.agency)
        assertEquals("2024-03-15", pdf.publishedDate)
        assertEquals("CC-BY-4.0", pdf.licence)
        assertEquals("NO", pdf.country)
        assertEquals(listOf("FLOOD"), pdf.hazards)

        val html = bundle.documents.single { it.sourceUri == htmlUri() }
        assertEquals(htmlChecksum, html.checksum)
        assertEquals("html", html.sourceType)

        for (doc in bundle.documents) {
            val fileBytes = out.resolve(BUNDLE_DOCUMENTS_DIR).resolve("${doc.id}.json").readBytes()
            assertEquals(doc.fileChecksum, sha256Hex(fileBytes))
            val file = BundleJson.decodeFromString(DocumentFile.serializer(), String(fileBytes, StandardCharsets.UTF_8))
            assertEquals(doc.id, file.id)
            assertTrue(file.chunks.isNotEmpty())
            assertEquals(doc.chunks.map { it.id }, file.chunks.map { it.id })
            for ((ref, chunk) in doc.chunks.zip(file.chunks)) {
                assertEquals(ref.checksum, sha256Hex(chunk.content.toByteArray(StandardCharsets.UTF_8)))
            }
        }

        val pdfChunks = readDocument(out, pdf.id).chunks
        assertEquals(listOf(1, 2), pdfChunks.map { it.pageNumber })
        assertContains(pdfChunks[0].content, "Boil water for one minute.")
        val htmlChunks = readDocument(out, html.id).chunks
        assertTrue(htmlChunks.all { it.pageNumber == null })
        assertTrue(Files.readString(out.resolve(BUNDLE_DOCUMENTS_DIR).resolve("${html.id}.json")).contains("\"pageNumber\": null"))
    }

    @Test
    fun checksumMismatchFailsThatEntryOnly() {
        val out = workDir.resolve("bundle")
        val wrong = "0".repeat(64)
        val result = builder().build(manifest(entry(pdfUri(), "pdf", wrong), entry(htmlUri(), "html", htmlChecksum)), out, keyPath)

        assertEquals(1, result.failures.size)
        assertEquals(pdfUri(), result.failures[0].sourceUri)
        assertContains(result.failures[0].reason, "Checksum mismatch")
        assertEquals(listOf(htmlUri()), result.manifest.documents.map { it.sourceUri })
        assertFalse(Files.exists(out.resolve(BUNDLE_DOCUMENTS_DIR).resolve("${documentId(pdfUri())}.json")))
        assertTrue(verify(publicKey, out.resolve(BUNDLE_MANIFEST_FILE).readBytes(), out.resolve(BUNDLE_SIGNATURE_FILE).readBytes()))
    }

    @Test
    fun approvedChecksumIsComparedCaseInsensitively() {
        val out = workDir.resolve("bundle")
        val result = builder().build(manifest(entry(pdfUri(), "pdf", pdfChecksum.uppercase())), out, keyPath)
        assertTrue(result.failures.isEmpty())
    }

    @Test
    fun entryRefusedByPolicyFailsWithoutARequest() {
        val out = workDir.resolve("bundle")
        val blocked = "https://not-allowed.example/guide.pdf"
        val result = builder().build(manifest(entry(blocked, "pdf", pdfChecksum), entry(htmlUri(), "html", htmlChecksum)), out, keyPath)

        assertEquals(listOf(blocked), result.failures.map { it.sourceUri })
        assertEquals(listOf("/page"), requestedPaths.toList())
    }

    @Test
    fun tamperedBundleJsonFailsVerification() {
        val out = workDir.resolve("bundle")
        builder().build(manifest(entry(pdfUri(), "pdf", pdfChecksum)), out, keyPath)
        val signature = out.resolve(BUNDLE_SIGNATURE_FILE).readBytes()
        val original = out.resolve(BUNDLE_MANIFEST_FILE).readBytes()
        assertTrue(verify(publicKey, original, signature))

        val tampered = String(original, StandardCharsets.UTF_8).replace("Flood guide", "Fl00d guide").toByteArray(StandardCharsets.UTF_8)
        assertFalse(tampered.contentEquals(original))
        out.resolve(BUNDLE_MANIFEST_FILE).writeBytes(tampered)

        assertFalse(verify(publicKey, out.resolve(BUNDLE_MANIFEST_FILE).readBytes(), signature))
        assertFalse(verify(generateKeyPair().public, original, signature))
    }

    @Test
    fun unreadableKeyLeavesNoOutput() {
        val out = workDir.resolve("bundle")
        val badKey = workDir.resolve("bad-key.der")
        badKey.writeBytes(byteArrayOf(1, 2, 3))

        assertFailsWith<BundleBuildException> {
            builder().build(manifest(entry(pdfUri(), "pdf", pdfChecksum)), out, badKey)
        }
        assertFailsWith<BundleBuildException> {
            builder().build(manifest(entry(pdfUri(), "pdf", pdfChecksum)), out, workDir.resolve("missing-key.der"))
        }
        assertNoOutput(out)
    }

    @Test
    fun buildWithNoSuccessfulEntryLeavesNoOutput() {
        val out = workDir.resolve("bundle")
        val error = assertFailsWith<BundleBuildException> {
            builder().build(manifest(entry(pdfUri(), "pdf", "0".repeat(64)), entry(htmlUri(), "html", "f".repeat(64))), out, keyPath)
        }
        assertEquals(2, error.failures.size)
        assertNoOutput(out)
    }

    @Test
    fun existingOutputIsNotOverwritten() {
        val out = workDir.resolve("bundle")
        Files.createDirectory(out)
        Files.writeString(out.resolve("keep.txt"), "previous")

        assertFailsWith<BundleBuildException> {
            builder().build(manifest(entry(pdfUri(), "pdf", pdfChecksum)), out, keyPath)
        }
        assertEquals(listOf("keep.txt"), out.listDirectoryEntries().map { it.name })
        assertEquals(setOf("bundle", "signing-key.der"), workDir.listDirectoryEntries().map { it.name }.toSet())
    }

    @Test
    fun documentIdsAreStableAcrossBuilds() {
        val input = manifest(entry(pdfUri(), "pdf", pdfChecksum), entry(htmlUri(), "html", htmlChecksum))
        val first = builder().build(input, workDir.resolve("first"), keyPath).manifest
        val second = builder(Instant.parse("2026-10-06T08:30:00Z")).build(input, workDir.resolve("second"), keyPath).manifest

        assertNotEquals(first.bundleVersion, second.bundleVersion)
        assertEquals(first.documents.map { it.id }, second.documents.map { it.id })
        assertEquals(first.documents.map { it.chunks }, second.documents.map { it.chunks })
        assertEquals(first.documents.map { it.fileChecksum }, second.documents.map { it.fileChecksum })
        assertEquals(documentId(pdfUri()), first.documents[0].id)
        // Fixed vector: the first 32 hex characters of SHA-256("https://example.org/a.pdf").
        assertEquals(
            sha256Hex("https://example.org/a.pdf".toByteArray(StandardCharsets.UTF_8)).substring(0, 32),
            documentId("https://example.org/a.pdf"),
        )
        assertEquals(documentId("https://example.org/a.pdf"), documentId("https://example.org/a.pdf"))
        assertNotEquals(documentId("https://example.org/a.pdf"), documentId("https://example.org/b.pdf"))
    }

    @Test
    fun globalDocumentHasNullCountry() {
        val out = workDir.resolve("bundle")
        val result = builder().build(manifest(entry(htmlUri(), "html", htmlChecksum, country = "GLOBAL")), out, keyPath)
        assertNull(result.manifest.documents.single().country)
        assertTrue(Files.readString(out.resolve(BUNDLE_MANIFEST_FILE)).contains("\"country\": null"))
    }

    @Test
    fun pdfConnectorDoesNotFollowRedirects() {
        server.createContext("/moved.pdf") { exchange ->
            requestedPaths.add(exchange.requestURI.path)
            exchange.responseHeaders.add("Location", "/guide.pdf")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        val connector = HttpPdfConnector(loopbackOnly(), Duration.ofSeconds(2), Duration.ofSeconds(5))
        val error = assertFailsWith<RuntimeException> { connector.fetch(url("/moved.pdf")) }
        assertContains(error.message.orEmpty(), "HTTP 302")
        assertEquals(listOf("/moved.pdf"), requestedPaths.toList())
    }

    private fun assertNoOutput(out: Path) {
        assertFalse(Files.exists(out), "output directory must not exist")
        // No staging directory is left beside it either.
        assertEquals(
            emptyList(),
            workDir.listDirectoryEntries().map { it.name }.filter { it.startsWith(".bundle") || it == "bundle" },
        )
    }

    private fun readDocument(out: Path, id: String): DocumentFile = BundleJson.decodeFromString(
        DocumentFile.serializer(),
        Files.readString(out.resolve(BUNDLE_DOCUMENTS_DIR).resolve("$id.json")),
    )

    private fun builder(now: Instant = Instant.parse("2026-10-05T12:00:00.123Z")): BundleBuilder {
        val policy = loopbackOnly()
        return BundleBuilder(
            connectors = mapOf(
                SourceType.PDF to HttpPdfConnector(policy, Duration.ofSeconds(2), Duration.ofSeconds(5)),
                SourceType.HTML to HtmlPageConnector(policy, Duration.ofSeconds(2), Duration.ofSeconds(5)),
            ),
            policy = policy,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
    }

    /** Stands in for the https allowlist: only this loopback server is allowed. */
    private fun loopbackOnly(): SourcePolicy {
        val prefix = "http://127.0.0.1:${server.address.port}/"
        return SourcePolicy { uri -> uri != null && uri.startsWith(prefix) }
    }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"
    private fun pdfUri() = url("/guide.pdf")
    private fun htmlUri() = url("/page")

    private fun entry(uri: String, type: String, checksum: String, country: String = "NO"): String = """
        |  - source_uri: "$uri"
        |    title: "${if (type == "pdf") "Flood guide" else "Storm page"}"
        |    source_agency: "Agency"
        |    hazards: [FLOOD]
        |    published_date: 2024-03-15
        |    license: "CC-BY-4.0"
        |    country: "$country"
        |    source_type: $type
        |    checksum: "$checksum"
    """.trimMargin()

    private fun manifest(vararg entries: String): CorpusManifest {
        val yaml = "documents:\n" + entries.joinToString("\n")
        return CorpusManifest.parse(yaml.byteInputStream())
    }

    private fun resource(name: String): ByteArray {
        val stream = BundleBuilderTest::class.java.getResourceAsStream(name)
        assertNotNull(stream, "fixture $name")
        return stream.use { it.readAllBytes() }
    }

    private fun buildPdf(vararg pages: String): ByteArray {
        PDDocument().use { document ->
            for (text in pages) {
                val page = PDPage()
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    stream.beginText()
                    stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                    stream.newLineAtOffset(50f, 700f)
                    stream.showText(text)
                    stream.endText()
                }
            }
            val out = ByteArrayOutputStream()
            document.save(out)
            return out.toByteArray()
        }
    }
}

package com.sheltr.shared.sync

import com.sheltr.shared.bundle.BUNDLE_FORMAT_VERSION
import com.sheltr.shared.bundle.BundleChunkRef
import com.sheltr.shared.bundle.BundleDocument
import com.sheltr.shared.bundle.BundleJson
import com.sheltr.shared.bundle.BundleManifest
import com.sheltr.shared.bundle.DocumentChunk
import com.sheltr.shared.bundle.DocumentFile
import com.sheltr.shared.bundle.PublisherFixture
import com.sheltr.shared.bundle.sha256Hex
import com.sheltr.shared.signing.SignedBundleFixture
import com.sheltr.shared.store.ContentStore
import com.sheltr.shared.store.JdbcStoreDriverFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.Signature
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readBytes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncEngineTest {

    private lateinit var dir: Path
    private lateinit var store: ContentStore

    /** Paths requested from the mock server, in order. */
    private val requests = mutableListOf<String>()

    /** What the mock server serves, by path under the base URL. */
    private val served = mutableMapOf<String, ByteArray>()

    /** Paths whose request fails mid-transfer. */
    private val broken = mutableSetOf<String>()

    private var now = 1_000L

    private val http = HttpClient(
        MockEngine { request ->
            val path = request.url.encodedPath.removePrefix("/bundle/")
            requests += path
            if (path in broken) throw IOException("connection reset while reading $path")
            val body = served[path]
            if (body == null) respond("not found", HttpStatusCode.NotFound) else respond(body, HttpStatusCode.OK)
        },
    )

    private val engine = SyncEngine({ KtorSyncClient(http, it) }, nowEpochSeconds = { now })

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("sheltr-sync")
        store = ContentStore(dir.toString(), JdbcStoreDriverFactory)
    }

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun tearDown() {
        store.close()
        http.close()
        dir.deleteRecursively()
    }

    private fun sync(baseUrl: String = BASE_URL, publicKey: ByteArray = signer.publicKey): SyncResult =
        runBlocking { engine.run(baseUrl, publicKey, store) }

    private fun serve(bundle: TestBundle) {
        served.clear()
        served["bundle.json"] = bundle.manifestBytes
        served["bundle.json.sig"] = bundle.signature
        for ((id, bytes) in bundle.files) served["documents/$id.json"] = bytes
    }

    private fun documentRequests() = requests.filter { it.startsWith("documents/") }

    // --- publisher fixture: a real bundle signed by the publisher ------------------------------------------------

    @Test
    fun publisherSignedBundleInstallsIntoEmptyStore() {
        served["bundle.json"] = SignedBundleFixture.BUNDLE_JSON.encodeToByteArray()
        served["bundle.json.sig"] = SignedBundleFixture.SIGNATURE
        served["documents/${PublisherFixture.DOCUMENT_ID}.json"] = PublisherFixture.DOCUMENT_JSON.encodeToByteArray()

        val result = sync(publicKey = SignedBundleFixture.PUBLIC_KEY)

        assertEquals(SyncResult.Updated("2026-10-05T12:00:00Z", 1), result)
        assertEquals("2026-10-05T12:00:00Z", store.bundleVersion())
        assertEquals("Flood guide", assertNotNull(store.document(PublisherFixture.DOCUMENT_ID)).title)
        assertEquals(2, store.chunks(PublisherFixture.DOCUMENT_ID).size)
        assertEquals(now, store.lastSyncEpochSeconds())
    }

    @Test
    fun publisherBundleWithTamperedManifestIsRejected() {
        served["bundle.json"] = SignedBundleFixture.BUNDLE_JSON.replace("Flood guide", "Flood guidf").encodeToByteArray()
        served["bundle.json.sig"] = SignedBundleFixture.SIGNATURE
        served["documents/${PublisherFixture.DOCUMENT_ID}.json"] = PublisherFixture.DOCUMENT_JSON.encodeToByteArray()

        assertEquals(SyncResult.RejectedSignature, sync(publicKey = SignedBundleFixture.PUBLIC_KEY))
        assertNull(store.bundleVersion())
        assertEquals(emptyList(), documentRequests())
    }

    // --- generated bundles -----------------------------------------------------------------------------------------

    @Test
    fun newVersionInstallsAndRecordsSyncTime() {
        serve(V1)
        val result = sync()

        assertEquals(SyncResult.Updated(V1.version, 3), result)
        assertEquals(V1.version, store.bundleVersion())
        assertEquals(listOf("doc-a-0"), store.search("sandbags").map { it.chunk.id })
        assertEquals(1_000L, store.lastSyncEpochSeconds())
    }

    @Test
    fun invalidSignatureLeavesStoreUnchanged() {
        serve(V1)
        sync()
        val activeBytes = Path.of(store.activePath).readBytes()
        requests.clear()

        serve(V2)
        served["bundle.json.sig"] = otherSigner.sign(V2.manifestBytes) // valid signature, wrong key
        now = 2_000L
        assertEquals(SyncResult.RejectedSignature, sync())

        assertEquals(listOf("bundle.json", "bundle.json.sig"), requests, "no document is fetched")
        assertEquals(V1.version, store.bundleVersion())
        assertEquals(1_000L, store.lastSyncEpochSeconds(), "a rejected sync is not recorded")
        assertContentEquals(activeBytes, Path.of(store.activePath).readBytes())

        served["bundle.json.sig"] = V2.signature.copyOf().also { it[0] = (it[0] + 1).toByte() } // corrupt signature
        assertEquals(SyncResult.RejectedSignature, sync())
        assertEquals(V1.version, store.bundleVersion())
    }

    @Test
    fun sameVersionIsUpToDateWithoutDocumentRequests() {
        serve(V1)
        sync()
        requests.clear()
        now = 5_000L

        assertEquals(SyncResult.UpToDate(V1.version), sync())

        assertEquals(listOf("bundle.json", "bundle.json.sig"), requests)
        assertEquals(5_000L, store.lastSyncEpochSeconds(), "up to date counts as a successful sync")
    }

    @Test
    fun changedDocumentIsTheOnlyOneDownloaded() {
        serve(V1)
        sync()
        requests.clear()

        serve(V2) // doc-b changed, doc-c removed, doc-a unchanged, doc-d new
        val result = sync()

        assertEquals(SyncResult.Updated(V2.version, 2), result)
        assertEquals(listOf("documents/doc-b.json", "documents/doc-d.json"), documentRequests())
        assertEquals(V2.version, store.bundleVersion())
        assertEquals(listOf("doc-a-0"), store.search("sandbags").map { it.chunk.id }, "unchanged doc-a reused")
        assertEquals(listOf("doc-b-0"), store.search("generator").map { it.chunk.id }, "doc-b has its new text")
        assertEquals(emptyList(), store.search("candles"), "doc-b old text is gone")
        assertNull(store.document("doc-c"), "document dropped from the manifest is removed")
        assertNotNull(store.document("doc-d"))
        assertEquals(V2.manifest.documents.first { it.id == "doc-a" }.fileChecksum, store.document("doc-a")?.fileChecksum)
    }

    @Test
    fun wrongFileChecksumFailsAndKeepsOldContent() {
        serve(V1)
        sync()
        val activeBytes = Path.of(store.activePath).readBytes()

        serve(V2)
        served["documents/doc-d.json"] = documentBytes("doc-d", "Tampered text.") // not what the manifest signed
        now = 2_000L
        val result = sync()

        assertIs<SyncResult.Failed>(result)
        assertTrue("doc-d" in result.reason, result.reason)
        assertEquals(V1.version, store.bundleVersion())
        assertEquals(1_000L, store.lastSyncEpochSeconds())
        assertContentEquals(activeBytes, Path.of(store.activePath).readBytes())
    }

    @Test
    fun interruptedDownloadFailsAndKeepsOldContent() {
        serve(V1)
        sync()
        val activeBytes = Path.of(store.activePath).readBytes()

        serve(V2)
        broken += "documents/doc-d.json"
        val result = sync()

        assertIs<SyncResult.Failed>(result)
        assertEquals(V1.version, store.bundleVersion())
        assertNotNull(store.document("doc-c"))
        assertContentEquals(activeBytes, Path.of(store.activePath).readBytes())

        broken.clear() // the next attempt succeeds
        assertEquals(SyncResult.Updated(V2.version, 2), sync())
    }

    @Test
    fun missingDocumentFailsAndKeepsOldContent() {
        serve(V1)
        sync()
        serve(V2)
        served.remove("documents/doc-b.json")

        val result = sync()

        assertIs<SyncResult.Failed>(result)
        assertTrue("404" in result.reason, result.reason)
        assertEquals(V1.version, store.bundleVersion())
    }

    @Test
    fun unsupportedFormatVersionFails() {
        val manifest = V1.manifest.copy(formatVersion = 2)
        val bytes = BundleJson.encodeToString(BundleManifest.serializer(), manifest).encodeToByteArray()
        served["bundle.json"] = bytes
        served["bundle.json.sig"] = signer.sign(bytes)

        val result = sync()

        assertIs<SyncResult.Failed>(result)
        assertTrue("formatVersion" in result.reason, result.reason)
        assertNull(store.bundleVersion())
        assertNull(store.lastSyncEpochSeconds())
    }

    @Test
    fun plainHttpBaseUrlIsRefused() {
        serve(V1)

        val result = sync(baseUrl = "http://example.org/bundle")

        assertIs<SyncResult.Failed>(result)
        assertTrue("https" in result.reason, result.reason)
        assertEquals(emptyList(), requests, "nothing is fetched over plain http")
        assertNull(store.bundleVersion())
    }

    @Test
    fun lastSyncTimeSurvivesReopenAndResetsWithNewContent() {
        serve(V1)
        sync()
        store.close()
        store = ContentStore(dir.toString(), JdbcStoreDriverFactory)
        assertEquals(1_000L, store.lastSyncEpochSeconds())

        serve(V2)
        now = 3_000L
        sync()
        assertEquals(3_000L, store.lastSyncEpochSeconds())
    }

    private class TestSigner {
        private val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

        /** Raw 32-byte key: the X.509 encoding minus its 12-byte prefix. */
        val publicKey: ByteArray = keyPair.public.encoded.takeLast(32).toByteArray()

        fun sign(message: ByteArray): ByteArray =
            Signature.getInstance("Ed25519").run {
                initSign(keyPair.private)
                update(message)
                sign()
            }
    }

    private class TestBundle(val version: String, documents: Map<String, String>, signer: TestSigner) {
        val files: Map<String, ByteArray> = documents.mapValues { (id, text) -> documentBytes(id, text) }
        val manifest = BundleManifest(
            formatVersion = BUNDLE_FORMAT_VERSION,
            bundleVersion = version,
            documents = documents.map { (id, text) ->
                BundleDocument(
                    id = id, title = "Title $id", agency = "Example Agency", publishedDate = "2026-01-01",
                    licence = "CC-BY-4.0", country = "NO", hazards = listOf("FLOOD"),
                    sourceUri = "https://example.org/$id.pdf", sourceType = "pdf",
                    checksum = sha256Hex("source $id $text".encodeToByteArray()),
                    fileChecksum = sha256Hex(files.getValue(id)),
                    chunks = listOf(BundleChunkRef("$id-0", sha256Hex(text.encodeToByteArray()))),
                )
            },
        )
        val manifestBytes: ByteArray = BundleJson.encodeToString(BundleManifest.serializer(), manifest).encodeToByteArray()
        val signature: ByteArray = signer.sign(manifestBytes)
    }

    private companion object {
        const val BASE_URL = "https://example.org/bundle"

        val signer = TestSigner()
        val otherSigner = TestSigner()

        val V1 = TestBundle(
            "2026-10-01T00:00:00Z",
            linkedMapOf(
                "doc-a" to "Fill sandbags before the river rises.",
                "doc-b" to "Keep candles and matches dry.",
                "doc-c" to "Clear gutters before winter storms.",
            ),
            signer,
        )

        val V2 = TestBundle(
            "2026-10-05T12:00:00Z",
            linkedMapOf(
                "doc-a" to "Fill sandbags before the river rises.",
                "doc-b" to "Never run a generator indoors.",
                "doc-d" to "Move to high ground when the siren sounds.",
            ),
            signer,
        )

        fun documentBytes(id: String, text: String): ByteArray =
            BundleJson.encodeToString(
                DocumentFile.serializer(),
                DocumentFile(id, listOf(DocumentChunk("$id-0", text, 1))),
            ).encodeToByteArray()
    }
}

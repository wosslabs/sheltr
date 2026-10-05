package com.sheltr.shared.store

import com.sheltr.shared.bundle.BUNDLE_FORMAT_VERSION
import com.sheltr.shared.bundle.BundleChunkRef
import com.sheltr.shared.bundle.BundleDocument
import com.sheltr.shared.bundle.BundleManifest
import com.sheltr.shared.bundle.DocumentChunk
import com.sheltr.shared.bundle.DocumentFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContentStoreTest {

    private lateinit var dir: Path
    private lateinit var store: ContentStore

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("sheltr-store")
        store = ContentStore(dir.toString(), JdbcStoreDriverFactory)
    }

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    @Test
    fun emptyStoreHasNoVersionAndNoResults() {
        assertNull(store.bundleVersion())
        assertEquals(emptyList(), store.search("flood"))
        assertNull(store.document("doc-a"))
    }

    @Test
    fun successfulSwapMakesNewContentQueryable() {
        store.install(V1.manifest, V1.source)
        assertEquals("2026-10-01T00:00:00Z", store.bundleVersion())

        store.install(V2.manifest, V2.source)

        assertEquals("2026-10-05T12:00:00Z", store.bundleVersion())
        assertNull(store.document("doc-a"), "document removed in v2 is gone")
        val doc = assertNotNull(store.document("doc-c"))
        assertEquals("Wildfire smoke", doc.title)
        assertEquals("CA", doc.country)
        assertEquals(listOf("doc-c-0", "doc-c-1"), store.chunks("doc-c").map { it.id })
        assertEquals(listOf("doc-c-1"), store.search("respirator").map { it.chunk.id })
        assertEquals(emptyList(), store.search("sandbags"), "v1-only text is no longer indexed")
        assertFalse(Path.of(store.stagingPath).exists())
    }

    @Test
    fun interruptedStagingKeepsPreviousContentUnchanged() {
        store.install(V1.manifest, V1.source)
        val before = snapshot()
        val activeBytes = Path.of(store.activePath).readBytes()

        var loaded = 0
        val failing = DocumentSource { doc ->
            if (++loaded == 2) throw IOException("connection reset while reading ${doc.id}")
            V2.source.load(doc)
        }
        assertFailsWith<IOException> { store.install(V2.manifest, failing) }

        assertEquals(2, loaded, "staging got as far as the second document")
        assertEquals(before, snapshot())
        assertContentEquals(activeBytes, Path.of(store.activePath).readBytes(), "active file untouched")
        assertFalse(Path.of(store.stagingPath).exists(), "staging file removed")
    }

    @Test
    fun chunkCountMismatchRefusesSwapAndKeepsOldContent() {
        store.install(V1.manifest, V1.source)
        val before = snapshot()

        // The document file lacks one of the chunks the manifest lists.
        val short = DocumentSource { doc ->
            val file = V2.source.load(doc)
            if (doc.id == "doc-c") file.copy(chunks = file.chunks.dropLast(1)) else file
        }
        val e = assertFailsWith<StoreUpdateException> { store.install(V2.manifest, short) }

        assertEquals("staged 2 chunks, manifest lists 3", e.message)
        assertEquals(before, snapshot())
        assertFalse(Path.of(store.stagingPath).exists())
    }

    @Test
    fun duplicateDocumentRefusesSwap() {
        store.install(V1.manifest, V1.source)
        val before = snapshot()

        // A manifest entry duplicated: the second insert of the same id fails, nothing is swapped.
        val manifest = V2.manifest.copy(documents = V2.manifest.documents + V2.manifest.documents.first())
        assertFailsWith<Exception> { store.install(manifest, V2.source) }

        assertEquals(before, snapshot())
    }

    @Test
    fun ftsFindsPresentWordAndNotAbsentWord() {
        store.install(V1.manifest, V1.source)

        val hits = store.search("sandbags")
        assertEquals(listOf("doc-a-1"), hits.map { it.chunk.id })
        assertEquals("doc-a", hits.single().chunk.documentId)
        assertEquals(2, hits.single().chunk.pageNumber)

        assertEquals(emptyList(), store.search("volcano"), "word in no chunk")
        assertEquals(emptyList(), store.search("  -- \"* "), "no words")
        // FTS5 syntax in user text is treated as words, not operators.
        assertEquals(listOf("doc-a-1"), store.search("sandbags NOT").map { it.chunk.id })
    }

    @Test
    fun bundleVersionChangesOnlyOnSuccessfulSwap() {
        store.install(V1.manifest, V1.source)
        assertEquals("2026-10-01T00:00:00Z", store.bundleVersion())

        assertFailsWith<IOException> { store.install(V2.manifest) { throw IOException("offline") } }
        assertEquals("2026-10-01T00:00:00Z", store.bundleVersion())

        val short = DocumentSource { doc -> V2.source.load(doc).let { it.copy(chunks = it.chunks.take(0)) } }
        assertFailsWith<StoreUpdateException> { store.install(V2.manifest, short) }
        assertEquals("2026-10-01T00:00:00Z", store.bundleVersion())

        store.install(V2.manifest, V2.source)
        assertEquals("2026-10-05T12:00:00Z", store.bundleVersion())
    }

    @Test
    fun contentSurvivesReopenAndLeftoverStagingIsDiscarded() {
        store.install(V1.manifest, V1.source)
        store.close()
        Files.writeString(Path.of(store.stagingPath), "partial write from a crash")

        store = ContentStore(dir.toString(), JdbcStoreDriverFactory)

        assertFalse(Path.of(store.stagingPath).exists())
        assertEquals("2026-10-01T00:00:00Z", store.bundleVersion())
        assertEquals(listOf("doc-a-1"), store.search("sandbags").map { it.chunk.id })
    }

    @Test
    fun hazardsRoundTripThroughInstall() {
        store.install(V1.manifest, V1.source)
        assertEquals(listOf("FLOOD"), assertNotNull(store.document("doc-a")).hazards)

        store.install(V2.manifest, V2.source)

        // Stored once each (the manifest lists WILDFIRE twice) and read back sorted.
        assertEquals(listOf("HEATWAVE", "WILDFIRE"), assertNotNull(store.document("doc-c")).hazards)
        assertEquals(listOf("FLOOD"), assertNotNull(store.document("doc-b")).hazards)
        store.close()
        store = ContentStore(dir.toString(), JdbcStoreDriverFactory)
        assertEquals(listOf("HEATWAVE", "WILDFIRE"), assertNotNull(store.document("doc-c")).hazards, "survives reopen")
    }

    @Test
    fun documentWithoutHazardsHasEmptyList() {
        val (entry, file) = doc("doc-x", "No hazards", null, listOf("Keep a kit." to null), hazards = emptyList())
        store.install(BundleManifest(BUNDLE_FORMAT_VERSION, "2026-10-06T00:00:00Z", listOf(entry))) { file }
        assertEquals(emptyList(), assertNotNull(store.document("doc-x")).hazards)
    }

    @Test
    fun ftsQueryQuotesEveryWord() {
        assertEquals("\"flood\" OR \"water\"", ftsQueryFor("Flood? water* flood".lowercase()))
        assertNull(ftsQueryFor("\"\" ( ) *"))
    }

    /** Everything a reader can see, for before/after comparison. */
    private fun snapshot(): List<Any?> {
        val ids = listOf("doc-a", "doc-b", "doc-c")
        return listOf(
            store.bundleVersion(),
            ids.map { store.document(it) },
            ids.map { store.chunks(it) },
            store.search("sandbags evacuation respirator").map { it.chunk.id }.sorted(),
        )
    }

    private class Bundle(val manifest: BundleManifest, files: List<DocumentFile>) {
        private val byId = files.associateBy { it.id }
        val source = DocumentSource { doc -> byId.getValue(doc.id) }
    }

    private companion object {
        fun doc(
            id: String,
            title: String,
            country: String?,
            chunks: List<Pair<String, Int?>>,
            hazards: List<String> = listOf("FLOOD"),
        ): Pair<BundleDocument, DocumentFile> {
            val file = DocumentFile(id, chunks.mapIndexed { i, (text, page) -> DocumentChunk("$id-$i", text, page) })
            val entry = BundleDocument(
                id = id, title = title, agency = "Example Agency", publishedDate = "2024-03-15", licence = "CC-BY-4.0",
                country = country, hazards = hazards, sourceUri = "https://example.org/$id",
                sourceType = "pdf", checksum = "0".repeat(64), fileChecksum = "1".repeat(64),
                chunks = file.chunks.map { BundleChunkRef(it.id, "2".repeat(64)) },
            )
            return entry to file
        }

        fun bundle(version: String, vararg docs: Pair<BundleDocument, DocumentFile>) =
            Bundle(BundleManifest(BUNDLE_FORMAT_VERSION, version, docs.map { it.first }), docs.map { it.second })

        val V1 = bundle(
            "2026-10-01T00:00:00Z",
            doc("doc-a", "Flood guide", "NO", listOf("Move to higher ground." to 1, "Place sandbags at doors." to 2)),
            doc("doc-b", "Evacuation", null, listOf("Follow the evacuation route." to null)),
        )

        val V2 = bundle(
            "2026-10-05T12:00:00Z",
            doc("doc-b", "Evacuation", null, listOf("Follow the evacuation route." to null)),
            doc(
                "doc-c", "Wildfire smoke", "CA", listOf("Stay indoors." to 1, "Wear a respirator outside." to 1),
                hazards = listOf("WILDFIRE", "HEATWAVE", "WILDFIRE"),
            ),
        )
    }
}

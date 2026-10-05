package com.sheltr.shared.retrieval

import com.sheltr.shared.bundle.BUNDLE_FORMAT_VERSION
import com.sheltr.shared.bundle.BundleChunkRef
import com.sheltr.shared.bundle.BundleDocument
import com.sheltr.shared.bundle.BundleManifest
import com.sheltr.shared.bundle.DocumentChunk
import com.sheltr.shared.bundle.DocumentFile
import com.sheltr.shared.store.ContentStore
import com.sheltr.shared.store.JdbcStoreDriverFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Retrieval over the real FTS5 store (bm25 scores from SQLite). */
class RetrieverStoreTest {

    private lateinit var dir: Path
    private lateinit var store: ContentStore
    private lateinit var retriever: Retriever

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("sheltr-retrieval")
        store = ContentStore(dir.toString(), JdbcStoreDriverFactory)
        val docs = listOf(
            // The global chunk is the better keyword match for "boil water"; the Norwegian one is close behind.
            doc("global", null, listOf("DROUGHT"), "Boil water before drinking. Boil it for one full minute."),
            doc("norway", "NO", listOf("STORM"), "Boil water before drinking it after the storm passes."),
            doc("sweden", "SE", listOf("FLOOD"), "Boil water before drinking it after the flood recedes."),
        ) + (1..20).map { doc("filler-$it", "US", listOf("HEATWAVE"), "Keep the kit ready and check the plan number $it.") }
        val manifest = BundleManifest(BUNDLE_FORMAT_VERSION, "2026-10-05T12:00:00Z", docs.map { it.first })
        val files = docs.associate { it.first.id to it.second }
        store.install(manifest) { files.getValue(it.id) }
        retriever = Retriever(store)
    }

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    private fun ids(query: RetrievalQuery) = retriever.rank(query).map { it.chunk.documentId }

    @Test
    fun withoutCountryOrHazardsTheOrderIsPlainKeywordRanking() {
        val plain = store.search("boil water", 5)
        val ranked = retriever.rank(RetrievalQuery("boil water", limit = 5))
        assertEquals(plain.map { it.chunk.id }, ranked.map { it.chunk.id })
        assertEquals(plain.map { -it.score }, ranked.map { it.score })
        assertEquals("global", ranked.first().chunk.documentId)
    }

    @Test
    fun countryMatchIsBoostedAboveACloseGlobalMatch() {
        val plain = retriever.rank(RetrievalQuery("boil water", limit = 3))
        val global = plain.single { it.document.id == "global" }
        val norway = plain.single { it.document.id == "norway" }
        assertTrue(global.score > norway.score, "global is the better keyword match")
        assertTrue(norway.score * (1 + COUNTRY_BOOST) > global.score, "within the boost: ${norway.score} vs ${global.score}")

        assertEquals("norway", ids(RetrievalQuery("boil water", country = "NO", limit = 3)).first())
    }

    @Test
    fun globalPassageIsKeptWhenACountryIsSet() {
        for (country in listOf("NO", "SE", "US", "FI")) {
            val ranked = ids(RetrievalQuery("boil water", country = country, limit = 3))
            assertEquals(setOf("global", "norway", "sweden"), ranked.toSet(), "country $country")
        }
    }

    @Test
    fun activeHazardBoostsItsDocument() {
        assertEquals("global", ids(RetrievalQuery("boil water", limit = 3)).first())
        assertEquals("sweden", ids(RetrievalQuery("boil water", activeHazards = setOf("FLOOD"), limit = 3)).first())
        assertEquals("norway", ids(RetrievalQuery("boil water", activeHazards = setOf("STORM"), limit = 3)).first())
        // A hazard no matching document covers leaves the keyword order.
        assertEquals(
            ids(RetrievalQuery("boil water", limit = 3)),
            ids(RetrievalQuery("boil water", activeHazards = setOf("EARTHQUAKE"), limit = 3)),
        )
    }

    @Test
    fun rareWordAnswersAtTheDefaultThreshold() {
        val result = assertIs<RetrievalResult.Passages>(retriever.retrieve(RetrievalQuery("boil water")))
        assertTrue(result.passages.first().score >= KEYWORD_RELEVANCE_THRESHOLD)
    }

    @Test
    fun refusesWhenOnlyCommonWordsOrNothingMatches() {
        // "the" is in more than half of the chunks: FTS5 gives it an idf of 1e-6.
        val common = retriever.rank(RetrievalQuery("the", limit = 3))
        assertTrue(common.isNotEmpty() && common.all { it.score < KEYWORD_RELEVANCE_THRESHOLD })
        assertSame(RetrievalResult.Refusal, retriever.retrieve(RetrievalQuery("the")))
        assertSame(RetrievalResult.Refusal, retriever.retrieve(RetrievalQuery("volcano")))
    }

    @Test
    fun passageExactlyAtTheThresholdIsReturnedAndJustBelowIsRefused() {
        val best = retriever.rank(RetrievalQuery("boil water")).maxOf { it.score }

        assertIs<RetrievalResult.Passages>(Retriever(store, threshold = best).retrieve(RetrievalQuery("boil water")))
        assertSame(
            RetrievalResult.Refusal,
            Retriever(store, threshold = Math.nextUp(best)).retrieve(RetrievalQuery("boil water")),
        )
    }

    @Test
    fun citationsComeFromRetrievedPassages() {
        val passages = (retriever.retrieve(RetrievalQuery("boil water", limit = 3)) as RetrievalResult.Passages).passages
        val used = CitationSelector.select("Boil it [2].", passages, passages.map(Citation::of))
        assertEquals(listOf(passages[1].chunk.id), used.map { it.chunkId })
        assertEquals(passages[1].document.title, used.single().sourceTitle)
    }

    private companion object {
        fun doc(id: String, country: String?, hazards: List<String>, text: String): Pair<BundleDocument, DocumentFile> {
            val file = DocumentFile(id, listOf(DocumentChunk("$id-0", text, null)))
            val entry = BundleDocument(
                id = id, title = "Title $id", agency = "Agency", publishedDate = "2024-01-01", licence = "CC-BY-4.0",
                country = country, hazards = hazards, sourceUri = "https://example.org/$id", sourceType = "html",
                checksum = "0".repeat(64), fileChecksum = "1".repeat(64),
                chunks = file.chunks.map { BundleChunkRef(it.id, "2".repeat(64)) },
            )
            return entry to file
        }
    }
}

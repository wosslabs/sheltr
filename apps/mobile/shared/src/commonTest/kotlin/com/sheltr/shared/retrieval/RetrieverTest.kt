package com.sheltr.shared.retrieval

import com.sheltr.shared.store.SearchHit
import com.sheltr.shared.store.StoreReader
import com.sheltr.shared.store.StoredChunk
import com.sheltr.shared.store.StoredDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/** Ranking and gate rules over a fixed keyword ranking (bm25 scores given directly). Store-backed: RetrieverStoreTest. */
class RetrieverTest {

    // Keyword order a, b, c, d. Each boosted passage is within 15 % of the one above it.
    private val store = FakeStore(
        documents = listOf(
            document("global", country = null, hazards = listOf("FLOOD")),
            document("no", country = "NO", hazards = listOf("STORM")),
            document("se", country = "SE", hazards = listOf("WILDFIRE")),
        ),
        hits = listOf(
            hit("a", "global", -3.0),
            hit("b", "se", -2.9),
            hit("c", "no", -2.8),
            hit("d", "global", -0.5),
        ),
    )
    private val retriever = Retriever(store)

    private fun ids(query: RetrievalQuery) = retriever.rank(query).map { it.chunk.id }

    @Test
    fun noCountryAndNoHazardsKeepsKeywordOrder() {
        val plain = store.search("water", 3).map { it.chunk.id }
        assertEquals(listOf("a", "b", "c"), plain)
        assertEquals(plain, ids(RetrievalQuery("water", limit = 3)))
        assertEquals(store.search("water", 4).map { it.score }, retriever.rank(RetrievalQuery("water", limit = 4)).map { -it.score })
    }

    @Test
    fun countryMatchMovesUpAndNothingIsRemoved() {
        // c: 2.8 * 1.15 = 3.22 passes a (3.0) and b (2.9).
        assertEquals(listOf("c", "a", "b", "d"), ids(RetrievalQuery("water", country = "NO", limit = 4)))
        // A country with no documents changes nothing.
        assertEquals(listOf("a", "b", "c", "d"), ids(RetrievalQuery("water", country = "FI", limit = 4)))
    }

    @Test
    fun globalPassagesAreKeptWhenACountryIsSet() {
        val ranked = retriever.rank(RetrievalQuery("water", country = "SE", limit = 4))
        assertEquals(listOf("b", "a", "c", "d"), ranked.map { it.chunk.id })
        assertEquals(listOf("a", "d"), ranked.filter { it.document.country == null }.map { it.chunk.id })
    }

    @Test
    fun activeHazardBoostsWithoutFiltering() {
        assertEquals(listOf("b", "a", "c", "d"), ids(RetrievalQuery("water", activeHazards = setOf("WILDFIRE"), limit = 4)))
        // Country and hazard multiply: c is 2.8 * 1.15 * 1.15 = 3.70, b is 2.9 * 1.15 = 3.34.
        assertEquals(
            listOf("c", "b", "a", "d"),
            ids(RetrievalQuery("water", country = "NO", activeHazards = setOf("WILDFIRE", "STORM"), limit = 4)),
        )
    }

    @Test
    fun boostedPassageBelowTheCutCanEnterTheResults() {
        assertEquals(listOf("a", "b"), ids(RetrievalQuery("water", limit = 2)))
        assertEquals(listOf("c", "a"), ids(RetrievalQuery("water", country = "NO", limit = 2)))
    }

    @Test
    fun refusesWhenTheBestPassageIsBelowTheThreshold() {
        val weak = Retriever(FakeStore(store.documents, listOf(hit("d", "global", -0.999))))
        val result = weak.retrieve(RetrievalQuery("water"))
        assertSame(RetrievalResult.Refusal, result)
        assertEquals(
            "I don't have enough reliable information from trusted sources to answer that question.",
            RetrievalResult.Refusal.MESSAGE,
        )
        assertSame(RetrievalResult.Refusal, Retriever(FakeStore(store.documents, emptyList())).retrieve(RetrievalQuery("x")))
    }

    @Test
    fun answersWhenTheBestPassageIsExactlyAtTheThreshold() {
        val atThreshold = Retriever(FakeStore(store.documents, listOf(hit("d", "global", -KEYWORD_RELEVANCE_THRESHOLD), hit("e", "no", -0.2))))
        val result = assertIs<RetrievalResult.Passages>(atThreshold.retrieve(RetrievalQuery("water")))
        // The gate is on the best passage; weaker passages under it are still returned.
        assertEquals(listOf("d", "e"), result.passages.map { it.chunk.id })
    }

    @Test
    fun boostNeverLiftsAPassageOverTheThreshold() {
        // 0.9 * 1.15 * 1.15 = 1.19 would pass if the gate used the boosted score; it uses the keyword score.
        val boosted = Retriever(FakeStore(store.documents, listOf(hit("c", "no", -0.9))))
        assertSame(
            RetrievalResult.Refusal,
            boosted.retrieve(RetrievalQuery("water", country = "NO", activeHazards = setOf("STORM"))),
        )
    }

    @Test
    fun thresholdIsOnTheBm25Scale() {
        assertEquals(1.0, KEYWORD_RELEVANCE_THRESHOLD)
    }

    @Test
    fun typoInQueryIsCorrectedWhenFirstSearchRefuses() {
        val store = FakeStore(
            documents = listOf(document("d", country = null, hazards = listOf("EARTHQUAKE"))),
            respondTo = mapOf(
                "eartquake shelter" to emptyList(),
                "earthquake shelter" to listOf(hit("a", "d", -5.0)), // well above threshold once negated
            ),
        )
        val result = Retriever(store).retrieve(RetrievalQuery("eartquake shelter"))
        assertIs<RetrievalResult.Passages>(result)
        assertEquals("a", result.passages.first().chunk.id)
        // Proves the retry actually re-queried with the corrected text, not just that the answer happened to match.
        assertEquals(2, store.searchCallCount)
    }

    @Test
    fun rankSearchesTheSynonymExpandedText() {
        // respondTo has no entry for the raw query "quake": if rank() searched that text verbatim (skipping
        // expandWithSynonyms), search would return empty and no hit would be found. Only the expanded text
        // "quake earthquake" has a hit registered, so a result here proves rank() actually expands before searching.
        val store = FakeStore(
            documents = listOf(document("d", country = null, hazards = listOf("EARTHQUAKE"))),
            respondTo = mapOf("quake earthquake" to listOf(hit("a", "d", -5.0))),
        )
        val result = Retriever(store).rank(RetrievalQuery("quake"))
        assertEquals(listOf("a"), result.map { it.chunk.id })
    }

    @Test
    fun noRetryWhenCorrectionDoesNotChangeTheQuery() {
        // "water" has no vocabulary term within distance 1, so correctTypos("water") == "water": the fallback must
        // not call store.search a second time with the same text. The respondTo map has no entry for "water", so a
        // second call would also return empty (and still refuse); the call count is what actually proves no retry.
        val store = FakeStore(documents = emptyList(), respondTo = emptyMap())
        assertEquals(RetrievalResult.Refusal, Retriever(store).retrieve(RetrievalQuery("water")))
        assertEquals(1, store.searchCallCount)
    }

    private class FakeStore(
        val documents: List<StoredDocument>,
        private val hits: List<SearchHit> = emptyList(),
        private val respondTo: Map<String, List<SearchHit>>? = null,
    ) : StoreReader {
        /** Number of [search] calls made so far; lets a test prove whether a retry happened. */
        var searchCallCount: Int = 0
            private set

        override fun bundleVersion() = "test"
        override fun document(id: String) = documents.firstOrNull { it.id == id }
        override fun chunks(documentId: String) = hits.map { it.chunk }.filter { it.documentId == documentId }
        override fun search(query: String, limit: Int): List<SearchHit> {
            searchCallCount++
            return respondTo?.let { it[query].orEmpty() } ?: hits.take(limit)
        }
    }

    private companion object {
        fun document(id: String, country: String?, hazards: List<String>) = StoredDocument(
            id, "Title $id", "Agency", "2024-01-01", "CC-BY-4.0", country, hazards,
            "https://example.org/$id", "html", "0".repeat(64), "1".repeat(64),
        )

        fun hit(id: String, documentId: String, bm25: Double) =
            SearchHit(StoredChunk(id, documentId, "text $id", null, 0), bm25)
    }
}

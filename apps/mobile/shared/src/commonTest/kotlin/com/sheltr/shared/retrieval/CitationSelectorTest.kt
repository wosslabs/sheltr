package com.sheltr.shared.retrieval

import kotlin.test.Test
import kotlin.test.assertEquals

/** Citation selection cases. */
class CitationSelectorTest {

    private val first = "chunk-1"
    private val second = "chunk-2"
    private val third = "chunk-3"
    private val passages = listOf(first, second, third)
    private val citations = listOf(citation(first, "One"), citation(second, "Two"), citation(third, "Three"))

    @Test
    fun keepsOnlyThePassagesTheAnswerCites() {
        val used = CitationSelector.selectByChunkIds("Boil it [3]. Then cool it [1].", passages, citations)

        assertEquals(listOf("One", "Three"), used.map { it.sourceTitle })
    }

    @Test
    fun ignoresPassageNumbersThatDoNotExist() {
        assertEquals(emptyList(), CitationSelector.selectByChunkIds("Boil it [7]. Or [0].", passages, citations))
    }

    @Test
    fun returnsNothingWhenTheAnswerCitesNothing() {
        assertEquals(emptyList(), CitationSelector.selectByChunkIds("Boil it for one minute.", passages, citations))
        assertEquals(emptyList(), CitationSelector.selectByChunkIds(null, passages, citations))
    }

    @Test
    fun skipsACitedPassageWhoseDocumentHadNoCitation() {
        val withoutSecond = listOf(citation(first, "One"), citation(third, "Three"))

        assertEquals(
            listOf("Three"),
            CitationSelector.selectByChunkIds("See [2] and [3].", passages, withoutSecond).map { it.sourceTitle },
        )
    }

    private fun citation(chunkId: String, title: String) =
        Citation("doc-$chunkId", chunkId, title, "https://example.org", null)
}

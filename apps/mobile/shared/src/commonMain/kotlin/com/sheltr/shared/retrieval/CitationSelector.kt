package com.sheltr.shared.retrieval

/** A source shown with an answer. Carries documentId, chunkId, sourceTitle, sourceUrl and pageNumber. */
data class Citation(
    val documentId: String,
    val chunkId: String,
    val sourceTitle: String,
    val sourceUrl: String,
    val pageNumber: Int?,
) {
    companion object {
        fun of(passage: Passage): Citation = Citation(
            passage.document.id, passage.chunk.id, passage.document.title, passage.document.sourceUri,
            passage.chunk.pageNumber,
        )
    }
}

/**
 * Finds which numbered passages an answer cites, written as [1], [2], and so on. Markers are 1-based passage numbers of one to three digits; numbers outside 1..passages.size
 * are ignored; the result is the [citations] whose chunk was cited, in the order of [citations], each once.
 */
object CitationSelector {

    private val MARKER = Regex("""\[([0-9]{1,3})]""")

    fun select(answer: String?, passages: List<Passage>, citations: List<Citation>): List<Citation> =
        selectByChunkIds(answer, passages.map { it.chunk.id }, citations)

    /** The same rule over the chunk ids of the numbered passages, in prompt order. */
    fun selectByChunkIds(answer: String?, passageChunkIds: List<String>, citations: List<Citation>): List<Citation> {
        val cited = mutableSetOf<String>()
        for (match in MARKER.findAll(answer ?: "")) {
            val number = match.groupValues[1].toInt()
            if (number >= 1 && number <= passageChunkIds.size) {
                cited += passageChunkIds[number - 1]
            }
        }
        return citations.filter { it.chunkId in cited }
    }
}

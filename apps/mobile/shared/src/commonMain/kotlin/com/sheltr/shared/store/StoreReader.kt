package com.sheltr.shared.store

/** Read access to the active content. */
interface StoreReader {
    /** The bundle version of the active content, or null if no content has been installed. */
    fun bundleVersion(): String?

    fun document(id: String): StoredDocument?

    /** Every document in the active content, ordered by title. Empty if no content is installed. */
    fun documents(): List<StoredDocument> = emptyList()

    /** The chunks of one document in bundle order. */
    fun chunks(documentId: String): List<StoredChunk>

    /**
     * Keyword search over chunk text (FTS5). [query] is user text: it is split into words and any word may match;
     * FTS5 syntax in it is not interpreted. Results are best first. Returns an empty list for a query with no words.
     */
    fun search(query: String, limit: Int = 20): List<SearchHit>
}

data class StoredDocument(
    val id: String,
    val title: String,
    val agency: String,
    val publishedDate: String,
    val licence: String,
    val country: String?,
    /** DisasterType names the document covers, sorted. Empty if the bundle lists none. */
    val hazards: List<String>,
    val sourceUri: String,
    val sourceType: String,
    val checksum: String,
    val fileChecksum: String,
)

data class StoredChunk(
    val id: String,
    val documentId: String,
    val content: String,
    val pageNumber: Int?,
    /** Index of the chunk within its document, from 0. */
    val position: Int,
)

data class SearchHit(
    val chunk: StoredChunk,
    /** FTS5 bm25 score: lower is a better match. */
    val score: Double,
)

/**
 * Builds an FTS5 query that matches any of the words in [text]. Each word is quoted, so operators, column
 * filters and quotes in user text are never interpreted. Returns null if [text] has no words.
 */
internal fun ftsQueryFor(text: String): String? {
    val words = mutableListOf<String>()
    val current = StringBuilder()
    for (c in text) {
        if (c.isLetterOrDigit()) {
            current.append(c)
        } else if (current.isNotEmpty()) {
            words += current.toString()
            current.clear()
        }
    }
    if (current.isNotEmpty()) words += current.toString()
    if (words.isEmpty()) return null
    return words.distinct().joinToString(" OR ") { "\"$it\"" }
}

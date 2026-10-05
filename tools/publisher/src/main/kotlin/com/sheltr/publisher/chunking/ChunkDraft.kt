package com.sheltr.publisher.chunking

/**
 * A chunk of document text ready to store.
 *
 * @property pageNumber the 1-based source page, or null for an HTML document, which has no pages
 */
data class ChunkDraft(val content: String, val pageNumber: Int?) {
    init {
        require(!content.isJavaBlank()) { "ChunkDraft content must not be blank" }
        require(pageNumber == null || pageNumber >= 1) { "pageNumber must be >= 1" }
    }
}

/** Same predicate as java.lang.String.isBlank(): every char is Character.isWhitespace. */
internal fun String.isJavaBlank(): Boolean = all { Character.isWhitespace(it) }

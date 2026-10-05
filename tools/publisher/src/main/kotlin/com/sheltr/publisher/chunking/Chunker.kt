package com.sheltr.publisher.chunking

import com.sheltr.publisher.text.PageText

/** Splits text into chunks of at most about 1000 characters that overlap by about 100, cut on word boundaries. */
object Chunker {
    private const val CHUNK_SIZE = 1000
    private const val OVERLAP = 100

    fun chunk(pages: List<PageText>): List<ChunkDraft> =
        pages.flatMap { page -> chunkText(page.text, page.pageNumber) }

    /** Chunks a document that has no pages (HTML). Its chunks carry no page number. */
    fun chunkUnpaged(text: String): List<ChunkDraft> = chunkText(text, null)

    private fun chunkText(text: String, pageNumber: Int?): List<ChunkDraft> {
        val drafts = mutableListOf<ChunkDraft>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + CHUNK_SIZE, text.length)
            if (end < text.length) {
                val lastSpace = text.lastIndexOf(' ', end)
                if (lastSpace > start) {
                    end = lastSpace
                }
                // else: no space found in [start, end] (a single token longer than
                // CHUNK_SIZE). Intentional last-resort fallback that hard-cuts mid-word
                // rather than searching further backward without bound.
            }

            var nextStart = if (end >= text.length) text.length else maxOf(end - OVERLAP, start + 1)
            if (end < text.length && nextStart > 0 && text[nextStart - 1] != ' ') {
                // end - OVERLAP can land inside a word. Search forward, unbounded (not limited
                // to the current chunk's end), for the next space and snap just past it.
                val forwardSpace = text.indexOf(' ', nextStart)
                if (forwardSpace >= 0) {
                    if (forwardSpace >= end) {
                        // The next safe boundary sits beyond this chunk's end (a long unbroken
                        // token spans the overlap window). Extend this chunk to cover that span
                        // rather than skipping it, which would drop every character in between.
                        end = forwardSpace
                    }
                    nextStart = forwardSpace + 1
                }
                // else: no space exists in the rest of the text. Keep the unadjusted position.
            }

            // Java String.trim() semantics: strips every char <= ' ', not Kotlin's isWhitespace set.
            val trimmed = text.substring(start, end).trim { it <= ' ' }
            if (!trimmed.isJavaBlank()) {
                drafts.add(ChunkDraft(trimmed, pageNumber))
            }
            if (end >= text.length) {
                break
            }
            start = nextStart
        }
        return drafts
    }
}

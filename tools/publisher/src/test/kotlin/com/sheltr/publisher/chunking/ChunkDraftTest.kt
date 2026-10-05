package com.sheltr.publisher.chunking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChunkDraftTest {

    @Test
    fun rejectsBlankContent() {
        val error = assertFailsWith<IllegalArgumentException> { ChunkDraft("  \n ", 1) }

        assertEquals("ChunkDraft content must not be blank", error.message)
    }

    @Test
    fun allowsNullPageNumberForHtml() {
        assertEquals(null, ChunkDraft("Some HTML text.", null).pageNumber)
    }

    @Test
    fun rejectsPageZero() {
        val error = assertFailsWith<IllegalArgumentException> { ChunkDraft("text", 0) }

        assertEquals("pageNumber must be >= 1", error.message)
    }
}

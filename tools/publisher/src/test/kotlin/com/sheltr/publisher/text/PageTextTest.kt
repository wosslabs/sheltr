package com.sheltr.publisher.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PageTextTest {

    @Test
    fun acceptsPageOne() {
        assertEquals(1, PageText(1, "").pageNumber)
    }

    @Test
    fun rejectsPageZero() {
        val error = assertFailsWith<IllegalArgumentException> { PageText(0, "text") }

        assertEquals("pageNumber must be >= 1", error.message)
    }
}

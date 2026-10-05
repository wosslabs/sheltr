package com.sheltr.publisher.text

import kotlin.test.Test
import kotlin.test.assertEquals

class TextCleanerTest {

    @Test
    fun collapsesRepeatedWhitespace() {
        val cleaned = TextCleaner.clean("Boil   water    for one   minute.")

        assertEquals("Boil water for one minute.", cleaned)
    }

    @Test
    fun collapsesExcessiveBlankLines() {
        val cleaned = TextCleaner.clean("Line one.\n\n\n\n\nLine two.")

        assertEquals("Line one.\n\nLine two.", cleaned)
    }

    @Test
    fun trimsLeadingAndTrailingWhitespace() {
        val cleaned = TextCleaner.clean("   Boil water.   ")

        assertEquals("Boil water.", cleaned)
    }
}

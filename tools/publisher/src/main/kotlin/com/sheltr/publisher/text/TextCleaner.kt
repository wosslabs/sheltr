package com.sheltr.publisher.text

/** Collapses runs of spaces and tabs, limits blank lines to one, and trims. */
object TextCleaner {
    private val horizontalWhitespace = Regex("[ \\t]+")
    private val excessNewlines = Regex("\\n{3,}")

    fun clean(rawText: String): String {
        val collapsed = horizontalWhitespace.replace(rawText, " ")
            .let { excessNewlines.replace(it, "\n\n") }
        // Same predicate as java.lang.String.trim(): every char <= ' ' (including control characters).
        return collapsed.trim { it <= ' ' }
    }
}

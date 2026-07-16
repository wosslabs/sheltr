package com.sheltr.ingestion.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextCleanerTest {

    @Test
    void collapsesRepeatedWhitespace() {
        String cleaned = TextCleaner.clean("Boil   water    for one   minute.");

        assertThat(cleaned).isEqualTo("Boil water for one minute.");
    }

    @Test
    void collapsesExcessiveBlankLines() {
        String cleaned = TextCleaner.clean("Line one.\n\n\n\n\nLine two.");

        assertThat(cleaned).isEqualTo("Line one.\n\nLine two.");
    }

    @Test
    void trimsLeadingAndTrailingWhitespace() {
        String cleaned = TextCleaner.clean("   Boil water.   ");

        assertThat(cleaned).isEqualTo("Boil water.");
    }
}

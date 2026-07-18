package com.sheltr.ingestion.domain;

import com.sheltr.knowledge.domain.PageNumber;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkerTest {

    @Test
    void shortTextProducesOneChunkTaggedWithItsPage() {
        List<ChunkDraft> drafts = Chunker.chunk(List.of(new PageText(3, "Boil water for one minute.")));

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).content()).isEqualTo("Boil water for one minute.");
        assertThat(drafts.get(0).pageNumber()).isEqualTo(new PageNumber(3));
    }

    @Test
    void longTextSplitsIntoMultipleChunksWithoutSplittingWords() {
        String longText = "word ".repeat(400); // 2000 chars, well over the 1000-char chunk size
        List<ChunkDraft> drafts = Chunker.chunk(List.of(new PageText(1, longText)));

        assertThat(drafts.size()).isGreaterThan(1);
        for (ChunkDraft draft : drafts) {
            assertThat(draft.content()).doesNotStartWith(" ").doesNotEndWith(" ");
            assertThat(draft.content()).doesNotContain("wor d", "wo rd"); // no mid-word split artifacts
        }
    }

    @Test
    void realisticProseNeverSplitsAChunkStartMidWord() {
        // Non-periodic, sentence-like text with varying word lengths. Unlike a repeated
        // fixed-length token (e.g. "word ".repeat(n)), this text does NOT have the property
        // that every 100-char offset happens to land on a space, so it actually exercises
        // the start-boundary word-safety fix.
        String[] sentences = {
                "The quick brown fox jumps over the lazy dog while onlookers murmur quietly under the afternoon sun.",
                "A programmer often refactors legacy code to remove duplication, improve readability, and reduce future maintenance costs.",
                "Meteorologists predict scattered thunderstorms across the valley tomorrow afternoon, with temperatures dropping sharply overnight.",
                "Historians frequently debate the causes of complex geopolitical conflicts, weighing economic, cultural, and ideological factors.",
                "Chefs carefully balance sweet, sour, salty, and bitter flavors when crafting an unforgettable tasting menu experience.",
                "Astronomers recently discovered an unusually bright exoplanet orbiting a distant binary star system many light-years away.",
        };
        StringBuilder builder = new StringBuilder();
        int i = 0;
        while (builder.length() < 4500) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(sentences[i % sentences.length]);
            i++;
        }
        String longText = builder.toString();

        // Build the set of word-start positions in the source text: position 0, and every
        // position immediately following a space (and not itself a space, to skip over any
        // run of consecutive separators).
        Set<Integer> wordStarts = new HashSet<>();
        for (int pos = 0; pos < longText.length(); pos++) {
            boolean atTextStart = pos == 0;
            boolean afterSpace = pos > 0 && longText.charAt(pos - 1) == ' ';
            boolean isNotSpace = longText.charAt(pos) != ' ';
            if ((atTextStart || afterSpace) && isNotSpace) {
                wordStarts.add(pos);
            }
        }

        List<ChunkDraft> drafts = Chunker.chunk(List.of(new PageText(1, longText)));

        assertThat(drafts.size()).isGreaterThan(2);

        int searchFrom = 0;
        for (int chunkIndex = 0; chunkIndex < drafts.size(); chunkIndex++) {
            String content = drafts.get(chunkIndex).content();
            int foundAt = longText.indexOf(content, searchFrom);
            assertThat(foundAt)
                    .withFailMessage("Chunk %d content not found in source text starting at %d: [%s]",
                            chunkIndex, searchFrom, content)
                    .isGreaterThanOrEqualTo(0);

            if (chunkIndex > 0) {
                assertThat(wordStarts)
                        .withFailMessage(
                                "Chunk %d starts mid-word at source index %d: [%s...]",
                                chunkIndex, foundAt, content.substring(0, Math.min(30, content.length())))
                        .contains(foundAt);
            }

            searchFrom = foundAt + 1;
        }
    }

    @Test
    void blankPageProducesNoChunks() {
        List<ChunkDraft> drafts = Chunker.chunk(List.of(new PageText(1, "   \n\n  ")));

        assertThat(drafts).isEmpty();
    }

    @Test
    void chunksFromDifferentPagesStayTaggedCorrectly() {
        List<ChunkDraft> drafts = Chunker.chunk(List.of(
                new PageText(1, "Page one content."),
                new PageText(2, "Page two content.")));

        assertThat(drafts).hasSize(2);
        assertThat(drafts.get(0).pageNumber()).isEqualTo(new PageNumber(1));
        assertThat(drafts.get(1).pageNumber()).isEqualTo(new PageNumber(2));
    }
}

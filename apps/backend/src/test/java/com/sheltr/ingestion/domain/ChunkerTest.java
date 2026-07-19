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
    void longUnbrokenTokenNearOverlapBoundaryDoesNotCauseMidWordChunkStart() {
        // Chunker's internals are CHUNK_SIZE = 1000 and OVERLAP = 100. This test builds a
        // prefix of exactly 840 chars ending in a space, followed immediately by a 160-char
        // space-free token (e.g. a URL/DOI/identifier) occupying source indices [840, 1000),
        // followed by a single space at index 1000 and then more prose. That layout means:
        //   - end (backward search for a space at/before index 1000) lands exactly at 1000,
        //     since text.charAt(1000) IS a space -- so chunk 0 runs all the way through the
        //     token.
        //   - nextStart = end - OVERLAP = 900, which sits inside the token (mid-word).
        //   - The token's only nearby space is at index 1000 == end, i.e. NOT strictly less
        //     than end. A forward search bounded by "< end" (the old, buggy behavior) would
        //     find this space and then discard it, leaving nextStart stuck at 900 -- a
        //     mid-word chunk start. The fixed, unbounded search must accept it and advance
        //     nextStart to 1001, the true next word boundary.
        String wordsSource = "The quick brown fox jumps over the lazy dog while onlookers murmur quietly "
                + "under the afternoon sun a programmer often refactors legacy code to remove duplication "
                + "improve readability and reduce future maintenance costs meteorologists predict scattered "
                + "thunderstorms across the valley tomorrow afternoon with temperatures dropping sharply "
                + "overnight historians frequently debate the causes of complex geopolitical conflicts "
                + "weighing economic cultural and ideological factors chefs carefully balance sweet sour "
                + "salty and bitter flavors when crafting an unforgettable tasting menu experience";
        String[] words = wordsSource.split(" ");

        final int prefixLength = 840; // CHUNK_SIZE (1000) minus the 160-char token below
        final int tokenLength = 160;  // longer than OVERLAP (100), so it spans nextStart..end

        StringBuilder prefixBuilder = new StringBuilder();
        int wi = 0;
        while (prefixBuilder.length() + words[wi % words.length].length() + 1 <= prefixLength) {
            prefixBuilder.append(words[wi % words.length]).append(' ');
            wi++;
        }
        int gap = prefixLength - prefixBuilder.length();
        if (gap > 0) {
            for (int k = 0; k < gap - 1; k++) {
                prefixBuilder.append('x');
            }
            prefixBuilder.append(' ');
        }
        String prefix = prefixBuilder.toString();
        assertThat(prefix).hasSize(prefixLength);
        assertThat(prefix.charAt(prefixLength - 1)).isEqualTo(' ');

        StringBuilder tokenBuilder = new StringBuilder();
        while (tokenBuilder.length() < tokenLength) {
            tokenBuilder.append("abcdefghij");
        }
        String token = tokenBuilder.substring(0, tokenLength);
        assertThat(token).doesNotContain(" ");

        StringBuilder suffixBuilder = new StringBuilder();
        int si = 0;
        while (suffixBuilder.length() < 3500) {
            suffixBuilder.append(words[si % words.length]).append(' ');
            si++;
        }
        String suffix = suffixBuilder.toString().trim();

        String longText = prefix + token + " " + suffix;

        // Sanity-check the construction lines up with Chunker's internal constants before
        // relying on it to exercise the bug.
        assertThat(longText.charAt(839)).isEqualTo(' ');
        assertThat(longText.substring(840, 1000)).isEqualTo(token);
        assertThat(longText.charAt(1000)).isEqualTo(' ');

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

        assertThat(drafts.size()).isGreaterThan(1);
        // The token must survive intact as the tail of the very first chunk -- proving no
        // chunk boundary cut through it.
        assertThat(drafts.get(0).content()).isEqualTo(prefix + token);

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
                assertThat(foundAt < 840 || foundAt >= 1000)
                        .withFailMessage("Chunk %d starts inside the long token at source index %d",
                                chunkIndex, foundAt)
                        .isTrue();
            }

            if (chunkIndex == 1) {
                // Under the old bounded forward-search, nextStart would have been left at
                // 900 (mid-token). The fix must instead land exactly one past the token's
                // only nearby trailing space, at index 1001.
                assertThat(foundAt).isEqualTo(1001);
            }

            searchFrom = foundAt + 1;
        }
    }

    @Test
    void unbrokenRunLongerThanChunkSizeIsNeverSilentlyDroppedFromAnyChunk() {
        // A degenerate but real-world-possible case: an unbroken token (URL, base64 blob,
        // malformed-extraction run) far longer than CHUNK_SIZE. The very first chunk's `end`
        // is hard-cut mid-token (the already-accepted single-long-token fallback), which
        // means the subsequent forward search for a safe next-start boundary must search
        // past `end` to find the token's actual terminating space, far beyond the current
        // chunk. The fix must extend the CURRENT chunk to swallow the full run rather than
        // silently skipping the span between the old `end` and the new `nextStart` -
        // otherwise that entire span (thousands of characters) would vanish from every chunk.
        String longRun = "x".repeat(6000);
        String suffix = "normal prose continues after the long run to prove no content was lost here today.";
        String longText = longRun + " " + suffix;

        List<ChunkDraft> drafts = Chunker.chunk(List.of(new PageText(1, longText)));

        assertThat(drafts).isNotEmpty();
        // The entire 6000-character unbroken run must survive intact in the first chunk -
        // not truncated at CHUNK_SIZE (1000) and not missing any span in the middle.
        assertThat(drafts.get(0).content()).isEqualTo(longRun);
        assertThat(drafts.size()).isGreaterThanOrEqualTo(2);
        assertThat(drafts.get(1).content()).startsWith("normal");
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

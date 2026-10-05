package com.sheltr.publisher.chunking

import com.sheltr.publisher.text.PageText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChunkerTest {

    @Test
    fun shortTextProducesOneChunkTaggedWithItsPage() {
        val drafts = Chunker.chunk(listOf(PageText(3, "Boil water for one minute.")))

        assertEquals(1, drafts.size)
        assertEquals("Boil water for one minute.", drafts[0].content)
        assertEquals(3, drafts[0].pageNumber)
    }

    @Test
    fun longTextSplitsIntoMultipleChunksWithoutSplittingWords() {
        val longText = "word ".repeat(400) // 2000 chars, well over the 1000-char chunk size
        val drafts = Chunker.chunk(listOf(PageText(1, longText)))

        assertTrue(drafts.size > 1)
        for (draft in drafts) {
            assertFalse(draft.content.startsWith(" "))
            assertFalse(draft.content.endsWith(" "))
            assertFalse(draft.content.contains("wor d")) // no mid-word split artifacts
            assertFalse(draft.content.contains("wo rd"))
        }
    }

    @Test
    fun realisticProseNeverSplitsAChunkStartMidWord() {
        // Non-periodic, sentence-like text with varying word lengths, so chunk offsets do not
        // all land on a space and the start-boundary word-safety rule is actually exercised.
        val sentences = arrayOf(
            "The quick brown fox jumps over the lazy dog while onlookers murmur quietly under the afternoon sun.",
            "A programmer often refactors legacy code to remove duplication, improve readability, and reduce future maintenance costs.",
            "Meteorologists predict scattered thunderstorms across the valley tomorrow afternoon, with temperatures dropping sharply overnight.",
            "Historians frequently debate the causes of complex geopolitical conflicts, weighing economic, cultural, and ideological factors.",
            "Chefs carefully balance sweet, sour, salty, and bitter flavors when crafting an unforgettable tasting menu experience.",
            "Astronomers recently discovered an unusually bright exoplanet orbiting a distant binary star system many light-years away.",
        )
        val builder = StringBuilder()
        var i = 0
        while (builder.length < 4500) {
            if (builder.isNotEmpty()) {
                builder.append(' ')
            }
            builder.append(sentences[i % sentences.size])
            i++
        }
        val longText = builder.toString()

        val wordStarts = wordStartsOf(longText)

        val drafts = Chunker.chunk(listOf(PageText(1, longText)))

        assertTrue(drafts.size > 2)

        var searchFrom = 0
        for (chunkIndex in drafts.indices) {
            val content = drafts[chunkIndex].content
            val foundAt = longText.indexOf(content, searchFrom)
            assertTrue(
                foundAt >= 0,
                "Chunk $chunkIndex content not found in source text starting at $searchFrom: [$content]",
            )

            if (chunkIndex > 0) {
                assertTrue(
                    foundAt in wordStarts,
                    "Chunk $chunkIndex starts mid-word at source index $foundAt: [${content.take(30)}...]",
                )
            }

            searchFrom = foundAt + 1
        }
    }

    @Test
    fun longUnbrokenTokenNearOverlapBoundaryDoesNotCauseMidWordChunkStart() {
        // CHUNK_SIZE = 1000 and OVERLAP = 100. The prefix is exactly 840 chars ending in a
        // space, then a 160-char space-free token at [840, 1000), then a space at 1000, then prose.
        // The fixed, unbounded forward search must accept the space at 1000 and place the next
        // chunk start at 1001, not at 900 (mid-token).
        val wordsSource = "The quick brown fox jumps over the lazy dog while onlookers murmur quietly " +
            "under the afternoon sun a programmer often refactors legacy code to remove duplication " +
            "improve readability and reduce future maintenance costs meteorologists predict scattered " +
            "thunderstorms across the valley tomorrow afternoon with temperatures dropping sharply " +
            "overnight historians frequently debate the causes of complex geopolitical conflicts " +
            "weighing economic cultural and ideological factors chefs carefully balance sweet sour " +
            "salty and bitter flavors when crafting an unforgettable tasting menu experience"
        val words = wordsSource.split(" ")

        val prefixLength = 840 // CHUNK_SIZE (1000) minus the 160-char token below
        val tokenLength = 160 // longer than OVERLAP (100), so it spans nextStart..end

        val prefixBuilder = StringBuilder()
        var wi = 0
        while (prefixBuilder.length + words[wi % words.size].length + 1 <= prefixLength) {
            prefixBuilder.append(words[wi % words.size]).append(' ')
            wi++
        }
        val gap = prefixLength - prefixBuilder.length
        if (gap > 0) {
            repeat(gap - 1) { prefixBuilder.append('x') }
            prefixBuilder.append(' ')
        }
        val prefix = prefixBuilder.toString()
        assertEquals(prefixLength, prefix.length)
        assertEquals(' ', prefix[prefixLength - 1])

        val tokenBuilder = StringBuilder()
        while (tokenBuilder.length < tokenLength) {
            tokenBuilder.append("abcdefghij")
        }
        val token = tokenBuilder.substring(0, tokenLength)
        assertFalse(token.contains(" "))

        val suffixBuilder = StringBuilder()
        var si = 0
        while (suffixBuilder.length < 3500) {
            suffixBuilder.append(words[si % words.size]).append(' ')
            si++
        }
        val suffix = suffixBuilder.toString().trim()

        val longText = prefix + token + " " + suffix

        // Sanity-check the construction lines up with Chunker's internal constants.
        assertEquals(' ', longText[839])
        assertEquals(token, longText.substring(840, 1000))
        assertEquals(' ', longText[1000])

        val wordStarts = wordStartsOf(longText)

        val drafts = Chunker.chunk(listOf(PageText(1, longText)))

        assertTrue(drafts.size > 1)
        // The token must survive intact as the tail of the very first chunk.
        assertEquals(prefix + token, drafts[0].content)

        var searchFrom = 0
        for (chunkIndex in drafts.indices) {
            val content = drafts[chunkIndex].content
            val foundAt = longText.indexOf(content, searchFrom)
            assertTrue(
                foundAt >= 0,
                "Chunk $chunkIndex content not found in source text starting at $searchFrom: [$content]",
            )

            if (chunkIndex > 0) {
                assertTrue(
                    foundAt in wordStarts,
                    "Chunk $chunkIndex starts mid-word at source index $foundAt: [${content.take(30)}...]",
                )
                assertTrue(
                    foundAt < 840 || foundAt >= 1000,
                    "Chunk $chunkIndex starts inside the long token at source index $foundAt",
                )
            }

            if (chunkIndex == 1) {
                // Under the old bounded forward search, nextStart would have been 900 (mid-token).
                assertEquals(1001, foundAt)
            }

            searchFrom = foundAt + 1
        }
    }

    @Test
    fun unbrokenRunLongerThanChunkSizeIsNeverSilentlyDroppedFromAnyChunk() {
        // An unbroken run far longer than CHUNK_SIZE. The current chunk must be extended to
        // swallow the full run rather than skipping the span between the hard cut and nextStart.
        val longRun = "x".repeat(6000)
        val suffix = "normal prose continues after the long run to prove no content was lost here today."
        val longText = "$longRun $suffix"

        val drafts = Chunker.chunk(listOf(PageText(1, longText)))

        assertTrue(drafts.isNotEmpty())
        // The entire 6000-character run must survive intact in the first chunk.
        assertEquals(longRun, drafts[0].content)
        assertTrue(drafts.size >= 2)
        assertTrue(drafts[1].content.startsWith("normal"))
    }

    @Test
    fun blankPageProducesNoChunks() {
        val drafts = Chunker.chunk(listOf(PageText(1, "   \n\n  ")))

        assertTrue(drafts.isEmpty())
    }

    @Test
    fun chunksFromDifferentPagesStayTaggedCorrectly() {
        val drafts = Chunker.chunk(
            listOf(
                PageText(1, "Page one content."),
                PageText(2, "Page two content."),
            ),
        )

        assertEquals(2, drafts.size)
        assertEquals(1, drafts[0].pageNumber)
        assertEquals(2, drafts[1].pageNumber)
    }

    // Added beyond the Java suite: the HTML path (chunkUnpaged) has no Java test.
    @Test
    fun unpagedTextProducesChunksWithNullPageNumber() {
        val drafts = Chunker.chunkUnpaged("Boil water for one minute. " + "word ".repeat(400))

        assertTrue(drafts.size > 1)
        for (draft in drafts) {
            assertEquals(null, draft.pageNumber)
        }
    }

    private fun wordStartsOf(text: String): Set<Int> {
        val wordStarts = HashSet<Int>()
        for (pos in text.indices) {
            val atTextStart = pos == 0
            val afterSpace = pos > 0 && text[pos - 1] == ' '
            val isNotSpace = text[pos] != ' '
            if ((atTextStart || afterSpace) && isNotSpace) {
                wordStarts.add(pos)
            }
        }
        return wordStarts
    }
}

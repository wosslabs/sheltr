package com.sheltr.ingestion.domain;

import com.sheltr.knowledge.domain.PageNumber;
import org.junit.jupiter.api.Test;

import java.util.List;

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

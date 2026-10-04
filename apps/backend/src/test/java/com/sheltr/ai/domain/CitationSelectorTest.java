package com.sheltr.ai.domain;

import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.DocumentId;
import com.sheltr.search.domain.RankedResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CitationSelectorTest {

    private final RankedResult first = passage();
    private final RankedResult second = passage();
    private final RankedResult third = passage();
    private final List<RankedResult> passages = List.of(first, second, third);
    private final List<Citation> citations = List.of(citation(first, "One"), citation(second, "Two"),
            citation(third, "Three"));

    @Test
    void keepsOnlyThePassagesTheAnswerCites() {
        List<Citation> used = CitationSelector.select("Boil it [3]. Then cool it [1].", passages, citations);

        assertThat(used).extracting(Citation::sourceTitle).containsExactly("One", "Three");
    }

    @Test
    void ignoresPassageNumbersThatDoNotExist() {
        assertThat(CitationSelector.select("Boil it [7]. Or [0].", passages, citations)).isEmpty();
    }

    @Test
    void returnsNothingWhenTheAnswerCitesNothing() {
        assertThat(CitationSelector.select("Boil it for one minute.", passages, citations)).isEmpty();
        assertThat(CitationSelector.select(null, passages, citations)).isEmpty();
    }

    @Test
    void skipsACitedPassageWhoseDocumentHadNoCitation() {
        List<Citation> withoutSecond = List.of(citation(first, "One"), citation(third, "Three"));

        assertThat(CitationSelector.select("See [2] and [3].", passages, withoutSecond))
                .extracting(Citation::sourceTitle).containsExactly("Three");
    }

    private static RankedResult passage() {
        return new RankedResult(new ChunkId(UUID.randomUUID()), new DocumentId(UUID.randomUUID()), "text", 0.8);
    }

    private static Citation citation(RankedResult passage, String title) {
        return new Citation(passage.documentId(), passage.chunkId(), title, "https://example.org", null);
    }
}

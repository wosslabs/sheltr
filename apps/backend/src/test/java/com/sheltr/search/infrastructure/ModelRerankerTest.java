package com.sheltr.search.infrastructure;

import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.DocumentId;
import com.sheltr.search.domain.NoOpReranker;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.SearchQuery;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ModelRerankerTest {

    private static final SearchQuery QUERY = new SearchQuery("Is flood water safe to drink?");
    private final List<RankedResult> candidates = List.of(passage("one"), passage("two"), passage("three"));

    @Test
    void ordersCandidatesAsTheModelRanksThem() {
        List<RankedResult> results = new ModelReranker(prompt -> "3, 1, 2").rerank(QUERY, candidates, 2);

        assertThat(results).extracting(RankedResult::content).containsExactly("three", "one");
    }

    @Test
    void sendsTheQuestionAndNumberedPassagesToTheModel() {
        StringBuilder seen = new StringBuilder();
        new ModelReranker(prompt -> {
            seen.append(prompt);
            return "1";
        }).rerank(QUERY, candidates, 3);

        assertThat(seen.toString()).contains("Is flood water safe to drink?", "[1] one", "[3] three");
    }

    @Test
    void ignoresNumbersOutOfRangeAndRepeatsThenAppendsWhatTheModelLeftOut() {
        List<RankedResult> results = new ModelReranker(prompt -> "2, 9, 2, 0").rerank(QUERY, candidates, 3);

        assertThat(results).extracting(RankedResult::content).containsExactly("two", "one", "three");
    }

    @Test
    void keepsTheOriginalOrderWhenTheReplyHasNoNumbers() {
        List<RankedResult> results = new ModelReranker(prompt -> "I cannot rank these.")
                .rerank(QUERY, candidates, 2);

        assertThat(results).extracting(RankedResult::content).containsExactly("one", "two");
    }

    @Test
    void keepsTheOriginalOrderWhenTheModelFails() {
        List<RankedResult> results = new ModelReranker(prompt -> {
            throw new IllegalStateException("model unavailable");
        }).rerank(QUERY, candidates, 2);

        assertThat(results).extracting(RankedResult::content).containsExactly("one", "two");
    }

    @Test
    void noOpRerankerTruncatesWithoutReordering() {
        assertThat(new NoOpReranker().rerank(QUERY, candidates, 2))
                .extracting(RankedResult::content).containsExactly("one", "two");
    }

    private static RankedResult passage(String content) {
        return new RankedResult(new ChunkId(UUID.randomUUID()), new DocumentId(UUID.randomUUID()), content, 0.8);
    }
}

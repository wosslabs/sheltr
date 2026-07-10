package com.sheltr.ai.domain;

import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.DocumentId;
import com.sheltr.search.domain.RankedResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RelevanceGateTest {

    @Test
    void passesWhenTopScoreAtOrAboveThreshold() {
        List<RankedResult> results = List.of(
                new RankedResult(new ChunkId(UUID.randomUUID()), new DocumentId(UUID.randomUUID()), "text", 0.80));

        assertThat(RelevanceGate.passesThreshold(results, 0.75)).isTrue();
    }

    @Test
    void failsWhenTopScoreBelowThreshold() {
        List<RankedResult> results = List.of(
                new RankedResult(new ChunkId(UUID.randomUUID()), new DocumentId(UUID.randomUUID()), "text", 0.60));

        assertThat(RelevanceGate.passesThreshold(results, 0.75)).isFalse();
    }

    @Test
    void failsWhenNoResults() {
        assertThat(RelevanceGate.passesThreshold(List.of(), 0.75)).isFalse();
    }
}

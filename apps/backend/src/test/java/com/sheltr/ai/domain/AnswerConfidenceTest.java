package com.sheltr.ai.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerConfidenceTest {

    @Test
    void highWhenScoreAtOrAboveNinetyPercent() {
        assertThat(AnswerConfidence.fromSimilarity(0.95).level()).isEqualTo(ConfidenceLevel.HIGH);
    }

    @Test
    void mediumWhenScoreBetweenSeventyFiveAndNinety() {
        assertThat(AnswerConfidence.fromSimilarity(0.80).level()).isEqualTo(ConfidenceLevel.MEDIUM);
    }

    @Test
    void lowWhenScoreBelowSeventyFive() {
        // Unreachable via the current 0.75 RelevanceGate default in ordinary operation,
        // but defined for correctness and for a future lower threshold.
        assertThat(AnswerConfidence.fromSimilarity(0.50).level()).isEqualTo(ConfidenceLevel.LOW);
    }
}

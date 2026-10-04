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
        // Unreachable via the current 0.75 ThresholdRelevanceJudge default in ordinary operation,
        // but defined for correctness and for a future lower threshold.
        assertThat(AnswerConfidence.fromSimilarity(0.50).level()).isEqualTo(ConfidenceLevel.LOW);
    }

    @Test
    void usesTheGivenBandsInsteadOfTheDefaults() {
        ConfidenceBands bands = new ConfidenceBands(0.60, 0.40);

        assertThat(AnswerConfidence.of(0.65, bands).level()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(AnswerConfidence.of(0.45, bands).level()).isEqualTo(ConfidenceLevel.MEDIUM);
        assertThat(AnswerConfidence.of(0.10, bands).level()).isEqualTo(ConfidenceLevel.LOW);
    }
}

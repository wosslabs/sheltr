package com.sheltr.ai.domain;

public record AnswerConfidence(double score, ConfidenceLevel level) {
    /**
     * KNOWN LIMITATION (see ADR-0011 Consequences): these bands (0.90/0.75) are hardcoded and
     * were tuned against Gemini's embedding-score distribution, not {@code local}'s. Since
     * {@code local}'s relevance threshold (0.65, see application-local.yml) sits below the
     * MEDIUM band, most {@code local}-profile answers will report LOW confidence even when
     * they're the best available match. Not yet profile-scoped the way the relevance threshold
     * was — a natural follow-up, not addressed in this migration.
     */
    public static AnswerConfidence fromSimilarity(double topScore) {
        ConfidenceLevel level = topScore >= 0.90 ? ConfidenceLevel.HIGH
                : topScore >= 0.75 ? ConfidenceLevel.MEDIUM
                : ConfidenceLevel.LOW;
        return new AnswerConfidence(topScore, level);
    }
}

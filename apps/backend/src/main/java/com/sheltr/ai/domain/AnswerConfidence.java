package com.sheltr.ai.domain;

public record AnswerConfidence(double score, ConfidenceLevel level) {

    public static AnswerConfidence of(double probability, ConfidenceBands bands) {
        ConfidenceLevel level = probability >= bands.high() ? ConfidenceLevel.HIGH
                : probability >= bands.medium() ? ConfidenceLevel.MEDIUM
                : ConfidenceLevel.LOW;
        return new AnswerConfidence(probability, level);
    }

    /** Convenience for tests and stored fixtures; production code passes configured bands to {@link #of}. */
    public static AnswerConfidence fromSimilarity(double topScore) {
        return of(topScore, ConfidenceBands.DEFAULT);
    }
}

package com.sheltr.ai.domain;

public record AnswerConfidence(double score, ConfidenceLevel level) {
    public static AnswerConfidence fromSimilarity(double topScore) {
        ConfidenceLevel level = topScore >= 0.90 ? ConfidenceLevel.HIGH
                : topScore >= 0.75 ? ConfidenceLevel.MEDIUM
                : ConfidenceLevel.LOW;
        return new AnswerConfidence(topScore, level);
    }
}

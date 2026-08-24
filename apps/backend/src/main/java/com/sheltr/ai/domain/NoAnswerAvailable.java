package com.sheltr.ai.domain;

public record NoAnswerAvailable() implements AnswerOutcome {
    public static final String MESSAGE =
            "I don't have enough reliable information from trusted sources to answer that question.";
}

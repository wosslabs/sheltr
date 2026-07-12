package com.sheltr.ai.application;

public class AnswerGenerationFailedException extends RuntimeException {
    public AnswerGenerationFailedException(String reason) {
        super(reason);
    }
}

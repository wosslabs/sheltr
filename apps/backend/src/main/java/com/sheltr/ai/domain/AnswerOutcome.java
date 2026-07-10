package com.sheltr.ai.domain;

public sealed interface AnswerOutcome permits AnswerGenerated, NoAnswerAvailable, AnswerGenerationFailed {
}

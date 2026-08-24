package com.sheltr.ai.application;

import com.sheltr.ai.domain.AnswerConfidence;
import com.sheltr.ai.domain.Citation;

import java.util.List;

public record AnswerContext(String prompt, List<Citation> citations, AnswerConfidence confidence) {
    public AnswerContext {
        citations = List.copyOf(citations == null ? List.of() : citations);
    }
}

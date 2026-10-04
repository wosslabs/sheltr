package com.sheltr.ai.infrastructure;

import com.sheltr.ai.application.AnswerContext;
import com.sheltr.ai.application.AnswerContextBuilder;
import com.sheltr.ai.domain.*;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.SearchQuery;

import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

public class SpringAiAnswerGeneratorAdapter implements AnswerGenerator {

    private final UnaryOperator<String> llm;
    private final AnswerContextBuilder answerContextBuilder;

    public SpringAiAnswerGeneratorAdapter(UnaryOperator<String> llm, AnswerContextBuilder answerContextBuilder) {
        this.llm = llm;
        this.answerContextBuilder = answerContextBuilder;
    }

    @Override
    public AnswerOutcome generate(SearchQuery query, List<RankedResult> results, RelevanceVerdict verdict) {
        AnswerContext context = answerContextBuilder.buildContext(query, results, verdict);
        String answerText;
        try {
            answerText = llm.apply(context.prompt());
        } catch (Exception e) {
            return new AnswerGenerationFailed("Answer generation failed: " + e.getMessage());
        }

        // ADR-0008: an answer that cannot be tied to a retrieved passage is never shown.
        List<Citation> used = CitationSelector.select(answerText, results, context.citations());
        if (used.isEmpty()) {
            return new NoAnswerAvailable();
        }

        Message message = new Message(
                new MessageId(UUID.randomUUID()), MessageRole.ASSISTANT, new MessageContent(answerText),
                used, context.confidence(),
                results.stream().map(RankedResult::chunkId).toList());

        return new AnswerGenerated(message);
    }
}

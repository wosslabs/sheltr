package com.sheltr.ai.infrastructure;

import com.sheltr.ai.application.AnswerContext;
import com.sheltr.ai.application.AnswerContextBuilder;
import com.sheltr.ai.domain.*;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.SearchQuery;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.UUID;

public class SpringAiAnswerGeneratorAdapter implements AnswerGenerator {

    private final ChatModel chatModel;
    private final AnswerContextBuilder answerContextBuilder;

    public SpringAiAnswerGeneratorAdapter(ChatModel chatModel, AnswerContextBuilder answerContextBuilder) {
        this.chatModel = chatModel;
        this.answerContextBuilder = answerContextBuilder;
    }

    @Override
    public AnswerOutcome generate(SearchQuery query, List<RankedResult> results) {
        AnswerContext context = answerContextBuilder.buildContext(query, results);
        String answerText;
        try {
            answerText = chatModel.call(context.prompt());
        } catch (Exception e) {
            return new AnswerGenerationFailed("Answer generation failed: " + e.getMessage());
        }

        Message message = new Message(
                new MessageId(UUID.randomUUID()), MessageRole.ASSISTANT, new MessageContent(answerText),
                context.citations(), context.confidence(),
                results.stream().map(RankedResult::chunkId).toList());

        return new AnswerGenerated(message);
    }
}

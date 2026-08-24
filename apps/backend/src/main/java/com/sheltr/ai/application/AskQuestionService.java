package com.sheltr.ai.application;

import com.sheltr.ai.domain.*;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.Retriever;
import com.sheltr.search.domain.SearchQuery;

import java.util.List;
import java.util.UUID;

public class AskQuestionService {

    private static final int TOP_K = 5;

    private final Retriever retriever;
    private final AnswerGenerator answerGenerator;
    private final ConversationRepository conversationRepository;
    private final double relevanceThreshold;

    public AskQuestionService(Retriever retriever, AnswerGenerator answerGenerator,
                               ConversationRepository conversationRepository, double relevanceThreshold) {
        this.retriever = retriever;
        this.answerGenerator = answerGenerator;
        this.conversationRepository = conversationRepository;
        this.relevanceThreshold = relevanceThreshold;
    }

    public Message ask(ConversationId conversationId, String questionText) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId));

        conversation.addMessage(new Message(
                new MessageId(UUID.randomUUID()), MessageRole.USER, new MessageContent(questionText),
                List.of(), null, List.of()));

        SearchQuery query = new SearchQuery(questionText);
        List<RankedResult> results = retriever.retrieve(query, TOP_K);

        Message assistantMessage;
        if (!RelevanceGate.passesThreshold(results, relevanceThreshold)) {
            assistantMessage = fallbackMessage(NoAnswerAvailable.MESSAGE);
            conversation.addMessage(assistantMessage);
            conversationRepository.save(conversation);
        } else {
            AnswerOutcome outcome = answerGenerator.generate(query, results);
            if (outcome instanceof AnswerGenerationFailed failed) {
                // Persist the user's question even though generation failed, so
                // conversation history reflects what was actually asked; do not
                // persist a fabricated assistant message for a real failure.
                conversationRepository.save(conversation);
                throw new AnswerGenerationFailedException(failed.reason());
            }
            assistantMessage = switch (outcome) {
                case AnswerGenerated generated -> generated.message();
                case NoAnswerAvailable ignored -> fallbackMessage(NoAnswerAvailable.MESSAGE);
                case AnswerGenerationFailed ignored -> throw new IllegalStateException("handled above");
            };
            conversation.addMessage(assistantMessage);
            conversationRepository.save(conversation);
        }

        return assistantMessage;
    }

    private static Message fallbackMessage(String text) {
        return new Message(new MessageId(UUID.randomUUID()), MessageRole.ASSISTANT,
                new MessageContent(text), List.of(), null, List.of());
    }
}

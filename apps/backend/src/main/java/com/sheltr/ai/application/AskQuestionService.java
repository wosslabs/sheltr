package com.sheltr.ai.application;

import com.sheltr.ai.domain.*;
import com.sheltr.interfaces.LimitExceededException;
import com.sheltr.knowledge.domain.DisasterType;
import com.sheltr.search.domain.SearchQuery;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class AskQuestionService {

    private final AnswerPipeline pipeline;
    private final ConversationRepository conversationRepository;
    private final int maxAsksPerDay;
    private final Clock clock;

    public AskQuestionService(AnswerPipeline pipeline, ConversationRepository conversationRepository,
                              int maxAsksPerDay, Clock clock) {
        this.pipeline = pipeline;
        this.conversationRepository = conversationRepository;
        this.maxAsksPerDay = maxAsksPerDay;
        this.clock = clock;
    }

    public Message ask(ConversationId conversationId, String questionText) {
        return ask(conversationId, questionText, Set.of());
    }

    public Message ask(ConversationId conversationId, String questionText, Set<DisasterType> activeHazards) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId));

        // Checked before retrieval or generation so a refused request costs nothing.
        long askedToday = conversationRepository.countUserMessagesSince(startOfTodayUtc());
        if (!AskQuota.allows(askedToday, maxAsksPerDay)) {
            throw new LimitExceededException("Daily question limit reached. Try again tomorrow.");
        }

        conversation.addMessage(new Message(
                new MessageId(UUID.randomUUID()), MessageRole.USER, new MessageContent(questionText),
                List.of(), null, List.of()));

        AnswerOutcome outcome = pipeline.answer(new SearchQuery(questionText, activeHazards)).outcome();
        if (outcome instanceof AnswerGenerationFailed failed) {
            // Persist the user's question even though generation failed, so conversation
            // history reflects what was actually asked; do not persist a fabricated
            // assistant message for a real failure.
            conversationRepository.save(conversation);
            throw new AnswerGenerationFailedException(failed.reason());
        }
        Message assistantMessage = switch (outcome) {
            case AnswerGenerated generated -> generated.message();
            case NoAnswerAvailable ignored -> fallbackMessage(NoAnswerAvailable.MESSAGE);
            case AnswerGenerationFailed ignored -> throw new IllegalStateException("handled above");
        };
        conversation.addMessage(assistantMessage);
        conversationRepository.save(conversation);
        return assistantMessage;
    }

    private Instant startOfTodayUtc() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC)).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Message fallbackMessage(String text) {
        return new Message(new MessageId(UUID.randomUUID()), MessageRole.ASSISTANT,
                new MessageContent(text), List.of(), null, List.of());
    }
}

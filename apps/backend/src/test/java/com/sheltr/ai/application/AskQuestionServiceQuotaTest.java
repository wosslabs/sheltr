package com.sheltr.ai.application;

import com.sheltr.ai.domain.*;
import com.sheltr.interfaces.LimitExceededException;
import com.sheltr.knowledge.domain.DocumentId;
import com.sheltr.search.domain.NoOpReranker;
import com.sheltr.search.domain.Retriever;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AskQuestionServiceQuotaTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-04T12:00:00Z"), ZoneOffset.UTC);

    private static AskQuestionService service(Retriever retriever, ConversationRepository conversations, int cap) {
        AnswerGenerator generator = (query, results, verdict) -> {
            throw new AssertionError("AnswerGenerator must not be called when the cap is reached");
        };
        return new AskQuestionService(new AnswerPipeline(retriever, new NoOpReranker(),
                new ThresholdRelevanceJudge(0.75), generator), conversations, cap, CLOCK);
    }

    @Test
    void refusesBeforeRetrievalOrGenerationWhenDailyCapIsReached() {
        ConversationId conversationId = new ConversationId(UUID.randomUUID());
        CountingConversationRepository conversations = new CountingConversationRepository(
                new Conversation(conversationId, null), 5);
        Retriever retriever = (query, topK) -> {
            throw new AssertionError("Retriever must not be called when the cap is reached");
        };

        AskQuestionService service = service(retriever, conversations, 5);

        assertThatThrownBy(() -> service.ask(conversationId, "Is flood water safe to drink?"))
                .isInstanceOf(LimitExceededException.class)
                .hasMessage("Daily question limit reached. Try again tomorrow.");
        assertThat(conversations.saved).isNull();
    }

    @Test
    void countsUserMessagesSinceMidnightUtcOfTheClockDay() {
        ConversationId conversationId = new ConversationId(UUID.randomUUID());
        CountingConversationRepository conversations = new CountingConversationRepository(
                new Conversation(conversationId, null), 0);
        Retriever retriever = (query, topK) -> List.of();

        service(retriever, conversations, 5).ask(conversationId, "question");

        assertThat(conversations.lastSince).isEqualTo(Instant.parse("2026-10-04T00:00:00Z"));
    }

    private static class CountingConversationRepository implements ConversationRepository {
        private final Conversation stored;
        private final long count;
        Conversation saved;
        Instant lastSince;

        CountingConversationRepository(Conversation stored, long count) {
            this.stored = stored;
            this.count = count;
        }

        @Override
        public Optional<Conversation> findById(ConversationId id) { return Optional.ofNullable(stored); }

        @Override
        public void save(Conversation conversation) { this.saved = conversation; }

        @Override
        public List<Citation> findCitationsByDocumentId(DocumentId documentId) { return List.of(); }

        @Override
        public long countUserMessagesSince(Instant since) {
            this.lastSince = since;
            return count;
        }
    }
}

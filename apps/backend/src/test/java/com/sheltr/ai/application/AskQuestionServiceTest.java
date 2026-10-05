package com.sheltr.ai.application;

import com.sheltr.ai.domain.*;
import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.DocumentId;
import com.sheltr.search.domain.NoOpReranker;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.Retriever;
import com.sheltr.search.domain.SearchQuery;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AskQuestionServiceTest {

    private static final double THRESHOLD = 0.75;

    private static AskQuestionService service(Retriever retriever, AnswerGenerator answerGenerator,
                                              ConversationRepository conversations) {
        return new AskQuestionService(new AnswerPipeline(retriever, new NoOpReranker(),
                new ThresholdRelevanceJudge(THRESHOLD), answerGenerator), conversations,
                Integer.MAX_VALUE, Clock.systemUTC());
    }

    @Test
    void returnsGeneratedAnswerWhenRetrievalIsRelevant() {
        ConversationId conversationId = new ConversationId(UUID.randomUUID());
        FakeConversationRepository conversations = new FakeConversationRepository(
                new Conversation(conversationId, null));
        RankedResult relevantResult = new RankedResult(
                new ChunkId(UUID.randomUUID()), new DocumentId(UUID.randomUUID()), "Boil water first.", 0.90);
        Retriever retriever = (query, topK) -> List.of(relevantResult);
        Message expectedAnswer = new Message(new MessageId(UUID.randomUUID()), MessageRole.ASSISTANT,
                new MessageContent("Boil the water for one minute."), List.of(), AnswerConfidence.fromSimilarity(0.90), List.of());
        AnswerGenerator answerGenerator = (query, results, verdict) -> new AnswerGenerated(expectedAnswer);

        AskQuestionService service = service(retriever, answerGenerator, conversations);

        Message actual = service.ask(conversationId, "Is flood water safe to drink?");

        assertThat(actual).isEqualTo(expectedAnswer);
        assertThat(conversations.saved.messages()).hasSize(2); // user + assistant
    }

    @Test
    void returnsFallbackWithoutCallingGeneratorWhenBelowThreshold() {
        ConversationId conversationId = new ConversationId(UUID.randomUUID());
        FakeConversationRepository conversations = new FakeConversationRepository(
                new Conversation(conversationId, null));
        RankedResult irrelevantResult = new RankedResult(
                new ChunkId(UUID.randomUUID()), new DocumentId(UUID.randomUUID()), "Unrelated text.", 0.40);
        Retriever retriever = (query, topK) -> List.of(irrelevantResult);
        AnswerGenerator answerGenerator = (query, results, verdict) -> {
            throw new AssertionError("AnswerGenerator must not be called when below threshold");
        };

        AskQuestionService service = service(retriever, answerGenerator, conversations);

        Message actual = service.ask(conversationId, "What is the capital of France?");

        assertThat(actual.role()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(actual.citations()).isEmpty();
        assertThat(actual.content().value()).isEqualTo(NoAnswerAvailable.MESSAGE);
    }

    @Test
    void throwsAnswerGenerationFailedExceptionWhenGenerationFails() {
        ConversationId conversationId = new ConversationId(UUID.randomUUID());
        FakeConversationRepository conversations = new FakeConversationRepository(
                new Conversation(conversationId, null));
        RankedResult relevantResult = new RankedResult(
                new ChunkId(UUID.randomUUID()), new DocumentId(UUID.randomUUID()), "Boil water first.", 0.90);
        Retriever retriever = (query, topK) -> List.of(relevantResult);
        AnswerGenerator answerGenerator = (query, results, verdict) -> new AnswerGenerationFailed("Gemini timed out");

        AskQuestionService service = service(retriever, answerGenerator, conversations);

        assertThatThrownBy(() -> service.ask(conversationId, "Is flood water safe to drink?"))
                .isInstanceOf(AnswerGenerationFailedException.class)
                .hasMessageContaining("Gemini timed out");
    }

    @Test
    void throwsWhenConversationDoesNotExist() {
        ConversationId conversationId = new ConversationId(UUID.randomUUID());
        FakeConversationRepository conversations = new FakeConversationRepository(null);
        Retriever retriever = (query, topK) -> List.of();
        AnswerGenerator answerGenerator = (query, results, verdict) -> new AnswerGenerationFailed("unused");

        AskQuestionService service = service(retriever, answerGenerator, conversations);

        assertThatThrownBy(() -> service.ask(conversationId, "question"))
                .isInstanceOf(ConversationNotFoundException.class);
    }

    private static class FakeConversationRepository implements ConversationRepository {
        private Conversation stored;
        Conversation saved;

        FakeConversationRepository(Conversation stored) { this.stored = stored; }

        @Override
        public Optional<Conversation> findById(ConversationId id) { return Optional.ofNullable(stored); }

        @Override
        public void save(Conversation conversation) { this.saved = conversation; }

        @Override
        public List<Citation> findCitationsByDocumentId(DocumentId documentId) { return List.of(); }

        @Override
        public long countUserMessagesSince(Instant since) { return 0; }
    }
}

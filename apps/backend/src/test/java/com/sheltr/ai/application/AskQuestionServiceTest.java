package com.sheltr.ai.application;

import com.sheltr.ai.domain.*;
import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.DocumentId;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.Retriever;
import com.sheltr.search.domain.SearchQuery;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AskQuestionServiceTest {

    private static final double THRESHOLD = 0.75;

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
        AnswerGenerator answerGenerator = (query, results) -> new AnswerGenerated(expectedAnswer);

        AskQuestionService service = new AskQuestionService(retriever, answerGenerator, conversations, THRESHOLD);

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
        AnswerGenerator answerGenerator = (query, results) -> {
            throw new AssertionError("AnswerGenerator must not be called when below threshold");
        };

        AskQuestionService service = new AskQuestionService(retriever, answerGenerator, conversations, THRESHOLD);

        Message actual = service.ask(conversationId, "What is the capital of France?");

        assertThat(actual.role()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(actual.citations()).isEmpty();
    }

    @Test
    void throwsAnswerGenerationFailedExceptionWhenGenerationFails() {
        ConversationId conversationId = new ConversationId(UUID.randomUUID());
        FakeConversationRepository conversations = new FakeConversationRepository(
                new Conversation(conversationId, null));
        RankedResult relevantResult = new RankedResult(
                new ChunkId(UUID.randomUUID()), new DocumentId(UUID.randomUUID()), "Boil water first.", 0.90);
        Retriever retriever = (query, topK) -> List.of(relevantResult);
        AnswerGenerator answerGenerator = (query, results) -> new AnswerGenerationFailed("Gemini timed out");

        AskQuestionService service = new AskQuestionService(retriever, answerGenerator, conversations, THRESHOLD);

        assertThatThrownBy(() -> service.ask(conversationId, "Is flood water safe to drink?"))
                .isInstanceOf(AnswerGenerationFailedException.class)
                .hasMessageContaining("Gemini timed out");
    }

    @Test
    void throwsWhenConversationDoesNotExist() {
        ConversationId conversationId = new ConversationId(UUID.randomUUID());
        FakeConversationRepository conversations = new FakeConversationRepository(null);
        Retriever retriever = (query, topK) -> List.of();
        AnswerGenerator answerGenerator = (query, results) -> new AnswerGenerationFailed("unused");

        AskQuestionService service = new AskQuestionService(retriever, answerGenerator, conversations, THRESHOLD);

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
    }
}

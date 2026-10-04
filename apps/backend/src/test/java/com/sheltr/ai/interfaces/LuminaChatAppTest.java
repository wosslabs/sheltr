package com.sheltr.ai.interfaces;

import com.sheltr.ai.application.AnswerContext;
import com.sheltr.ai.application.AnswerContextBuilder;
import com.sheltr.ai.application.AnswerPipeline;
import com.sheltr.ai.domain.AnswerConfidence;
import com.sheltr.ai.domain.Citation;
import com.sheltr.ai.domain.Conversation;
import com.sheltr.ai.domain.ConversationId;
import com.sheltr.ai.domain.ConversationRepository;
import com.sheltr.ai.domain.MessageRole;
import com.sheltr.ai.domain.NoAnswerAvailable;
import com.sheltr.ai.domain.ThresholdRelevanceJudge;
import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.DocumentId;
import com.sheltr.search.domain.NoOpReranker;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.Retriever;
import io.lumina.ai.ChatClient;
import io.lumina.state.StateStore;
import io.lumina.ui.Ui;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LuminaChatAppTest {

    private static final double THRESHOLD = 0.75;

    private static AnswerPipeline pipeline(Retriever retriever) {
        return new AnswerPipeline(retriever, new NoOpReranker(), new ThresholdRelevanceJudge(THRESHOLD),
                (query, results, verdict) -> {
                    throw new AssertionError("Lumina streams through ChatClient, not AnswerGenerator");
                });
    }

    @Test
    void persistsRefusalWithoutCallingChatClientWhenBelowThreshold() {
        Conversation conversation = new Conversation(new ConversationId(UUID.randomUUID()), null);
        FakeConversationRepository conversations = new FakeConversationRepository(conversation);
        Retriever retriever = mock(Retriever.class);
        AnswerContextBuilder contextBuilder = mock(AnswerContextBuilder.class);
        ChatClient chatClient = mock(ChatClient.class);
        Ui ui = uiFor(conversation.id(), "What is the capital of France?");
        RankedResult irrelevant = result(0.40);
        when(retriever.retrieve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(irrelevant));

        new LuminaChatApp(conversations, pipeline(retriever), contextBuilder, chatClient).build(ui);

        assertThat(conversations.saved.messages())
                .extracting(message -> message.role(), message -> message.content().value())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                MessageRole.USER, "What is the capital of France?"),
                        org.assertj.core.groups.Tuple.tuple(
                                MessageRole.ASSISTANT, NoAnswerAvailable.MESSAGE));
        verify(ui).ai(NoAnswerAvailable.MESSAGE);
        verify(chatClient, never()).stream(anyString());
    }

    @Test
    void savesUserAndShowsFriendlyErrorWhenChatClientThrows() {
        Conversation conversation = new Conversation(new ConversationId(UUID.randomUUID()), null);
        FakeConversationRepository conversations = new FakeConversationRepository(conversation);
        Retriever retriever = mock(Retriever.class);
        AnswerContextBuilder contextBuilder = mock(AnswerContextBuilder.class);
        ChatClient chatClient = mock(ChatClient.class);
        Ui ui = uiFor(conversation.id(), "Is flood water safe to drink?");
        RankedResult relevant = result(0.90);
        when(retriever.retrieve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(relevant));
        when(contextBuilder.buildContext(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AnswerContext(
                        "grounded prompt", List.of(), AnswerConfidence.fromSimilarity(0.90)));
        when(chatClient.stream("grounded prompt"))
                .thenThrow(new IllegalStateException("provider-secret: model unavailable"));

        new LuminaChatApp(conversations, pipeline(retriever), contextBuilder, chatClient).build(ui);

        assertThat(conversations.saved.messages())
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.role()).isEqualTo(MessageRole.USER);
                    assertThat(message.content().value()).isEqualTo("Is flood water safe to drink?");
                });
        String sessionError = ui.state().get("error");
        assertThat(sessionError).isEqualTo(LuminaChatApp.GENERATION_ERROR);
        verify(ui).text(LuminaChatApp.GENERATION_ERROR);
        verify(ui, never()).text(contains("provider-secret"));
    }

    @Test
    void showsGenericErrorWithoutLeakingDetailsWhenEmbeddingsAreStale() {
        Conversation conversation = new Conversation(new ConversationId(UUID.randomUUID()), null);
        FakeConversationRepository conversations = new FakeConversationRepository(conversation);
        Retriever retriever = mock(Retriever.class);
        AnswerContextBuilder contextBuilder = mock(AnswerContextBuilder.class);
        ChatClient chatClient = mock(ChatClient.class);
        Ui ui = uiFor(conversation.id(), "Is flood water safe to drink?");
        when(retriever.retrieve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new com.sheltr.knowledge.domain.StaleEmbeddingsException(3, "secret-model"));

        new LuminaChatApp(conversations, pipeline(retriever), contextBuilder, chatClient).build(ui);

        assertThat(conversations.saved.messages()).singleElement()
                .satisfies(message -> assertThat(message.role()).isEqualTo(MessageRole.USER));
        String sessionError = ui.state().get("error");
        assertThat(sessionError).isEqualTo(LuminaChatApp.GENERATION_ERROR);
        verify(ui).text(LuminaChatApp.GENERATION_ERROR);
        verify(ui, never()).text(contains("secret-model"));
        verify(chatClient, never()).stream(anyString());
    }

    private static Ui uiFor(ConversationId conversationId, String prompt) {
        Ui ui = mock(Ui.class);
        MapStateStore state = new MapStateStore();
        state.set("conversationId", conversationId.value());
        when(ui.state()).thenReturn(state);
        when(ui.chatInput()).thenReturn(prompt);
        return ui;
    }

    private static RankedResult result(double score) {
        return new RankedResult(
                new ChunkId(UUID.randomUUID()),
                new DocumentId(UUID.randomUUID()),
                "Relevant source text.",
                score);
    }

    private static final class FakeConversationRepository implements ConversationRepository {
        private final Conversation stored;
        private Conversation saved;

        private FakeConversationRepository(Conversation stored) {
            this.stored = stored;
        }

        @Override
        public Optional<Conversation> findById(ConversationId id) {
            return stored.id().equals(id) ? Optional.of(stored) : Optional.empty();
        }

        @Override
        public void save(Conversation conversation) {
            saved = conversation;
        }

        @Override
        public List<Citation> findCitationsByDocumentId(DocumentId documentId) {
            return List.of();
        }
    }

    private static final class MapStateStore implements StateStore {
        private final Map<String, Object> values = new HashMap<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(String key) {
            return (T) values.get(key);
        }

        @Override
        public void set(String key, Object value) {
            values.put(key, value);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T computeIfAbsent(String key, Function<String, T> mappingFunction) {
            return (T) values.computeIfAbsent(key, mappingFunction);
        }

        @Override
        public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public void remove(String key) {
            values.remove(key);
        }
    }
}

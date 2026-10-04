package com.sheltr.search.config;

import com.sheltr.search.domain.NoOpReranker;
import com.sheltr.search.infrastructure.ModelReranker;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class RerankerConfigTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<ChatModel> chatModel = mock(ObjectProvider.class);

    @Test
    void rejectsAnUnknownValue() {
        assertThatThrownBy(() -> new RerankerConfig().reranker(chatModel, "llm"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'llm'");
    }

    @Test
    void noneReturnsNoOpRerankerWithoutNeedingAChatModel() {
        assertThat(new RerankerConfig().reranker(chatModel, "none")).isInstanceOf(NoOpReranker.class);

        verify(chatModel, never()).getObject();
    }

    @Test
    void modelReturnsModelRerankerUsingTheChatModel() {
        ChatModel model = mock(ChatModel.class);
        when(chatModel.getObject()).thenReturn(model);

        assertThat(new RerankerConfig().reranker(chatModel, "model")).isInstanceOf(ModelReranker.class);
    }
}

package com.sheltr.ai.config;

import com.sheltr.ai.domain.ThresholdRelevanceJudge;
import com.sheltr.ai.infrastructure.ModelRelevanceJudge;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AnswerPipelineConfigTest {

    private final ChatModel chatModel = mock(ChatModel.class);

    @Test
    void rejectsAnUnknownJudgeType() {
        assertThatThrownBy(() -> new AnswerPipelineConfig().relevanceJudge(chatModel, "bogus", 0.5, 0.75))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'bogus'");
    }

    @Test
    void buildsTheConfiguredJudge() {
        assertThat(new AnswerPipelineConfig().relevanceJudge(chatModel, "threshold", 0.5, 0.75))
                .isInstanceOf(ThresholdRelevanceJudge.class);
        assertThat(new AnswerPipelineConfig().relevanceJudge(chatModel, "model", 0.5, 0.75))
                .isInstanceOf(ModelRelevanceJudge.class);
    }
}

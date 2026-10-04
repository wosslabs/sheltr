package com.sheltr.ai.config;

import com.sheltr.ai.application.AnswerContextBuilder;
import com.sheltr.ai.application.AnswerPipeline;
import com.sheltr.ai.domain.ConversationRepository;
import com.sheltr.ai.interfaces.LuminaChatApp;
import io.lumina.LuminaApp;
import io.lumina.ai.ChatClient;
import io.lumina.springai.SpringAiChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LuminaChatAppConfig {

    @Bean
    @ConditionalOnMissingBean(ChatClient.class)
    public ChatClient luminaChatClient(ChatModel chatModel) {
        // Lumina 1.0.0 evaluates ConditionalOnBean(ChatModel) before Ollama's ChatModel exists.
        return new SpringAiChatClient(
                org.springframework.ai.chat.client.ChatClient.create(chatModel));
    }

    @Bean
    public LuminaApp luminaApp(ConversationRepository conversationRepository, AnswerPipeline answerPipeline,
                               AnswerContextBuilder answerContextBuilder, ChatClient chatClient) {
        return new LuminaChatApp(conversationRepository, answerPipeline, answerContextBuilder, chatClient);
    }
}

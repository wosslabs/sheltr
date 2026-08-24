package com.sheltr.ai.config;

import com.sheltr.ai.application.AnswerContextBuilder;
import com.sheltr.ai.domain.ConversationRepository;
import com.sheltr.ai.interfaces.LuminaChatApp;
import com.sheltr.search.domain.Retriever;
import io.lumina.LuminaApp;
import io.lumina.ai.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LuminaChatAppConfig {

    @Bean
    public ChatClient luminaChatClient(ChatModel chatModel) {
        // Lumina 1.0.0 evaluates ConditionalOnBean(ChatModel) before Ollama's ChatModel exists.
        return new io.lumina.springai.SpringAiChatClient(
                org.springframework.ai.chat.client.ChatClient.create(chatModel));
    }

    @Bean
    public LuminaApp luminaApp(ConversationRepository conversationRepository, Retriever retriever,
                               AnswerContextBuilder answerContextBuilder, ChatClient chatClient,
                               @Value("${sheltr.search.relevance-threshold}") double relevanceThreshold) {
        return new LuminaChatApp(conversationRepository, retriever, answerContextBuilder, chatClient,
                relevanceThreshold);
    }
}

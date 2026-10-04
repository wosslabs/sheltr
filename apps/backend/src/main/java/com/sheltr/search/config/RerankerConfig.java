package com.sheltr.search.config;

import com.sheltr.search.domain.NoOpReranker;
import com.sheltr.search.domain.Reranker;
import com.sheltr.search.infrastructure.ModelReranker;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RerankerConfig {

    @Bean
    public Reranker reranker(ChatModel chatModel, @Value("${sheltr.search.reranker}") String type) {
        return switch (type) {
            case "none" -> new NoOpReranker();
            case "model" -> new ModelReranker(chatModel::call);
            default -> throw new IllegalArgumentException(
                    "sheltr.search.reranker must be 'none' or 'model', got '" + type + "'");
        };
    }
}

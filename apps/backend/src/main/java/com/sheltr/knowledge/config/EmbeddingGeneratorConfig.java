package com.sheltr.knowledge.config;

import com.sheltr.knowledge.domain.EmbeddingGenerator;
import com.sheltr.knowledge.infrastructure.SpringAiEmbeddingGeneratorAdapter;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmbeddingGeneratorConfig {

    @Bean
    public EmbeddingGenerator embeddingGenerator(EmbeddingModel embeddingModel) {
        return new SpringAiEmbeddingGeneratorAdapter(embeddingModel);
    }
}

package com.sheltr.knowledge.config;

import com.sheltr.knowledge.domain.EmbeddingGenerator;
import com.sheltr.knowledge.infrastructure.SpringAiEmbeddingGeneratorAdapter;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmbeddingGeneratorConfig {

    @Bean
    public EmbeddingGenerator embeddingGenerator(
            EmbeddingModel embeddingModel,
            @Value("${sheltr.embedding.model-name}") String modelName,
            @Value("${sheltr.embedding.query-prefix:}") String queryPrefix,
            @Value("${sheltr.embedding.document-prefix:}") String documentPrefix) {
        return new SpringAiEmbeddingGeneratorAdapter(embeddingModel, modelName, queryPrefix, documentPrefix);
    }
}

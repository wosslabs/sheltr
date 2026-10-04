package com.sheltr.knowledge.infrastructure;

import com.sheltr.knowledge.domain.Embedding;
import com.sheltr.knowledge.domain.EmbeddingGenerator;
import org.springframework.ai.embedding.EmbeddingModel;

public class SpringAiEmbeddingGeneratorAdapter implements EmbeddingGenerator {

    private final EmbeddingModel embeddingModel;
    private final String modelName;
    private final String queryPrefix;
    private final String documentPrefix;

    public SpringAiEmbeddingGeneratorAdapter(EmbeddingModel embeddingModel, String modelName) {
        this(embeddingModel, modelName, "", "");
    }

    public SpringAiEmbeddingGeneratorAdapter(EmbeddingModel embeddingModel, String modelName,
                                             String queryPrefix, String documentPrefix) {
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("modelName must not be blank");
        }
        this.embeddingModel = embeddingModel;
        this.modelName = modelName;
        this.queryPrefix = queryPrefix == null ? "" : queryPrefix;
        this.documentPrefix = documentPrefix == null ? "" : documentPrefix;
    }

    @Override
    public Embedding embed(String text) {
        return new Embedding(embeddingModel.embed(documentPrefix + text));
    }

    @Override
    public Embedding embedQuery(String text) {
        return new Embedding(embeddingModel.embed(queryPrefix + text));
    }

    @Override
    public String modelName() {
        return modelName;
    }
}

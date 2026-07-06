package com.sheltr.knowledge.infrastructure;

import com.sheltr.knowledge.domain.Embedding;
import com.sheltr.knowledge.domain.EmbeddingGenerator;
import org.springframework.ai.embedding.EmbeddingModel;

public class GeminiEmbeddingGeneratorAdapter implements EmbeddingGenerator {

    private final EmbeddingModel embeddingModel;

    public GeminiEmbeddingGeneratorAdapter(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public Embedding embed(String text) {
        float[] vector = embeddingModel.embed(text);
        return new Embedding(vector);
    }
}

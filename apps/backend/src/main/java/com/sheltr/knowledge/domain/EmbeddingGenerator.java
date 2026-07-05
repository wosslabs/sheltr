package com.sheltr.knowledge.domain;

public interface EmbeddingGenerator {
    Embedding embed(String text);
}

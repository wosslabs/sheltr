package com.sheltr.knowledge.domain;

public record ChunkContent(String value) {
    public ChunkContent {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("ChunkContent value must not be blank");
        }
    }
}

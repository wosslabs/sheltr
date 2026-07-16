package com.sheltr.ingestion.domain;

import com.sheltr.knowledge.domain.PageNumber;

public record ChunkDraft(String content, PageNumber pageNumber) {
    public ChunkDraft {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("ChunkDraft content must not be blank");
        }
    }
}

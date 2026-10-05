package com.sheltr.ingestion.domain;

import com.sheltr.knowledge.domain.PageNumber;

/** @param pageNumber the source page, or null for an HTML document, which has no pages */
public record ChunkDraft(String content, PageNumber pageNumber) {
    public ChunkDraft {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("ChunkDraft content must not be blank");
        }
    }
}

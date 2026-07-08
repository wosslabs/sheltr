package com.sheltr.search.domain;

import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.DocumentId;

public record RankedResult(ChunkId chunkId, DocumentId documentId, String content, double score) {
    public RankedResult {
        if (score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("score must be between 0.0 and 1.0, got " + score);
        }
    }
}

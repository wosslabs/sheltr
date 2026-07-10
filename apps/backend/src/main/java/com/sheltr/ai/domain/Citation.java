package com.sheltr.ai.domain;

import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.DocumentId;

public record Citation(DocumentId documentId, ChunkId chunkId, String sourceTitle, String sourceUrl, Integer pageNumber) {
}

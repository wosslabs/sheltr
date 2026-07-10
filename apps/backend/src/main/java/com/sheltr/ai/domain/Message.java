package com.sheltr.ai.domain;

import com.sheltr.knowledge.domain.ChunkId;

import java.util.List;

public record Message(MessageId id, MessageRole role, MessageContent content, List<Citation> citations,
                       AnswerConfidence confidence, List<ChunkId> retrievedChunkIds) {
    public Message {
        citations = List.copyOf(citations == null ? List.of() : citations);
        retrievedChunkIds = List.copyOf(retrievedChunkIds == null ? List.of() : retrievedChunkIds);
    }
}

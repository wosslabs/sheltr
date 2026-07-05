package com.sheltr.knowledge.domain;

import java.util.UUID;

public record ChunkId(UUID value) {
    public ChunkId {
        if (value == null) throw new IllegalArgumentException("ChunkId value must not be null");
    }
}

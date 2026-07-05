package com.sheltr.knowledge.domain;

import java.util.List;
import java.util.Optional;

public interface ChunkRepository {
    Optional<Chunk> findById(ChunkId id);
    List<Chunk> findPendingEmbeddings();
    void save(Chunk chunk);
}

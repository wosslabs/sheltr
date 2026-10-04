package com.sheltr.knowledge.domain;

import java.util.List;
import java.util.Optional;

public interface ChunkRepository {
    Optional<Chunk> findById(ChunkId id);

    /** Chunks with no vector, or with a vector from a model other than {@code embeddingModel}. */
    List<Chunk> findPendingEmbeddings(String embeddingModel);

    void save(Chunk chunk, String embeddingModel);

    void insert(Chunk chunk, String embeddingModel);

    int countEmbeddedWithOtherModel(String embeddingModel);
}

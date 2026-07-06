package com.sheltr.knowledge.application;

import com.sheltr.knowledge.domain.Chunk;
import com.sheltr.knowledge.domain.ChunkRepository;
import com.sheltr.knowledge.domain.EmbeddingGenerator;

import java.util.List;

public class ChunkEmbeddingBackfillService {

    private final ChunkRepository chunkRepository;
    private final EmbeddingGenerator embeddingGenerator;

    public ChunkEmbeddingBackfillService(ChunkRepository chunkRepository, EmbeddingGenerator embeddingGenerator) {
        this.chunkRepository = chunkRepository;
        this.embeddingGenerator = embeddingGenerator;
    }

    public int backfillAll() {
        List<Chunk> pending = chunkRepository.findPendingEmbeddings();
        for (Chunk chunk : pending) {
            var embedding = embeddingGenerator.embed(chunk.content().value());
            chunkRepository.save(chunk.withEmbedding(embedding));
        }
        return pending.size();
    }
}

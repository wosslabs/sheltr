package com.sheltr.knowledge.application;

import com.sheltr.knowledge.domain.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkEmbeddingBackfillServiceTest {

    @Test
    void embedsEveryPendingChunkAndSavesIt() {
        Chunk pending = new Chunk(
                new ChunkId(java.util.UUID.randomUUID()), new DocumentId(java.util.UUID.randomUUID()),
                new ChunkContent("Boil water for one minute."), null, null, 0,
                new ChunkMetadata("Water Safety", java.util.Set.of(DisasterType.FLOOD)),
                new Checksum("abc"));

        FakeChunkRepository chunkRepository = new FakeChunkRepository(List.of(pending));
        FakeEmbeddingGenerator embeddingGenerator = new FakeEmbeddingGenerator();
        ChunkEmbeddingBackfillService service = new ChunkEmbeddingBackfillService(chunkRepository, embeddingGenerator);

        int count = service.backfillAll();

        assertThat(count).isEqualTo(1);
        assertThat(chunkRepository.saved).hasSize(1);
        assertThat(chunkRepository.saved.get(0).embedding()).isPresent();
    }

    private static class FakeChunkRepository implements ChunkRepository {
        private final List<Chunk> pending;
        final List<Chunk> saved = new ArrayList<>();

        FakeChunkRepository(List<Chunk> pending) { this.pending = pending; }

        @Override
        public Optional<Chunk> findById(ChunkId id) { return Optional.empty(); }

        @Override
        public List<Chunk> findPendingEmbeddings() { return pending; }

        @Override
        public void save(Chunk chunk) { saved.add(chunk); }
    }

    private static class FakeEmbeddingGenerator implements EmbeddingGenerator {
        @Override
        public Embedding embed(String text) {
            float[] values = new float[768];
            values[0] = 1.0f;
            return new Embedding(values);
        }
    }
}

package com.sheltr.knowledge.domain;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkTest {

    @Test
    void createsChunkWithoutEmbedding() {
        ChunkId id = new ChunkId(UUID.randomUUID());
        DocumentId documentId = new DocumentId(UUID.randomUUID());
        ChunkContent content = new ChunkContent("Boil water for one minute to make it safe to drink.");
        ChunkMetadata metadata = new ChunkMetadata("Water Safety", java.util.Set.of(DisasterType.FLOOD));

        Chunk chunk = new Chunk(id, documentId, content, null, null, 0, metadata, new Checksum("abc123"));

        assertThat(chunk.id()).isEqualTo(id);
        assertThat(chunk.embedding()).isEmpty();
    }

    @Test
    void withEmbeddingReturnsNewChunkWithEmbeddingSet() {
        Chunk chunk = new Chunk(
                new ChunkId(UUID.randomUUID()), new DocumentId(UUID.randomUUID()),
                new ChunkContent("Test content"), null, null, 0,
                new ChunkMetadata("Section", java.util.Set.of(DisasterType.WILDFIRE)),
                new Checksum("abc123"));
        float[] vector = new float[768];
        vector[0] = 0.5f;
        Embedding embedding = new Embedding(vector);

        Chunk withEmbedding = chunk.withEmbedding(embedding);

        assertThat(withEmbedding.embedding()).contains(embedding);
        assertThat(chunk.embedding()).isEmpty();
    }

    @Test
    void embeddingRejectsWrongDimension() {
        assertThatThrownBy(() -> new Embedding(new float[10]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("768");
    }
}

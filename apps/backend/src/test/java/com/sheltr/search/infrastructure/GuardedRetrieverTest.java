package com.sheltr.search.infrastructure;

import com.sheltr.knowledge.domain.Chunk;
import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.ChunkRepository;
import com.sheltr.knowledge.domain.StaleEmbeddingsException;
import com.sheltr.search.domain.Retriever;
import com.sheltr.search.domain.SearchQuery;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuardedRetrieverTest {

    private static final Retriever EMPTY = (query, topK) -> List.of();

    @Test
    void delegatesWhenNoStaleEmbeddingsExist() {
        var retriever = new GuardedRetriever(EMPTY, chunksWithStaleCount(0), "model-a");

        assertThat(retriever.retrieve(new SearchQuery("flood"), 5)).isEmpty();
    }

    @Test
    void refusesWhenChunksWereEmbeddedByAnotherModel() {
        var retriever = new GuardedRetriever(EMPTY, chunksWithStaleCount(12), "model-a");

        assertThatThrownBy(() -> retriever.retrieve(new SearchQuery("flood"), 5))
                .isInstanceOf(StaleEmbeddingsException.class)
                .hasMessageContaining("12")
                .hasMessageContaining("model-a")
                .hasMessageContaining("make seed-embeddings");
    }

    private static ChunkRepository chunksWithStaleCount(int staleCount) {
        return new ChunkRepository() {
            @Override
            public Optional<Chunk> findById(ChunkId id) { return Optional.empty(); }

            @Override
            public List<Chunk> findPendingEmbeddings(String embeddingModel) { return List.of(); }

            @Override
            public void save(Chunk chunk, String embeddingModel) { }

            @Override
            public void insert(Chunk chunk, String embeddingModel) { }

            @Override
            public int countEmbeddedWithOtherModel(String embeddingModel) { return staleCount; }
        };
    }
}

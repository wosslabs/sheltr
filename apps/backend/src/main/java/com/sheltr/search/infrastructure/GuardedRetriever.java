package com.sheltr.search.infrastructure;

import com.sheltr.knowledge.domain.ChunkRepository;
import com.sheltr.knowledge.domain.StaleEmbeddingsException;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.Retriever;
import com.sheltr.search.domain.SearchQuery;

import java.util.List;

/** Refuses to search while chunks embedded by another model exist, instead of degrading silently. */
public class GuardedRetriever implements Retriever {

    private final Retriever delegate;
    private final ChunkRepository chunkRepository;
    private final String embeddingModel;

    public GuardedRetriever(Retriever delegate, ChunkRepository chunkRepository, String embeddingModel) {
        this.delegate = delegate;
        this.chunkRepository = chunkRepository;
        this.embeddingModel = embeddingModel;
    }

    @Override
    public List<RankedResult> retrieve(SearchQuery query, int topK) {
        int stale = chunkRepository.countEmbeddedWithOtherModel(embeddingModel);
        if (stale > 0) {
            throw new StaleEmbeddingsException(stale, embeddingModel);
        }
        return delegate.retrieve(query, topK);
    }
}

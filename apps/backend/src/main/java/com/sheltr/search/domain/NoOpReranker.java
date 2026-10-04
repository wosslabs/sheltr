package com.sheltr.search.domain;

import java.util.List;

public class NoOpReranker implements Reranker {
    @Override
    public List<RankedResult> rerank(SearchQuery query, List<RankedResult> candidates, int topK) {
        return candidates.stream().limit(topK).toList();
    }
}

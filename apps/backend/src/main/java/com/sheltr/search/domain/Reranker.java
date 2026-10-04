package com.sheltr.search.domain;

import java.util.List;

public interface Reranker {
    List<RankedResult> rerank(SearchQuery query, List<RankedResult> candidates, int topK);
}

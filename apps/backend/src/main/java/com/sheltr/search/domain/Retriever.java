package com.sheltr.search.domain;

import java.util.List;

public interface Retriever {
    List<RankedResult> retrieve(SearchQuery query, int topK);
}

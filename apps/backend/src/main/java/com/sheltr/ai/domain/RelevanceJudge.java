package com.sheltr.ai.domain;

import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.SearchQuery;

import java.util.List;

/** Decides whether the retrieved passages are enough to answer the question. */
public interface RelevanceJudge {
    RelevanceVerdict judge(SearchQuery query, List<RankedResult> passages);
}

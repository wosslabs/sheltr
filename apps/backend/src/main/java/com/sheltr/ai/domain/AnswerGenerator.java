package com.sheltr.ai.domain;

import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.SearchQuery;

import java.util.List;

public interface AnswerGenerator {
    AnswerOutcome generate(SearchQuery query, List<RankedResult> results, RelevanceVerdict verdict);
}

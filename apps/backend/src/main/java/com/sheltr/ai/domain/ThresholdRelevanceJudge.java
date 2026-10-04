package com.sheltr.ai.domain;

import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.SearchQuery;

import java.util.List;

/** Answerable when the best passage's similarity score reaches a fixed threshold. */
public class ThresholdRelevanceJudge implements RelevanceJudge {

    private final double threshold;

    public ThresholdRelevanceJudge(double threshold) {
        this.threshold = threshold;
    }

    @Override
    public RelevanceVerdict judge(SearchQuery query, List<RankedResult> passages) {
        if (passages.isEmpty()) {
            return RelevanceVerdict.REFUSE;
        }
        double best = passages.stream().mapToDouble(RankedResult::score).max().orElse(0.0);
        return new RelevanceVerdict(best >= threshold, best);
    }
}

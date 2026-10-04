package com.sheltr.search.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reciprocal rank fusion: combines rankings without needing their scores to be comparable. */
public final class RankFusion {

    static final int K = 60;

    private RankFusion() {
    }

    public static <T> Map<T, Double> fuse(List<List<T>> rankings) {
        Map<T, Double> scores = new LinkedHashMap<>();
        for (List<T> ranking : rankings) {
            for (int i = 0; i < ranking.size(); i++) {
                scores.merge(ranking.get(i), 1.0 / (K + i + 1), Double::sum);
            }
        }
        return scores;
    }
}

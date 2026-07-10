package com.sheltr.ai.domain;

import com.sheltr.search.domain.RankedResult;

import java.util.List;

public final class RelevanceGate {

    private RelevanceGate() {
    }

    public static boolean passesThreshold(List<RankedResult> results, double threshold) {
        return results.stream().findFirst().map(top -> top.score() >= threshold).orElse(false);
    }
}

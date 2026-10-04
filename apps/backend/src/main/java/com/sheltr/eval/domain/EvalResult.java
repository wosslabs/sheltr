package com.sheltr.eval.domain;

import java.util.List;

/**
 * @param confidence the relevance judge's probability; null when the pipeline failed before judging
 * @param error      set when generation or the pipeline failed; such a result counts as refused
 */
public record EvalResult(String questionId, List<RetrievedPassage> passages, boolean refused, String answer,
                         Double confidence, List<RetrievedPassage> cited, long latencyMs, String error) {
    public EvalResult {
        refused = refused || error != null;
        passages = List.copyOf(passages == null ? List.of() : passages);
        cited = List.copyOf(cited == null ? List.of() : cited);
    }
}

package com.sheltr.ai.domain;

public record RelevanceVerdict(boolean answerable, double probability) {

    public static final RelevanceVerdict REFUSE = new RelevanceVerdict(false, 0.0);

    public RelevanceVerdict {
        if (Double.isNaN(probability) || probability < 0.0 || probability > 1.0) {
            throw new IllegalArgumentException("probability must be between 0.0 and 1.0, got " + probability);
        }
    }
}

package com.sheltr.ai.domain;

/** Probability cut-offs for HIGH and MEDIUM confidence. Fitted per judge on the eval tuning split. */
public record ConfidenceBands(double high, double medium) {

    public static final ConfidenceBands DEFAULT = new ConfidenceBands(0.90, 0.75);

    public ConfidenceBands {
        if (medium > high) {
            throw new IllegalArgumentException("medium (" + medium + ") must not exceed high (" + high + ")");
        }
    }
}

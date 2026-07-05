package com.sheltr.knowledge.domain;

import java.util.Arrays;

public record Embedding(float[] values) {
    public static final int DIMENSION = 768;

    public Embedding {
        if (values == null || values.length != DIMENSION) {
            throw new IllegalArgumentException(
                    "Embedding must have exactly " + DIMENSION + " dimensions, got "
                            + (values == null ? "null" : values.length));
        }
        values = Arrays.copyOf(values, values.length);
    }

    @Override
    public float[] values() {
        return Arrays.copyOf(values, values.length);
    }
}

package com.sheltr.knowledge.domain;

public record PageNumber(int value) {
    public PageNumber {
        if (value < 1) throw new IllegalArgumentException("PageNumber must be >= 1");
    }
}

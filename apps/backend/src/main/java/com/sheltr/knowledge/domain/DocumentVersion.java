package com.sheltr.knowledge.domain;

public record DocumentVersion(int value) {
    public DocumentVersion {
        if (value < 1) throw new IllegalArgumentException("DocumentVersion must be >= 1");
    }
}

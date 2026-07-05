package com.sheltr.knowledge.domain;

public record Checksum(String hex) {
    public Checksum {
        if (hex == null || hex.isBlank()) {
            throw new IllegalArgumentException("Checksum hex must not be blank");
        }
    }
}

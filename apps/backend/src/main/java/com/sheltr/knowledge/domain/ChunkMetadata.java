package com.sheltr.knowledge.domain;

import java.util.Set;

public record ChunkMetadata(String sectionHeading, Set<DisasterType> disasterTypes) {
    public ChunkMetadata {
        disasterTypes = Set.copyOf(disasterTypes == null ? Set.of() : disasterTypes);
    }
}

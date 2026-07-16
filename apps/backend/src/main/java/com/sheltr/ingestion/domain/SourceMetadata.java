package com.sheltr.ingestion.domain;

import com.sheltr.knowledge.domain.DisasterType;

import java.util.Set;

public record SourceMetadata(String title, String sourceAgency, Set<DisasterType> disasterTypes) {
    public SourceMetadata {
        disasterTypes = Set.copyOf(disasterTypes == null ? Set.of() : disasterTypes);
    }
}

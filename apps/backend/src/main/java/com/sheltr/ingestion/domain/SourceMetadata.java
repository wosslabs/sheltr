package com.sheltr.ingestion.domain;

import com.sheltr.knowledge.domain.DisasterType;

import java.time.LocalDate;
import java.util.Set;

public record SourceMetadata(String title, String sourceAgency, Set<DisasterType> disasterTypes,
                             LocalDate publishedDate, String license, String country) {

    public SourceMetadata(String title, String sourceAgency, Set<DisasterType> disasterTypes,
                          LocalDate publishedDate, String license) {
        this(title, sourceAgency, disasterTypes, publishedDate, license, null);
    }

    public SourceMetadata {
        requireText(title, "title");
        requireText(sourceAgency, "sourceAgency");
        requireText(license, "license");
        if (disasterTypes == null || disasterTypes.isEmpty()) {
            throw new IllegalArgumentException("disasterTypes must not be empty");
        }
        if (publishedDate == null) {
            throw new IllegalArgumentException("publishedDate must not be null");
        }
        disasterTypes = Set.copyOf(disasterTypes);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}

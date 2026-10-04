package com.sheltr.search.domain;

import com.sheltr.knowledge.domain.DisasterType;

import java.util.Set;

/**
 * @param activeHazards hazards an official feed reports near the asker. They raise matching
 *                      passages in the ranking and are never used to filter passages out.
 */
public record SearchQuery(String text, Set<DisasterType> activeHazards) {
    public SearchQuery {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("SearchQuery text must not be blank");
        }
        activeHazards = Set.copyOf(activeHazards == null ? Set.of() : activeHazards);
    }

    public SearchQuery(String text) {
        this(text, Set.of());
    }
}

package com.sheltr.search.domain;

import com.sheltr.knowledge.domain.DisasterType;

import java.util.Set;

/**
 * @param activeHazards hazards an official feed reports near the asker. They raise matching
 *                      passages in the ranking and are never used to filter passages out.
 * @param country       optional ISO 3166-1 alpha-2 code of the asker's country. Passages whose
 *                      document is for this country are boosted; global and other-country
 *                      passages are never removed. Null means no country was given.
 */
public record SearchQuery(String text, Set<DisasterType> activeHazards, String country) {
    public SearchQuery {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("SearchQuery text must not be blank");
        }
        activeHazards = Set.copyOf(activeHazards == null ? Set.of() : activeHazards);
    }

    public SearchQuery(String text, Set<DisasterType> activeHazards) {
        this(text, activeHazards, null);
    }

    public SearchQuery(String text) {
        this(text, Set.of(), null);
    }
}

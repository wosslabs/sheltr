package com.sheltr.search.domain;

import com.sheltr.knowledge.domain.DisasterType;

import java.util.Set;

public interface RelatedHazards {
    /** Hazards that approved corpus documents say commonly follow any of {@code hazards}. */
    Set<DisasterType> relatedTo(Set<DisasterType> hazards);
}

package com.sheltr.search.domain;

import com.sheltr.knowledge.domain.DisasterType;

import java.util.Collections;
import java.util.Set;

public record HazardBoost(double activeBoost, double relatedBoost) {

    public double factor(Set<DisasterType> documentHazards, Set<DisasterType> active, Set<DisasterType> related) {
        if (!Collections.disjoint(documentHazards, active)) {
            return 1.0 + activeBoost;
        }
        if (!Collections.disjoint(documentHazards, related)) {
            return 1.0 + relatedBoost;
        }
        return 1.0;
    }
}

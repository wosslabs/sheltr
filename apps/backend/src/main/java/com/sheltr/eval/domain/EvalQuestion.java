package com.sheltr.eval.domain;

import com.sheltr.knowledge.domain.DisasterType;

import java.util.List;
import java.util.Set;

public record EvalQuestion(String id, String question, EvalKind kind, Set<DisasterType> activeHazards,
                           List<ExpectedPassage> expectedPassages, List<String> keyFacts, String split) {
    public EvalQuestion {
        activeHazards = Set.copyOf(activeHazards == null ? Set.of() : activeHazards);
        expectedPassages = List.copyOf(expectedPassages == null ? List.of() : expectedPassages);
        keyFacts = List.copyOf(keyFacts == null ? List.of() : keyFacts);
    }
}

package com.sheltr.search.domain;

import com.sheltr.knowledge.domain.DisasterType;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class HazardBoostTest {

    private final HazardBoost boost = new HazardBoost(0.3, 0.15);

    @Test
    void boostsADocumentAboutAnActiveHazardMost() {
        assertThat(boost.factor(Set.of(DisasterType.FLOOD), Set.of(DisasterType.FLOOD),
                Set.of(DisasterType.LANDSLIDE))).isCloseTo(1.3, within(1e-9));
    }

    @Test
    void boostsADocumentAboutARelatedHazardLess() {
        assertThat(boost.factor(Set.of(DisasterType.LANDSLIDE), Set.of(DisasterType.FLOOD),
                Set.of(DisasterType.LANDSLIDE))).isCloseTo(1.15, within(1e-9));
    }

    @Test
    void leavesOtherDocumentsAndQueriesWithoutHazardsUnchanged() {
        assertThat(boost.factor(Set.of(DisasterType.WILDFIRE), Set.of(DisasterType.FLOOD), Set.of()))
                .isEqualTo(1.0);
        assertThat(boost.factor(Set.of(DisasterType.FLOOD), Set.of(), Set.of())).isEqualTo(1.0);
    }
}

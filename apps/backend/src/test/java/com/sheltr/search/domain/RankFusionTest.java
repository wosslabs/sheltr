package com.sheltr.search.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RankFusionTest {

    @Test
    void scoresAnItemByItsRankInEveryListItAppearsIn() {
        Map<String, Double> fused = RankFusion.fuse(List.of(List.of("a", "b"), List.of("b", "c")));

        assertThat(fused.get("a")).isCloseTo(1.0 / 61, within(1e-9));
        assertThat(fused.get("b")).isCloseTo(1.0 / 62 + 1.0 / 61, within(1e-9));
        assertThat(fused.get("c")).isCloseTo(1.0 / 62, within(1e-9));
    }

    @Test
    void keepsFirstSeenOrderAndHandlesEmptyLists() {
        assertThat(RankFusion.fuse(List.of(List.of("a", "b"), List.<String>of())).keySet())
                .containsExactly("a", "b");
        assertThat(RankFusion.fuse(List.<List<String>>of())).isEmpty();
    }
}

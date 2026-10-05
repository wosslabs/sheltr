package com.sheltr.search.domain;

import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.DisasterType;
import com.sheltr.knowledge.domain.DocumentId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HybridRankerTest {

    private final HybridRanker ranker = new HybridRanker(new HazardBoost(0.3, 0.15));
    private final HybridRanker.Candidate quake = candidate("quake text", 0.9, DisasterType.EARTHQUAKE);
    private final HybridRanker.Candidate flood = candidate("flood text", 0.8, DisasterType.FLOOD);
    private final HybridRanker.Candidate fire = candidate("fire text", 0.7, DisasterType.WILDFIRE);

    @Test
    void ranksACandidateFoundByBothSearchesFirst() {
        List<RankedResult> results = ranker.rank(List.of(quake, flood), List.of(flood, fire),
                Set.of(), Set.of(), 3);

        assertThat(results).extracting(RankedResult::content)
                .containsExactly("flood text", "quake text", "fire text");
    }

    @Test
    void keepsVectorOrderWhenThereAreNoKeywordHitsOrHazards() {
        List<RankedResult> results = ranker.rank(List.of(quake, flood), List.of(), Set.of(), Set.of(), 5);

        assertThat(results).extracting(RankedResult::content).containsExactly("quake text", "flood text");
        assertThat(results.get(0).score()).isEqualTo(0.9);
    }

    @Test
    void liftsACandidateAboutAnActiveHazardAboveItsNeighbour() {
        List<RankedResult> results = ranker.rank(List.of(quake, flood), List.of(),
                Set.of(DisasterType.FLOOD), Set.of(), 5);

        assertThat(results).extracting(RankedResult::content).containsExactly("flood text", "quake text");
    }

    @Test
    void neverDropsACandidateBecauseOfActiveHazards() {
        List<RankedResult> results = ranker.rank(List.of(quake, flood, fire), List.of(),
                Set.of(DisasterType.FLOOD), Set.of(), 5);

        assertThat(results).hasSize(3);
    }

    @Test
    void returnsAtMostTopK() {
        assertThat(ranker.rank(List.of(quake, flood, fire), List.of(), Set.of(), Set.of(), 2)).hasSize(2);
    }

    @Test
    void ranksACountryMatchAboveAnEqualOtherCountryPassage() {
        HybridRanker countryRanker = new HybridRanker(new HazardBoost(0.3, 0.15), new CountryBoost(0.15));
        HybridRanker.Candidate us = countryCandidate("us text", 0.8, "US");
        HybridRanker.Candidate gb = countryCandidate("gb text", 0.8, "GB");

        // US is first in vector order, so fusion alone would rank it above GB.
        List<RankedResult> results = countryRanker.rank(List.of(us, gb), List.of(), Set.of(), Set.of(), "GB", 2);

        assertThat(results).extracting(RankedResult::content).containsExactly("gb text", "us text");
    }

    @Test
    void keepsAGlobalPassageWhenNoCountryPassageMatches() {
        HybridRanker countryRanker = new HybridRanker(new HazardBoost(0.3, 0.15), new CountryBoost(0.15));
        HybridRanker.Candidate global = countryCandidate("global text", 0.7, null);
        HybridRanker.Candidate us = countryCandidate("us text", 0.9, "US");

        List<RankedResult> results = countryRanker.rank(List.of(us, global), List.of(), Set.of(), Set.of(), "GB", 5);

        assertThat(results).extracting(RankedResult::content).containsExactly("us text", "global text");
    }

    @Test
    void noCountryLeavesOrderUnchangedEvenWhenCountryBoostIsConfigured() {
        HybridRanker countryRanker = new HybridRanker(new HazardBoost(0.3, 0.15), new CountryBoost(0.15));
        HybridRanker.Candidate us = countryCandidate("us text", 0.8, "US");
        HybridRanker.Candidate gb = countryCandidate("gb text", 0.8, "GB");

        List<RankedResult> results = countryRanker.rank(List.of(us, gb), List.of(), Set.of(), Set.of(), null, 2);

        assertThat(results).extracting(RankedResult::content).containsExactly("us text", "gb text");
    }

    private static HybridRanker.Candidate countryCandidate(String content, double similarity, String country) {
        return new HybridRanker.Candidate(new ChunkId(UUID.randomUUID()), new DocumentId(UUID.randomUUID()),
                content, similarity, Set.of(), country);
    }

    private static HybridRanker.Candidate candidate(String content, double similarity, DisasterType hazard) {
        return new HybridRanker.Candidate(new ChunkId(UUID.randomUUID()), new DocumentId(UUID.randomUUID()),
                content, similarity, Set.of(hazard));
    }
}

package com.sheltr.search.domain;

import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.DisasterType;
import com.sheltr.knowledge.domain.DocumentId;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class HybridRanker {

    /** @param countryCode ISO code of the document's country, or null for global guidance. */
    public record Candidate(ChunkId chunkId, DocumentId documentId, String content, double similarity,
                            Set<DisasterType> hazards, String countryCode) {
        public Candidate(ChunkId chunkId, DocumentId documentId, String content, double similarity,
                         Set<DisasterType> hazards) {
            this(chunkId, documentId, content, similarity, hazards, null);
        }
    }

    private record Scored(Candidate candidate, double score) {
    }

    private final HazardBoost hazardBoost;
    private final CountryBoost countryBoost;

    public HybridRanker(HazardBoost hazardBoost) {
        this(hazardBoost, CountryBoost.NONE);
    }

    public HybridRanker(HazardBoost hazardBoost, CountryBoost countryBoost) {
        this.hazardBoost = hazardBoost;
        this.countryBoost = countryBoost;
    }

    public List<RankedResult> rank(List<Candidate> vectorHits, List<Candidate> keywordHits,
                                   Set<DisasterType> active, Set<DisasterType> related, int topK) {
        return rank(vectorHits, keywordHits, active, related, null, topK);
    }

    public List<RankedResult> rank(List<Candidate> vectorHits, List<Candidate> keywordHits,
                                   Set<DisasterType> active, Set<DisasterType> related, String country, int topK) {
        Map<ChunkId, Candidate> byId = new LinkedHashMap<>();
        vectorHits.forEach(candidate -> byId.putIfAbsent(candidate.chunkId(), candidate));
        keywordHits.forEach(candidate -> byId.putIfAbsent(candidate.chunkId(), candidate));

        Map<ChunkId, Double> fused = RankFusion.fuse(List.of(
                vectorHits.stream().map(Candidate::chunkId).toList(),
                keywordHits.stream().map(Candidate::chunkId).toList()));

        // Stream.sorted is stable, so equal scores keep fusion order.
        return fused.entrySet().stream()
                .map(entry -> {
                    Candidate candidate = byId.get(entry.getKey());
                    double factor = hazardBoost.factor(candidate.hazards(), active, related)
                            * countryBoost.factor(candidate.countryCode(), country);
                    return new Scored(candidate, entry.getValue() * factor);
                })
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(topK)
                .map(scored -> new RankedResult(scored.candidate().chunkId(), scored.candidate().documentId(),
                        scored.candidate().content(),
                        Math.max(0.0, Math.min(1.0, scored.candidate().similarity()))))
                .toList();
    }
}

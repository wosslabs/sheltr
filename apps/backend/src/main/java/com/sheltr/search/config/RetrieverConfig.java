package com.sheltr.search.config;

import com.sheltr.knowledge.domain.ChunkRepository;
import com.sheltr.knowledge.domain.EmbeddingGenerator;
import com.sheltr.search.domain.CountryBoost;
import com.sheltr.search.domain.HazardBoost;
import com.sheltr.search.domain.HybridRanker;
import com.sheltr.search.domain.RelatedHazards;
import com.sheltr.search.domain.Retriever;
import com.sheltr.search.infrastructure.GuardedRetriever;
import com.sheltr.search.infrastructure.HybridSearchAdapter;
import com.sheltr.search.infrastructure.JooqRelatedHazards;
import com.sheltr.search.infrastructure.PgvectorSemanticSearchAdapter;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.Set;

@Configuration
public class RetrieverConfig {

    @Bean
    public RelatedHazards relatedHazards(DataSource dataSource) {
        return new JooqRelatedHazards(dataSource);
    }

    @Bean
    public Retriever retriever(DataSource dataSource, EmbeddingGenerator embeddingGenerator,
                               ChunkRepository chunkRepository, RelatedHazards relatedHazards,
                               @Value("${sheltr.search.statuses}") Set<String> statuses,
                               @Value("${sheltr.search.mode}") String mode,
                               @Value("${sheltr.search.candidates-per-list}") int candidatesPerList,
                               @Value("${sheltr.search.hazard-boost}") double hazardBoost,
                               @Value("${sheltr.search.related-hazard-boost}") double relatedHazardBoost,
                               @Value("${sheltr.search.country-boost}") double countryBoost) {
        Retriever search = switch (mode) {
            case "vector" -> new PgvectorSemanticSearchAdapter(dataSource, embeddingGenerator, statuses,
                    new CountryBoost(countryBoost));
            case "hybrid" -> new HybridSearchAdapter(dataSource, embeddingGenerator, statuses, relatedHazards,
                    new HybridRanker(new HazardBoost(hazardBoost, relatedHazardBoost), new CountryBoost(countryBoost)),
                    candidatesPerList);
            default -> throw new IllegalArgumentException(
                    "sheltr.search.mode must be 'vector' or 'hybrid', got '" + mode + "'");
        };
        return new GuardedRetriever(search, chunkRepository, embeddingGenerator.modelName());
    }

    /** Says at startup what GuardedRetriever will refuse on, so the cause is in the log before a user asks. */
    @Bean
    public ApplicationRunner staleEmbeddingsWarning(ChunkRepository chunkRepository,
                                                    EmbeddingGenerator embeddingGenerator) {
        return args -> {
            int stale = chunkRepository.countEmbeddedWithOtherModel(embeddingGenerator.modelName());
            if (stale > 0) {
                LoggerFactory.getLogger(RetrieverConfig.class).error(
                        "{} chunk(s) were embedded by a model other than '{}'. Questions are refused "
                                + "until `make seed-embeddings` re-embeds them.",
                        stale, embeddingGenerator.modelName());
            }
        };
    }
}

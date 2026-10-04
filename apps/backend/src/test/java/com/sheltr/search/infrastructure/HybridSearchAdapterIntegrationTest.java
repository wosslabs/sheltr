package com.sheltr.search.infrastructure;

import com.sheltr.knowledge.domain.DisasterType;
import com.sheltr.knowledge.domain.Embedding;
import com.sheltr.knowledge.domain.EmbeddingGenerator;
import com.sheltr.search.domain.HazardBoost;
import com.sheltr.search.domain.HybridRanker;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.SearchQuery;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every seed chunk gets the same vector, so vector search cannot tell them apart and any
 * ordering seen here comes from keyword search and fusion.
 */
@Testcontainers
class HybridSearchAdapterIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static final EmbeddingGenerator SAME_VECTOR = new EmbeddingGenerator() {
        @Override
        public Embedding embed(String text) {
            float[] values = new float[Embedding.DIMENSION];
            values[0] = 1.0f;
            return new Embedding(values);
        }

        @Override
        public String modelName() {
            return "model-a";
        }
    };

    private HybridSearchAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        StringBuilder vector = new StringBuilder("[1");
        for (int i = 1; i < Embedding.DIMENSION; i++) {
            vector.append(",0");
        }
        vector.append(']');
        try (Connection conn = dataSource.getConnection();
             PreparedStatement statement = conn.prepareStatement(
                     "UPDATE knowledge.chunks SET embedding = ?::vector, embedding_model = 'model-a'")) {
            statement.setString(1, vector.toString());
            statement.executeUpdate();
        }

        adapter = new HybridSearchAdapter(dataSource, SAME_VECTOR, Set.of("ACTIVE", "SEED"),
                new JooqRelatedHazards(dataSource), new HybridRanker(new HazardBoost(0.3, 0.15)), 40);
    }

    @Test
    void keywordMatchPutsTheOnlyMatchingChunkFirst() {
        // Only one seed chunk mentions a generator or carbon monoxide.
        List<RankedResult> results = adapter.retrieve(new SearchQuery("generator carbon monoxide"), 5);

        assertThat(results).hasSize(5);
        assertThat(results.get(0).content()).contains("carbon monoxide");
    }

    @Test
    void aQuestionOfOnlyCommonWordsStillReturnsVectorResults() {
        // Every word is an English stop word, so the keyword query is empty.
        List<RankedResult> results = adapter.retrieve(new SearchQuery("what should I do"), 5);

        assertThat(results).hasSize(5);
    }

    @Test
    void activeHazardsNeverReduceTheNumberOfResults() {
        List<RankedResult> results = adapter.retrieve(
                new SearchQuery("generator carbon monoxide", Set.of(DisasterType.FLOOD)), 5);

        assertThat(results).hasSize(5);
        assertThat(results.get(0).content()).contains("carbon monoxide");
    }
}

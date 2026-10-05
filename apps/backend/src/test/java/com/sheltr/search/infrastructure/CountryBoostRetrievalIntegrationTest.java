package com.sheltr.search.infrastructure;

import com.sheltr.knowledge.domain.Embedding;
import com.sheltr.knowledge.domain.EmbeddingGenerator;
import com.sheltr.search.domain.CountryBoost;
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
import java.sql.ResultSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every seed chunk has the same vector, so all similarities are equal. One document is marked GB.
 * With topK equal to the chunk count the candidate pool covers every chunk, so the country
 * boost alone decides the order: GB chunks first, nothing removed.
 */
@Testcontainers
class CountryBoostRetrievalIntegrationTest {

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

    private PGSimpleDataSource dataSource;
    private int totalChunks;
    private int gbChunks;
    private String gbDocumentId;

    @BeforeEach
    void setUp() throws Exception {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        StringBuilder vector = new StringBuilder("[1");
        for (int i = 1; i < Embedding.DIMENSION; i++) {
            vector.append(",0");
        }
        vector.append(']');

        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement statement = conn.prepareStatement(
                    "UPDATE knowledge.chunks SET embedding = ?::vector, embedding_model = 'model-a'")) {
                statement.setString(1, vector.toString());
                statement.executeUpdate();
            }
            try (PreparedStatement pick = conn.prepareStatement(
                    "SELECT document_id FROM knowledge.chunks ORDER BY id LIMIT 1");
                 ResultSet rs = pick.executeQuery()) {
                rs.next();
                gbDocumentId = rs.getString(1);
            }
            try (PreparedStatement mark = conn.prepareStatement(
                    "UPDATE knowledge.documents SET country_code = 'GB' WHERE id = ?::uuid")) {
                mark.setString(1, gbDocumentId);
                mark.executeUpdate();
            }
            totalChunks = count(conn, "SELECT count(*) FROM knowledge.chunks WHERE embedding IS NOT NULL");
            gbChunks = count(conn, "SELECT count(*) FROM knowledge.chunks WHERE document_id = '"
                    + gbDocumentId + "'::uuid");
        }
    }

    @Test
    void vectorPathPutsEqualRelevanceGbPassagesFirstAndKeepsEverythingElse() {
        PgvectorSemanticSearchAdapter adapter = new PgvectorSemanticSearchAdapter(
                dataSource, SAME_VECTOR, Set.of("ACTIVE", "SEED"), new CountryBoost(0.15));

        List<RankedResult> results = adapter.retrieve(new SearchQuery("flood water", Set.of(), "GB"), totalChunks);

        assertThat(results).hasSize(totalChunks);
        assertThat(results.subList(0, gbChunks))
                .allSatisfy(result -> assertThat(result.documentId().value().toString()).isEqualTo(gbDocumentId));
    }

    @Test
    void vectorPathWithoutCountryIsUnchanged() {
        PgvectorSemanticSearchAdapter adapter = new PgvectorSemanticSearchAdapter(
                dataSource, SAME_VECTOR, Set.of("ACTIVE", "SEED"), new CountryBoost(0.15));

        List<RankedResult> results = adapter.retrieve(new SearchQuery("flood water"), 3);

        assertThat(results).hasSize(3);
    }

    @Test
    void hybridPathPutsGbPassagesFirstWhenPoolCoversAllChunks() {
        HybridSearchAdapter adapter = new HybridSearchAdapter(dataSource, SAME_VECTOR, Set.of("ACTIVE", "SEED"),
                new JooqRelatedHazards(dataSource),
                new HybridRanker(
                        new HazardBoost(0.3, 0.15), new CountryBoost(0.15)),
                totalChunks);

        // Keyword search matches nothing for a stop-word-only question, so only vector hits count.
        List<RankedResult> results = adapter.retrieve(new SearchQuery("what should I do", Set.of(), "GB"), totalChunks);

        assertThat(results).hasSize(totalChunks);
        assertThat(results.subList(0, gbChunks))
                .allSatisfy(result -> assertThat(result.documentId().value().toString()).isEqualTo(gbDocumentId));
    }

    private static int count(Connection conn, String sql) throws Exception {
        try (PreparedStatement statement = conn.prepareStatement(sql); ResultSet rs = statement.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }
}

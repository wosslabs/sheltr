package com.sheltr.search.infrastructure;

import com.sheltr.knowledge.domain.Embedding;
import com.sheltr.knowledge.domain.EmbeddingGenerator;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RetrievalFilterIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static final EmbeddingGenerator MODEL_A = new EmbeddingGenerator() {
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

    @BeforeEach
    void migrateAndEmbedSeedChunks() throws Exception {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        setAllEmbeddings("model-a");
    }

    @Test
    void returnsNothingWhenSeedStatusIsNotAllowed() {
        var adapter = new PgvectorSemanticSearchAdapter(dataSource, MODEL_A, Set.of("ACTIVE"));

        assertThat(adapter.retrieve(new SearchQuery("flood water"), 5)).isEmpty();
    }

    @Test
    void returnsSeedChunksWhenSeedStatusIsAllowed() {
        var adapter = new PgvectorSemanticSearchAdapter(dataSource, MODEL_A, Set.of("ACTIVE", "SEED"));

        assertThat(adapter.retrieve(new SearchQuery("flood water"), 5)).hasSize(5);
    }

    @Test
    void ignoresChunksEmbeddedByAnotherModel() throws Exception {
        setAllEmbeddings("model-b");
        var adapter = new PgvectorSemanticSearchAdapter(dataSource, MODEL_A, Set.of("ACTIVE", "SEED"));

        assertThat(adapter.retrieve(new SearchQuery("flood water"), 5)).isEmpty();
    }

    private void setAllEmbeddings(String model) throws Exception {
        StringBuilder vector = new StringBuilder("[1");
        for (int i = 1; i < Embedding.DIMENSION; i++) {
            vector.append(",0");
        }
        vector.append(']');
        try (Connection conn = dataSource.getConnection();
             PreparedStatement statement = conn.prepareStatement(
                     "UPDATE knowledge.chunks SET embedding = ?::vector, embedding_model = ?")) {
            statement.setString(1, vector.toString());
            statement.setString(2, model);
            statement.executeUpdate();
        }
    }
}

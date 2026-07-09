package com.sheltr.search.infrastructure;

import com.sheltr.knowledge.domain.Embedding;
import com.sheltr.knowledge.domain.EmbeddingGenerator;
import com.sheltr.knowledge.infrastructure.GeminiEmbeddingGeneratorAdapter;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.SearchQuery;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class PgvectorSemanticSearchAdapterIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Test
    void findsMostSimilarChunkByRealEmbedding() throws Exception {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();

        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        GoogleGenAiEmbeddingConnectionDetails connectionDetails = GoogleGenAiEmbeddingConnectionDetails.builder()
                .apiKey(System.getenv("GEMINI_API_KEY"))
                .build();
        EmbeddingModel embeddingModel = new GoogleGenAiTextEmbeddingModel(connectionDetails,
                GoogleGenAiTextEmbeddingOptions.builder().model("gemini-embedding-001").dimensions(768).build());
        EmbeddingGenerator embeddingGenerator = new GeminiEmbeddingGeneratorAdapter(embeddingModel);

        // Manually embed and store the two seeded chunks this test cares about.
        embedAndStore(dataSource, embeddingGenerator, "b0000000-0000-0000-0000-000000000006");
        embedAndStore(dataSource, embeddingGenerator, "b0000000-0000-0000-0000-000000000029");

        PgvectorSemanticSearchAdapter adapter = new PgvectorSemanticSearchAdapter(dataSource, embeddingGenerator);

        List<RankedResult> results = adapter.retrieve(new SearchQuery("Is it safe to drink flood water?"), 5);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).chunkId().value())
                .isEqualTo(UUID.fromString("b0000000-0000-0000-0000-000000000006"));
    }

    private static void embedAndStore(DataSource dataSource, EmbeddingGenerator generator, String chunkId) throws Exception {
        String content;
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("SELECT content FROM knowledge.chunks WHERE id = ?")) {
            stmt.setObject(1, UUID.fromString(chunkId));
            try (var rs = stmt.executeQuery()) {
                rs.next();
                content = rs.getString(1);
            }
        }
        Embedding embedding = generator.embed(content);
        float[] values = embedding.values();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(values[i]);
        }
        sb.append(']');
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("UPDATE knowledge.chunks SET embedding = ?::vector WHERE id = ?")) {
            stmt.setString(1, sb.toString());
            stmt.setObject(2, UUID.fromString(chunkId));
            stmt.executeUpdate();
        }
    }
}

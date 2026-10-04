package com.sheltr.knowledge.infrastructure;

import com.sheltr.knowledge.domain.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class JooqChunkRepositoryInsertTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Test
    void insertsANewChunkWithEmbeddingAlreadySet() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();

        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        JooqKnowledgeDocumentRepository documentRepository = new JooqKnowledgeDocumentRepository(dataSource);
        JooqChunkRepository chunkRepository = new JooqChunkRepository(dataSource);

        DocumentId documentId = new DocumentId(UUID.randomUUID());
        documentRepository.save(new KnowledgeDocument(
                documentId, "Test Document", "FEMA", "https://example.org/doc.pdf",
                Set.of(DisasterType.FLOOD), LocalDate.now(), new DocumentVersion(1),
                new Checksum("docchecksum"), null, DocumentStatus.ACTIVE));

        float[] vector = new float[768];
        vector[0] = 0.5f;
        ChunkId chunkId = new ChunkId(UUID.randomUUID());
        Chunk chunk = new Chunk(chunkId, documentId, new ChunkContent("Boil water for one minute."),
                new Embedding(vector), new PageNumber(2), 0,
                new ChunkMetadata("Water Safety", Set.of(DisasterType.FLOOD)), new Checksum("chunkchecksum"));

        chunkRepository.insert(chunk, "test-model");

        Optional<Chunk> reloaded = chunkRepository.findById(chunkId);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().content().value()).isEqualTo("Boil water for one minute.");
        assertThat(reloaded.get().pageNumber()).contains(new PageNumber(2));
    }
}

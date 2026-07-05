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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class JooqChunkRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static DataSource dataSource() {
        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setURL(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return ds;
    }

    private static void migrate() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();
    }

    @Test
    void savesAndReadsBackAnEmbedding() {
        migrate();
        DataSource dataSource = dataSource();
        JooqKnowledgeDocumentRepository documentRepository = new JooqKnowledgeDocumentRepository(dataSource);
        JooqChunkRepository chunkRepository = new JooqChunkRepository(dataSource);

        DocumentId documentId = new DocumentId(UUID.randomUUID());
        insertDocument(dataSource, documentId);
        ChunkId chunkId = new ChunkId(UUID.randomUUID());
        insertChunkWithoutEmbedding(dataSource, chunkId, documentId);

        List<Chunk> pending = chunkRepository.findPendingEmbeddings();
        assertThat(pending).extracting(Chunk::id).contains(chunkId);

        float[] vector = new float[768];
        vector[0] = 0.42f;
        Chunk toSave = pending.stream().filter(c -> c.id().equals(chunkId)).findFirst().orElseThrow()
                .withEmbedding(new Embedding(vector));
        chunkRepository.save(toSave);

        List<Chunk> pendingAfter = chunkRepository.findPendingEmbeddings();
        assertThat(pendingAfter).extracting(Chunk::id).doesNotContain(chunkId);

        Optional<KnowledgeDocument> document = documentRepository.findById(documentId);
        assertThat(document).isPresent();
        assertThat(document.get().title()).isEqualTo("Test Document");
    }

    private static void insertDocument(DataSource dataSource, DocumentId id) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "INSERT INTO knowledge.documents (id, title, source_agency, source_uri, source_type, "
                             + "disaster_types, published_date, version, status, checksum) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            stmt.setObject(1, id.value());
            stmt.setString(2, "Test Document");
            stmt.setString(3, "Test Agency");
            stmt.setString(4, "https://example.org/test");
            stmt.setString(5, "PDF");
            stmt.setArray(6, conn.createArrayOf("text", new String[]{"FLOOD"}));
            stmt.setObject(7, LocalDate.of(2024, 1, 1));
            stmt.setInt(8, 1);
            stmt.setString(9, "ACTIVE");
            stmt.setString(10, "checksum123");
            stmt.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void insertChunkWithoutEmbedding(DataSource dataSource, ChunkId chunkId, DocumentId documentId) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "INSERT INTO knowledge.chunks (id, document_id, content, chunk_index, section_heading, checksum) "
                             + "VALUES (?, ?, ?, ?, ?, ?)")) {
            stmt.setObject(1, chunkId.value());
            stmt.setObject(2, documentId.value());
            stmt.setString(3, "Boil water for one minute.");
            stmt.setInt(4, 0);
            stmt.setString(5, "Water Safety");
            stmt.setString(6, "chunkchecksum");
            stmt.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

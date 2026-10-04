package com.sheltr.ingestion.infrastructure;

import com.sheltr.ingestion.domain.StoredDocument;
import com.sheltr.knowledge.domain.*;
import com.sheltr.knowledge.infrastructure.JooqKnowledgeDocumentRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class JooqCorpusCatalogIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Test
    void findsOnlyActiveDocumentsAndUpsertsRelatedHazards() throws Exception {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        DocumentId documentId = new DocumentId(UUID.randomUUID());
        new JooqKnowledgeDocumentRepository(dataSource).save(new KnowledgeDocument(
                documentId, "Quake Guide", "Agency", "https://example.org/quake.pdf",
                Set.of(DisasterType.EARTHQUAKE), LocalDate.of(2019, 11, 2), new DocumentVersion(1),
                new Checksum("abc"), null, DocumentStatus.ACTIVE, "test-fixture-licence"));
        JooqCorpusCatalog catalog = new JooqCorpusCatalog(dataSource);

        assertThat(catalog.findActiveDocumentId("https://example.org/quake.pdf")).contains(documentId);
        assertThat(catalog.findActiveDocumentId("https://example.org/unknown.pdf")).isEmpty();
        // Seed rows have status SEED and must never count as corpus documents.
        assertThat(catalog.findActiveDocumentId("https://www.fema.gov/earthquakes")).isEmpty();
        assertThat(catalog.findActiveDocument("https://example.org/quake.pdf"))
                .contains(new StoredDocument(documentId, "abc"));
        assertThat(catalog.findActiveDocument("https://www.fema.gov/earthquakes")).isEmpty();

        catalog.upsertRelatedHazard(DisasterType.EARTHQUAKE, DisasterType.LANDSLIDE, documentId, "first");
        catalog.upsertRelatedHazard(DisasterType.EARTHQUAKE, DisasterType.LANDSLIDE, documentId, "second");

        try (Connection conn = dataSource.getConnection();
             ResultSet rs = conn.createStatement().executeQuery(
                     "SELECT count(*), max(note) FROM knowledge.related_hazards")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
            assertThat(rs.getString(2)).isEqualTo("second");
        }
    }
}

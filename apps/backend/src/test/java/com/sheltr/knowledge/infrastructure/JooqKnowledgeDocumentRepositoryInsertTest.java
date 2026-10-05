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
class JooqKnowledgeDocumentRepositoryInsertTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Test
    void savesAndReadsBackANewDocument() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();

        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        JooqKnowledgeDocumentRepository repository = new JooqKnowledgeDocumentRepository(dataSource);
        DocumentId id = new DocumentId(UUID.randomUUID());
        KnowledgeDocument document = new KnowledgeDocument(
                id, "Disaster Preparedness Guide for Caregivers", "FEMA",
                "https://www.ready.gov/sites/default/files/2024-02/ready-gov_caregivers-preparedness-guide.pdf",
                Set.of(DisasterType.FLOOD, DisasterType.WILDFIRE), LocalDate.now(), new DocumentVersion(1),
                new Checksum("abc123def456"), null, DocumentStatus.ACTIVE);

        repository.save(document);

        Optional<KnowledgeDocument> reloaded = repository.findById(id);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().title()).isEqualTo("Disaster Preparedness Guide for Caregivers");
        assertThat(reloaded.get().disasterTypes()).containsExactlyInAnyOrder(DisasterType.FLOOD, DisasterType.WILDFIRE);
        assertThat(reloaded.get().checksum()).isEqualTo(new Checksum("abc123def456"));
    }

    @Test
    void savesAndReadsBackTheCountryCode() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();

        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        JooqKnowledgeDocumentRepository repository = new JooqKnowledgeDocumentRepository(dataSource);
        DocumentId id = new DocumentId(UUID.randomUUID());
        KnowledgeDocument document = new KnowledgeDocument(
                id, "Flood guidance for the UK", "Environment Agency",
                "https://www.gov.uk/guidance/prepare-for-a-flood", Set.of(DisasterType.FLOOD), LocalDate.now(),
                new DocumentVersion(1), new Checksum("uk123"), null, DocumentStatus.ACTIVE, "Open Government Licence",
                "GB");

        repository.save(document);

        assertThat(repository.findById(id).orElseThrow().country()).contains("GB");
    }
}

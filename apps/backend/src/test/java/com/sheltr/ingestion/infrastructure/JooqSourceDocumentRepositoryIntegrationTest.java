package com.sheltr.ingestion.infrastructure;

import com.sheltr.ingestion.domain.IngestionStatus;
import com.sheltr.ingestion.domain.SourceDocument;
import com.sheltr.ingestion.domain.SourceType;
import com.sheltr.knowledge.domain.Checksum;
import com.sheltr.knowledge.domain.DocumentId;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class JooqSourceDocumentRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Test
    void savesAndReloadsASourceDocumentAcrossStatusTransitions() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();

        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        JooqSourceDocumentRepository repository = new JooqSourceDocumentRepository(dataSource);
        DocumentId id = new DocumentId(UUID.randomUUID());
        SourceDocument document = new SourceDocument(id, "https://example.org/doc.pdf", SourceType.PDF);
        document.markDownloaded(new Checksum("abc123"));

        repository.save(document);
        Optional<SourceDocument> reloaded = repository.findById(id);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().status()).isEqualTo(IngestionStatus.DOWNLOADED);
        assertThat(reloaded.get().checksum()).contains(new Checksum("abc123"));

        document.markIndexed();
        repository.save(document);
        Optional<SourceDocument> reloadedAgain = repository.findById(id);
        assertThat(reloadedAgain.get().status()).isEqualTo(IngestionStatus.INDEXED);
    }
}

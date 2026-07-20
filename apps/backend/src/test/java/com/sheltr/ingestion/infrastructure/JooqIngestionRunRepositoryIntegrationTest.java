package com.sheltr.ingestion.infrastructure;

import com.sheltr.ingestion.domain.IngestionRun;
import com.sheltr.ingestion.domain.IngestionRunId;
import com.sheltr.ingestion.domain.RunStatus;
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
class JooqIngestionRunRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Test
    void savesAndReloadsAnIngestionRun() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();

        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        DocumentId documentId = new DocumentId(UUID.randomUUID());
        JooqSourceDocumentRepository documentRepository = new JooqSourceDocumentRepository(dataSource);
        SourceDocument document = new SourceDocument(documentId, "https://example.org/doc.pdf", SourceType.PDF);
        document.markDownloaded(new Checksum("abc123"));
        documentRepository.save(document);

        JooqIngestionRunRepository runRepository = new JooqIngestionRunRepository(dataSource);
        IngestionRunId runId = new IngestionRunId(UUID.randomUUID());
        IngestionRun run = new IngestionRun(runId, documentId);
        runRepository.save(run);

        run.markFailed("Download failed: HTTP 404");
        runRepository.save(run);

        Optional<IngestionRun> reloaded = runRepository.findById(runId);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().status()).isEqualTo(RunStatus.FAILED);
        assertThat(reloaded.get().errorMessage()).contains("Download failed: HTTP 404");
        assertThat(reloaded.get().finishedAt()).isPresent();
    }
}

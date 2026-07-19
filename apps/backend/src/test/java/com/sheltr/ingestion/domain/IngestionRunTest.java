package com.sheltr.ingestion.domain;

import com.sheltr.knowledge.domain.DocumentId;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionRunTest {

    @Test
    void startsInRunningStatusWithNoFinishTime() {
        IngestionRun run = new IngestionRun(new IngestionRunId(UUID.randomUUID()), new DocumentId(UUID.randomUUID()));

        assertThat(run.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(run.finishedAt()).isEmpty();
        assertThat(run.errorMessage()).isEmpty();
    }

    @Test
    void markSucceededSetsStatusAndFinishTime() {
        IngestionRun run = new IngestionRun(new IngestionRunId(UUID.randomUUID()), new DocumentId(UUID.randomUUID()));

        run.markSucceeded();

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.finishedAt()).isPresent();
    }

    @Test
    void markFailedRecordsReasonAndFinishTime() {
        IngestionRun run = new IngestionRun(new IngestionRunId(UUID.randomUUID()), new DocumentId(UUID.randomUUID()));

        run.markFailed("Download failed: HTTP 404");

        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.errorMessage()).contains("Download failed: HTTP 404");
        assertThat(run.finishedAt()).isPresent();
    }
}

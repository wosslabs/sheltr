package com.sheltr.ingestion.domain;

import java.util.Optional;

public interface IngestionRunRepository {
    void save(IngestionRun run);
    Optional<IngestionRun> findById(IngestionRunId id);
}

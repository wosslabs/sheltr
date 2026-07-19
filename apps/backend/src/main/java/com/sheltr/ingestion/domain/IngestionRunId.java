package com.sheltr.ingestion.domain;

import java.util.UUID;

public record IngestionRunId(UUID value) {
    public IngestionRunId {
        if (value == null) throw new IllegalArgumentException("IngestionRunId value must not be null");
    }
}

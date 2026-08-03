package com.sheltr.ingestion.interfaces;

import java.time.Instant;
import java.util.UUID;

public record IngestionRunDto(UUID id, UUID sourceDocumentId, String status, Instant startedAt,
                               Instant finishedAt, String errorMessage) {
}

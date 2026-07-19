package com.sheltr.ingestion.domain;

import com.sheltr.knowledge.domain.DocumentId;

import java.time.Instant;
import java.util.Optional;

public final class IngestionRun {
    private final IngestionRunId id;
    private final DocumentId sourceDocumentId;
    private final Instant startedAt;
    private RunStatus status;
    private Instant finishedAt;
    private String errorMessage;

    public IngestionRun(IngestionRunId id, DocumentId sourceDocumentId) {
        this.id = id;
        this.sourceDocumentId = sourceDocumentId;
        this.startedAt = Instant.now();
        this.status = RunStatus.RUNNING;
    }

    public void markSucceeded() {
        this.status = RunStatus.SUCCEEDED;
        this.finishedAt = Instant.now();
    }

    public void markFailed(String reason) {
        this.status = RunStatus.FAILED;
        this.errorMessage = reason;
        this.finishedAt = Instant.now();
    }

    public IngestionRunId id() { return id; }
    public DocumentId sourceDocumentId() { return sourceDocumentId; }
    public Instant startedAt() { return startedAt; }
    public RunStatus status() { return status; }
    public Optional<Instant> finishedAt() { return Optional.ofNullable(finishedAt); }
    public Optional<String> errorMessage() { return Optional.ofNullable(errorMessage); }
}

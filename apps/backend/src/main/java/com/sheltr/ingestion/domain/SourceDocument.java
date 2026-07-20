package com.sheltr.ingestion.domain;

import com.sheltr.knowledge.domain.Checksum;
import com.sheltr.knowledge.domain.DocumentId;

import java.util.Optional;

public final class SourceDocument {
    private final DocumentId id;
    private final String sourceUri;
    private final SourceType sourceType;
    private Checksum checksum;
    private IngestionStatus status;

    public SourceDocument(DocumentId id, String sourceUri, SourceType sourceType) {
        this.id = id;
        this.sourceUri = sourceUri;
        this.sourceType = sourceType;
        this.status = IngestionStatus.DISCOVERED;
    }

    public SourceDocument(DocumentId id, String sourceUri, SourceType sourceType, Checksum checksum, IngestionStatus status) {
        this.id = id;
        this.sourceUri = sourceUri;
        this.sourceType = sourceType;
        this.checksum = checksum;
        this.status = status;
    }

    public void markDownloaded(Checksum checksum) {
        this.checksum = checksum;
        this.status = IngestionStatus.DOWNLOADED;
    }

    public void markExtracted() { this.status = IngestionStatus.EXTRACTED; }
    public void markCleaned() { this.status = IngestionStatus.CLEANED; }
    public void markChunked() { this.status = IngestionStatus.CHUNKED; }
    public void markIndexed() { this.status = IngestionStatus.INDEXED; }
    public void markFailed() { this.status = IngestionStatus.FAILED; }

    public DocumentId id() { return id; }
    public String sourceUri() { return sourceUri; }
    public SourceType sourceType() { return sourceType; }
    public Optional<Checksum> checksum() { return Optional.ofNullable(checksum); }
    public IngestionStatus status() { return status; }
}

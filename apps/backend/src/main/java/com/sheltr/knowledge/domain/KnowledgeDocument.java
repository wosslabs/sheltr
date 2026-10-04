package com.sheltr.knowledge.domain;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

public final class KnowledgeDocument {
    private final DocumentId id;
    private final String title;
    private final String sourceAgency;
    private final String sourceUri;
    private final Set<DisasterType> disasterTypes;
    private final LocalDate publishedDate;
    private final DocumentVersion version;
    private final Checksum checksum;
    private final DocumentId supersedes;
    private final DocumentStatus status;
    private final String license;

    public KnowledgeDocument(DocumentId id, String title, String sourceAgency, String sourceUri,
                              Set<DisasterType> disasterTypes, LocalDate publishedDate, DocumentVersion version,
                              Checksum checksum, DocumentId supersedes, DocumentStatus status) {
        this(id, title, sourceAgency, sourceUri, disasterTypes, publishedDate, version, checksum, supersedes,
                status, null);
    }

    public KnowledgeDocument(DocumentId id, String title, String sourceAgency, String sourceUri,
                              Set<DisasterType> disasterTypes, LocalDate publishedDate, DocumentVersion version,
                              Checksum checksum, DocumentId supersedes, DocumentStatus status, String license) {
        this.id = id;
        this.title = title;
        this.sourceAgency = sourceAgency;
        this.sourceUri = sourceUri;
        this.disasterTypes = Set.copyOf(disasterTypes == null ? Set.of() : disasterTypes);
        this.publishedDate = publishedDate;
        this.version = version;
        this.checksum = checksum;
        this.supersedes = supersedes;
        this.status = status;
        this.license = license;
    }

    public DocumentId id() { return id; }
    public String title() { return title; }
    public String sourceAgency() { return sourceAgency; }
    public String sourceUri() { return sourceUri; }
    public Set<DisasterType> disasterTypes() { return disasterTypes; }
    public LocalDate publishedDate() { return publishedDate; }
    public DocumentVersion version() { return version; }
    public Checksum checksum() { return checksum; }
    public Optional<DocumentId> supersedes() { return Optional.ofNullable(supersedes); }
    public DocumentStatus status() { return status; }
    public Optional<String> license() { return Optional.ofNullable(license); }
}

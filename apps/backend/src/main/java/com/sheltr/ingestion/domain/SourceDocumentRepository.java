package com.sheltr.ingestion.domain;

import com.sheltr.knowledge.domain.DocumentId;

import java.util.Optional;

public interface SourceDocumentRepository {
    void save(SourceDocument document);
    Optional<SourceDocument> findById(DocumentId id);
}

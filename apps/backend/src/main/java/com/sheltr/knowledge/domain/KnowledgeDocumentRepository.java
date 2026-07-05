package com.sheltr.knowledge.domain;

import java.util.Optional;

public interface KnowledgeDocumentRepository {
    Optional<KnowledgeDocument> findById(DocumentId id);
}

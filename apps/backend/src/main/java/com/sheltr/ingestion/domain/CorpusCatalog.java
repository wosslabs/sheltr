package com.sheltr.ingestion.domain;

import com.sheltr.knowledge.domain.DisasterType;
import com.sheltr.knowledge.domain.DocumentId;

import java.util.Optional;

/** What the corpus loader needs to know about, and record in, the knowledge store. */
public interface CorpusCatalog {
    Optional<DocumentId> findActiveDocumentId(String sourceUri);

    void upsertRelatedHazard(DisasterType hazard, DisasterType relatedHazard, DocumentId source, String note);
}

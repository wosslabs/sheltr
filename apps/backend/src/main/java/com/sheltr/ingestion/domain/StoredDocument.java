package com.sheltr.ingestion.domain;

import com.sheltr.knowledge.domain.DocumentId;

/** An ACTIVE corpus document as stored; checksum may be null for legacy rows. */
public record StoredDocument(DocumentId id, String checksum) {
}

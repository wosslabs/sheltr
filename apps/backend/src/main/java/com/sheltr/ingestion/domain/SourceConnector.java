package com.sheltr.ingestion.domain;

public interface SourceConnector {
    RawContent fetch(String sourceUri);
    SourceMetadata metadata();
}

package com.sheltr.ingestion.domain;

public enum IngestionStatus {
    DISCOVERED, DOWNLOADED, EXTRACTED, CLEANED, CHUNKED, INDEXED, FAILED
}

CREATE SCHEMA IF NOT EXISTS ingestion;

CREATE TABLE ingestion.source_documents (
    id UUID PRIMARY KEY,
    source_uri TEXT NOT NULL,
    source_type TEXT NOT NULL,
    checksum TEXT NOT NULL,
    version INT NOT NULL DEFAULT 1,
    status TEXT NOT NULL DEFAULT 'DISCOVERED',
    discovered_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    downloaded_at TIMESTAMPTZ NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE ingestion.ingestion_runs (
    id UUID PRIMARY KEY,
    source_document_id UUID NOT NULL REFERENCES ingestion.source_documents(id),
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ NULL,
    status TEXT NOT NULL DEFAULT 'RUNNING',
    error_message TEXT NULL
);

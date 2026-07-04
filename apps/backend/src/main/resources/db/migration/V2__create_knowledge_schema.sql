CREATE SCHEMA IF NOT EXISTS knowledge;

CREATE TABLE knowledge.documents (
    id UUID PRIMARY KEY,
    title TEXT NOT NULL,
    source_agency TEXT NOT NULL,
    source_uri TEXT NOT NULL,
    source_type TEXT NOT NULL,
    disaster_types TEXT[] NOT NULL DEFAULT '{}',
    published_date DATE,
    version INT NOT NULL DEFAULT 1,
    supersedes_id UUID NULL REFERENCES knowledge.documents(id),
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    checksum TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE knowledge.chunks (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES knowledge.documents(id),
    content TEXT NOT NULL,
    content_tsv TSVECTOR,
    embedding VECTOR(768),
    page_number INT NULL,
    chunk_index INT NOT NULL,
    section_heading TEXT,
    checksum TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_knowledge_chunks_embedding ON knowledge.chunks USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_knowledge_chunks_content_tsv ON knowledge.chunks USING gin (content_tsv);
CREATE INDEX idx_knowledge_chunks_document_id ON knowledge.chunks (document_id);

-- Reuse terms of each ingested document. Required by ingestion; seed rows have none.
ALTER TABLE knowledge.documents ADD COLUMN license TEXT;

-- The V5 rows are hand-written test fixtures, not official text. Keep them out of
-- real retrieval, which only returns statuses listed in sheltr.search.statuses.
UPDATE knowledge.documents SET status = 'SEED' WHERE checksum LIKE 'seed-%';

UPDATE knowledge.documents
SET disaster_types = array_replace(disaster_types, 'HURRICANE', 'TROPICAL_CYCLONE');

-- Which model produced each vector, so vectors from different models are never mixed.
ALTER TABLE knowledge.chunks ADD COLUMN embedding_model TEXT;
-- Existing vectors have no recorded model; clear them so backfill re-embeds and records it.
UPDATE knowledge.chunks SET embedding = NULL WHERE embedding IS NOT NULL;

-- content_tsv existed but was never populated. Dropping the column drops its GIN index.
ALTER TABLE knowledge.chunks DROP COLUMN content_tsv;
ALTER TABLE knowledge.chunks ADD COLUMN content_tsv TSVECTOR
    GENERATED ALWAYS AS (to_tsvector('english'::regconfig, content)) STORED;
CREATE INDEX idx_knowledge_chunks_content_tsv ON knowledge.chunks USING gin (content_tsv);

CREATE TABLE knowledge.related_hazards (
    hazard TEXT NOT NULL,
    related_hazard TEXT NOT NULL,
    source_document_id UUID NOT NULL REFERENCES knowledge.documents(id),
    note TEXT,
    PRIMARY KEY (hazard, related_hazard)
);

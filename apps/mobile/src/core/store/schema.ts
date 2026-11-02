/*
 * On-device content store schema. Content rows (documents, chunks, document_hazards, schema_version) always hold
 * exactly one complete bundle: an update replaces them all inside one transaction, so a failure leaves the old
 * bundle in place. The settings table is separate and is never touched by an update.
 */
export const SCHEMA = `
CREATE TABLE IF NOT EXISTS documents (
  id TEXT NOT NULL PRIMARY KEY,
  title TEXT NOT NULL,
  agency TEXT NOT NULL,
  published_date TEXT NOT NULL,
  licence TEXT NOT NULL,
  country TEXT,
  source_uri TEXT NOT NULL,
  source_type TEXT NOT NULL,
  checksum TEXT NOT NULL,
  file_checksum TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS chunks (
  id TEXT NOT NULL PRIMARY KEY,
  document_id TEXT NOT NULL REFERENCES documents(id),
  content TEXT NOT NULL,
  page_number INTEGER,
  position INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS chunks_document_id ON chunks(document_id, position);

CREATE TABLE IF NOT EXISTS document_hazards (
  document_id TEXT NOT NULL REFERENCES documents(id),
  hazard TEXT NOT NULL,
  PRIMARY KEY (document_id, hazard)
);

CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts5(
  chunk_id UNINDEXED,
  content
);

CREATE TRIGGER IF NOT EXISTS chunks_fts_insert AFTER INSERT ON chunks BEGIN
  INSERT INTO chunks_fts(chunk_id, content) VALUES (new.id, new.content);
END;

CREATE TRIGGER IF NOT EXISTS chunks_fts_delete AFTER DELETE ON chunks BEGIN
  DELETE FROM chunks_fts WHERE chunk_id = old.id;
END;

CREATE TABLE IF NOT EXISTS schema_version (
  id INTEGER NOT NULL PRIMARY KEY CHECK (id = 1),
  bundle_version TEXT NOT NULL,
  format_version INTEGER NOT NULL,
  last_sync_epoch_seconds INTEGER
);

CREATE TABLE IF NOT EXISTS settings (
  key TEXT NOT NULL PRIMARY KEY,
  value TEXT NOT NULL
);
`;

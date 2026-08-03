-- DISCOVERED source documents exist before download; checksum is only known after DOWNLOADED.
ALTER TABLE ingestion.source_documents ALTER COLUMN checksum DROP NOT NULL;

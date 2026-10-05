-- ISO 3166-1 alpha-2 country the guide is for. NULL means global guidance.
ALTER TABLE knowledge.documents ADD COLUMN country_code CHAR(2) NULL;
CREATE INDEX IF NOT EXISTS idx_documents_country ON knowledge.documents (country_code);

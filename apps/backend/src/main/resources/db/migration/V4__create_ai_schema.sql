CREATE SCHEMA IF NOT EXISTS ai;

CREATE TABLE ai.conversations (
    id UUID PRIMARY KEY,
    user_ref TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_active_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ai.messages (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES ai.conversations(id),
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    confidence_score NUMERIC NULL,
    confidence_level TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ai.citations (
    id UUID PRIMARY KEY,
    message_id UUID NOT NULL REFERENCES ai.messages(id),
    chunk_id UUID NOT NULL REFERENCES knowledge.chunks(id),
    source_title TEXT NOT NULL,
    source_url TEXT NOT NULL,
    page_number INT NULL
);

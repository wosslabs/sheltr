package com.sheltr.knowledge.domain;

import java.util.Optional;

public final class Chunk {
    private final ChunkId id;
    private final DocumentId documentId;
    private final ChunkContent content;
    private final Embedding embedding;
    private final PageNumber pageNumber;
    private final int chunkIndex;
    private final ChunkMetadata metadata;
    private final Checksum checksum;

    public Chunk(ChunkId id, DocumentId documentId, ChunkContent content, Embedding embedding,
                 PageNumber pageNumber, int chunkIndex, ChunkMetadata metadata, Checksum checksum) {
        this.id = id;
        this.documentId = documentId;
        this.content = content;
        this.embedding = embedding;
        this.pageNumber = pageNumber;
        this.chunkIndex = chunkIndex;
        this.metadata = metadata;
        this.checksum = checksum;
    }

    public Chunk withEmbedding(Embedding newEmbedding) {
        return new Chunk(id, documentId, content, newEmbedding, pageNumber, chunkIndex, metadata, checksum);
    }

    public ChunkId id() { return id; }
    public DocumentId documentId() { return documentId; }
    public ChunkContent content() { return content; }
    public Optional<Embedding> embedding() { return Optional.ofNullable(embedding); }
    public Optional<PageNumber> pageNumber() { return Optional.ofNullable(pageNumber); }
    public int chunkIndex() { return chunkIndex; }
    public ChunkMetadata metadata() { return metadata; }
    public Checksum checksum() { return checksum; }
}

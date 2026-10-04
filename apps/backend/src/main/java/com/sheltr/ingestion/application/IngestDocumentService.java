package com.sheltr.ingestion.application;

import com.sheltr.ingestion.domain.*;
import com.sheltr.ingestion.infrastructure.PdfContentExtractor;
import com.sheltr.knowledge.domain.*;

import java.util.List;
import java.util.UUID;

public class IngestDocumentService {

    private final SourceConnector connector;
    private final PdfContentExtractor pdfContentExtractor;
    private final EmbeddingGenerator embeddingGenerator;
    private final SourceDocumentRepository sourceDocumentRepository;
    private final IngestionRunRepository ingestionRunRepository;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final ChunkRepository chunkRepository;

    public IngestDocumentService(SourceConnector connector, PdfContentExtractor pdfContentExtractor,
                                  EmbeddingGenerator embeddingGenerator,
                                  SourceDocumentRepository sourceDocumentRepository,
                                  IngestionRunRepository ingestionRunRepository,
                                  KnowledgeDocumentRepository knowledgeDocumentRepository,
                                  ChunkRepository chunkRepository) {
        this.connector = connector;
        this.pdfContentExtractor = pdfContentExtractor;
        this.embeddingGenerator = embeddingGenerator;
        this.sourceDocumentRepository = sourceDocumentRepository;
        this.ingestionRunRepository = ingestionRunRepository;
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
        this.chunkRepository = chunkRepository;
    }

    public IngestionRun ingest(String sourceUri, SourceType sourceType, SourceMetadata metadata,
                               String expectedChecksum) {
        DocumentId documentId = new DocumentId(UUID.randomUUID());
        SourceDocument sourceDocument = new SourceDocument(documentId, sourceUri, sourceType);
        // Persist DISCOVERED source document first so ingestion_runs FK is satisfiable.
        sourceDocumentRepository.save(sourceDocument);

        IngestionRun run = new IngestionRun(new IngestionRunId(UUID.randomUUID()), documentId);
        ingestionRunRepository.save(run);

        try {
            if (sourceType != SourceType.PDF) {
                throw new UnsupportedOperationException(
                        "Only PDF ingestion is supported in this slice, got " + sourceType);
            }

            RawContent raw = connector.fetch(sourceUri);
            if (expectedChecksum != null && !expectedChecksum.equalsIgnoreCase(raw.checksum().hex())) {
                throw new IllegalStateException("Checksum mismatch for " + sourceUri + ": approved "
                        + expectedChecksum + " but downloaded " + raw.checksum().hex()
                        + ". The document changed since approval and needs re-approval.");
            }
            sourceDocument.markDownloaded(raw.checksum());
            sourceDocumentRepository.save(sourceDocument);

            List<PageText> pages = pdfContentExtractor.extract(raw.bytes());
            sourceDocument.markExtracted();
            sourceDocumentRepository.save(sourceDocument);

            List<PageText> cleanedPages = pages.stream()
                    .map(page -> new PageText(page.pageNumber(), TextCleaner.clean(page.text())))
                    .toList();
            sourceDocument.markCleaned();
            sourceDocumentRepository.save(sourceDocument);

            List<ChunkDraft> chunkDrafts = Chunker.chunk(cleanedPages);
            if (chunkDrafts.isEmpty()) {
                throw new IllegalStateException("No extractable text in " + sourceUri
                        + ". Scanned or image-only PDFs are not supported.");
            }
            sourceDocument.markChunked();
            sourceDocumentRepository.save(sourceDocument);

            KnowledgeDocument knowledgeDocument = new KnowledgeDocument(
                    documentId, metadata.title(), metadata.sourceAgency(), sourceUri, metadata.disasterTypes(),
                    metadata.publishedDate(), new DocumentVersion(1), raw.checksum(), null,
                    DocumentStatus.ACTIVE, metadata.license());
            knowledgeDocumentRepository.save(knowledgeDocument);

            int chunkIndex = 0;
            for (ChunkDraft draft : chunkDrafts) {
                Embedding embedding = embeddingGenerator.embed(draft.content());
                Chunk chunk = new Chunk(
                        new ChunkId(UUID.randomUUID()), documentId, new ChunkContent(draft.content()), embedding,
                        draft.pageNumber(), chunkIndex++, new ChunkMetadata(null, metadata.disasterTypes()),
                        raw.checksum());
                chunkRepository.insert(chunk, embeddingGenerator.modelName());
            }

            sourceDocument.markIndexed();
            sourceDocumentRepository.save(sourceDocument);

            run.markSucceeded();
            ingestionRunRepository.save(run);
        } catch (Exception e) {
            sourceDocument.markFailed();
            sourceDocumentRepository.save(sourceDocument);
            run.markFailed(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            ingestionRunRepository.save(run);
        }

        return run;
    }
}

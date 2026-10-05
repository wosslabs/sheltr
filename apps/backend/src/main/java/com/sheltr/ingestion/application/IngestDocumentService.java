package com.sheltr.ingestion.application;

import com.sheltr.ingestion.domain.*;
import com.sheltr.ingestion.infrastructure.PdfContentExtractor;
import com.sheltr.knowledge.domain.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class IngestDocumentService {

    private final Map<SourceType, SourceConnector> connectors;
    private final PdfContentExtractor pdfContentExtractor;
    private final EmbeddingGenerator embeddingGenerator;
    private final SourceDocumentRepository sourceDocumentRepository;
    private final IngestionRunRepository ingestionRunRepository;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final ChunkRepository chunkRepository;
    private final SourcePolicy sourcePolicy;

    /**
     * @param connectors the connector for each supported source type; a manifest entry selects one by its type
     */
    public IngestDocumentService(Map<SourceType, SourceConnector> connectors, PdfContentExtractor pdfContentExtractor,
                                  EmbeddingGenerator embeddingGenerator,
                                  SourceDocumentRepository sourceDocumentRepository,
                                  IngestionRunRepository ingestionRunRepository,
                                  KnowledgeDocumentRepository knowledgeDocumentRepository,
                                  ChunkRepository chunkRepository, SourcePolicy sourcePolicy) {
        this.connectors = Map.copyOf(connectors);
        this.pdfContentExtractor = pdfContentExtractor;
        this.embeddingGenerator = embeddingGenerator;
        this.sourceDocumentRepository = sourceDocumentRepository;
        this.ingestionRunRepository = ingestionRunRepository;
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
        this.chunkRepository = chunkRepository;
        this.sourcePolicy = sourcePolicy;
    }

    public IngestionRun ingest(String sourceUri, SourceType sourceType, SourceMetadata metadata,
                               String expectedChecksum) {
        // Checked before the first save: the source document's source_type column is NOT NULL.
        Objects.requireNonNull(sourceType, "source type is required");
        DocumentId documentId = new DocumentId(UUID.randomUUID());
        SourceDocument sourceDocument = new SourceDocument(documentId, sourceUri, sourceType);
        // Persist DISCOVERED source document first so ingestion_runs FK is satisfiable.
        sourceDocumentRepository.save(sourceDocument);

        IngestionRun run = new IngestionRun(new IngestionRunId(UUID.randomUUID()), documentId);
        ingestionRunRepository.save(run);

        try {
            if (!sourcePolicy.allows(sourceUri)) {
                throw new IllegalArgumentException(SourcePolicy.rejectionMessage(sourceUri));
            }
            SourceConnector connector = connectors.get(sourceType);
            if (connector == null) {
                throw new UnsupportedOperationException(
                        "Unsupported source type " + sourceType + ", supported: " + connectors.keySet());
            }

            RawContent raw = connector.fetch(sourceUri);
            if (expectedChecksum != null && !expectedChecksum.equalsIgnoreCase(raw.checksum().hex())) {
                throw new IllegalStateException("Checksum mismatch for " + sourceUri + ": approved "
                        + expectedChecksum + " but downloaded " + raw.checksum().hex()
                        + ". The document changed since approval and needs re-approval.");
            }
            List<ChunkDraft> chunkDrafts;
            if (sourceType == SourceType.HTML) {
                // The HTML connector already returns extracted text, so it is cleaned and chunked once here.
                // Checked before any checksum is recorded, so an empty page leaves no downloaded trace.
                // HTML has no pages, so its chunks carry no page number.
                chunkDrafts = Chunker.chunkUnpaged(TextCleaner.clean(htmlText(raw)));
                if (chunkDrafts.isEmpty()) {
                    throw noExtractableText(sourceUri, sourceType);
                }
                sourceDocument.markDownloaded(raw.checksum());
                sourceDocumentRepository.save(sourceDocument);
                sourceDocument.markExtracted();
                sourceDocumentRepository.save(sourceDocument);
                sourceDocument.markCleaned();
                sourceDocumentRepository.save(sourceDocument);
            } else {
                sourceDocument.markDownloaded(raw.checksum());
                sourceDocumentRepository.save(sourceDocument);

                List<PageText> pages = pdfContentExtractor.extract(raw.bytes());
                sourceDocument.markExtracted();
                sourceDocumentRepository.save(sourceDocument);

                List<PageText> cleanedPages = cleanPages(pages);
                sourceDocument.markCleaned();
                sourceDocumentRepository.save(sourceDocument);

                chunkDrafts = Chunker.chunk(cleanedPages);
                if (chunkDrafts.isEmpty()) {
                    throw noExtractableText(sourceUri, sourceType);
                }
            }
            sourceDocument.markChunked();
            sourceDocumentRepository.save(sourceDocument);

            KnowledgeDocument knowledgeDocument = new KnowledgeDocument(
                    documentId, metadata.title(), metadata.sourceAgency(), sourceUri, metadata.disasterTypes(),
                    metadata.publishedDate(), new DocumentVersion(1), raw.checksum(), null,
                    DocumentStatus.ACTIVE, metadata.license(), metadata.country(), sourceType.name());
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

    private static String htmlText(RawContent raw) {
        return new String(raw.bytes(), StandardCharsets.UTF_8);
    }

    private static List<PageText> cleanPages(List<PageText> pages) {
        return pages.stream()
                .map(page -> new PageText(page.pageNumber(), TextCleaner.clean(page.text())))
                .toList();
    }

    private static IllegalStateException noExtractableText(String sourceUri, SourceType sourceType) {
        String hint = sourceType == SourceType.PDF
                ? " Scanned or image-only PDFs are not supported."
                : " The page has no main text.";
        return new IllegalStateException("No extractable text in " + sourceUri + "." + hint);
    }
}

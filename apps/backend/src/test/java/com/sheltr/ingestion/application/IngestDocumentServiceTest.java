package com.sheltr.ingestion.application;

import com.sheltr.ingestion.domain.*;
import com.sheltr.ingestion.infrastructure.PdfContentExtractor;
import com.sheltr.knowledge.domain.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IngestDocumentServiceTest {

    @Test
    void ingestsAPdfEndToEndWithFakes() throws IOException {
        byte[] pdfBytes = buildTestPdf();
        FakeSourceConnector connector = new FakeSourceConnector(pdfBytes);
        FakeEmbeddingGenerator embeddingGenerator = new FakeEmbeddingGenerator();
        FakeSourceDocumentRepository sourceDocumentRepository = new FakeSourceDocumentRepository();
        FakeIngestionRunRepository ingestionRunRepository = new FakeIngestionRunRepository();
        FakeKnowledgeDocumentRepository knowledgeDocumentRepository = new FakeKnowledgeDocumentRepository();
        FakeChunkRepository chunkRepository = new FakeChunkRepository();

        IngestDocumentService service = new IngestDocumentService(connector, new PdfContentExtractor(),
                embeddingGenerator, sourceDocumentRepository, ingestionRunRepository,
                knowledgeDocumentRepository, chunkRepository);

        IngestionRun run = service.ingest("https://example.org/test.pdf", SourceType.PDF);

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(knowledgeDocumentRepository.saved).hasSize(1);
        assertThat(chunkRepository.inserted).isNotEmpty();
        assertThat(sourceDocumentRepository.lastSaved().status()).isEqualTo(IngestionStatus.INDEXED);
    }

    @Test
    void marksRunFailedWhenDownloadFails() {
        FakeSourceConnector connector = new FakeSourceConnector(null);
        FakeEmbeddingGenerator embeddingGenerator = new FakeEmbeddingGenerator();
        FakeSourceDocumentRepository sourceDocumentRepository = new FakeSourceDocumentRepository();
        FakeIngestionRunRepository ingestionRunRepository = new FakeIngestionRunRepository();
        FakeKnowledgeDocumentRepository knowledgeDocumentRepository = new FakeKnowledgeDocumentRepository();
        FakeChunkRepository chunkRepository = new FakeChunkRepository();

        IngestDocumentService service = new IngestDocumentService(connector, new PdfContentExtractor(),
                embeddingGenerator, sourceDocumentRepository, ingestionRunRepository,
                knowledgeDocumentRepository, chunkRepository);

        IngestionRun run = service.ingest("https://example.org/test.pdf", SourceType.PDF);

        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.errorMessage()).isPresent();
        assertThat(sourceDocumentRepository.lastSaved().status()).isEqualTo(IngestionStatus.FAILED);
        assertThat(knowledgeDocumentRepository.saved).isEmpty();
    }

    private static byte[] buildTestPdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText("Boil water for one minute to make it safe to drink.");
                stream.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static class FakeSourceConnector implements SourceConnector {
        private final byte[] pdfBytes;

        FakeSourceConnector(byte[] pdfBytes) { this.pdfBytes = pdfBytes; }

        @Override
        public RawContent fetch(String sourceUri) {
            if (pdfBytes == null) {
                throw new RuntimeException("Simulated download failure");
            }
            return new RawContent(pdfBytes, new Checksum("fakechecksum123"));
        }

        @Override
        public SourceMetadata metadata() {
            return new SourceMetadata("Test Document", "FEMA", Set.of(DisasterType.FLOOD));
        }
    }

    private static class FakeEmbeddingGenerator implements EmbeddingGenerator {
        @Override
        public Embedding embed(String text) {
            float[] values = new float[768];
            values[0] = 1.0f;
            return new Embedding(values);
        }

        @Override
        public String modelName() { return "test-model"; }
    }

    private static class FakeSourceDocumentRepository implements SourceDocumentRepository {
        final List<SourceDocument> saved = new ArrayList<>();

        @Override
        public void save(SourceDocument document) { saved.add(document); }

        @Override
        public Optional<SourceDocument> findById(DocumentId id) { return Optional.empty(); }

        SourceDocument lastSaved() { return saved.get(saved.size() - 1); }
    }

    private static class FakeIngestionRunRepository implements IngestionRunRepository {
        @Override
        public void save(IngestionRun run) { }

        @Override
        public Optional<IngestionRun> findById(IngestionRunId id) { return Optional.empty(); }
    }

    private static class FakeKnowledgeDocumentRepository implements KnowledgeDocumentRepository {
        final List<KnowledgeDocument> saved = new ArrayList<>();

        @Override
        public Optional<KnowledgeDocument> findById(DocumentId id) { return Optional.empty(); }

        @Override
        public void save(KnowledgeDocument document) { saved.add(document); }
    }

    private static class FakeChunkRepository implements ChunkRepository {
        final List<Chunk> inserted = new ArrayList<>();

        @Override
        public Optional<Chunk> findById(ChunkId id) { return Optional.empty(); }

        @Override
        public List<Chunk> findPendingEmbeddings(String embeddingModel) { return List.of(); }

        @Override
        public void save(Chunk chunk, String embeddingModel) { }

        @Override
        public void insert(Chunk chunk, String embeddingModel) { inserted.add(chunk); }

        @Override
        public int countEmbeddedWithOtherModel(String embeddingModel) { return 0; }
    }
}

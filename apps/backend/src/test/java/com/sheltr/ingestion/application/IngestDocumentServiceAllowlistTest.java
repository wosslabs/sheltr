package com.sheltr.ingestion.application;

import com.sheltr.ingestion.config.HostAllowlistSourcePolicy;
import com.sheltr.ingestion.config.IngestionProperties;
import com.sheltr.ingestion.domain.IngestionStatus;
import com.sheltr.ingestion.domain.IngestionRun;
import com.sheltr.ingestion.domain.RawContent;
import com.sheltr.ingestion.domain.RunStatus;
import com.sheltr.ingestion.domain.SourceConnector;
import com.sheltr.ingestion.domain.SourceMetadata;
import com.sheltr.ingestion.domain.SourceType;
import com.sheltr.ingestion.infrastructure.PdfContentExtractor;
import com.sheltr.knowledge.domain.DisasterType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IngestDocumentServiceAllowlistTest {

    private static final SourceMetadata METADATA = new SourceMetadata(
            "Test Document", "Test Agency", Set.of(DisasterType.FLOOD),
            LocalDate.of(2021, 3, 15), "test-fixture-licence");

    @Test
    void deniedUriFailsTheRunWithoutFetching() {
        CountingConnector connector = new CountingConnector();
        IngestDocumentServiceTest.FakeSourceDocumentRepository sourceDocuments =
                new IngestDocumentServiceTest.FakeSourceDocumentRepository();
        IngestDocumentServiceTest.FakeKnowledgeDocumentRepository knowledgeDocuments =
                new IngestDocumentServiceTest.FakeKnowledgeDocumentRepository();
        IngestDocumentService service = serviceWith(connector, sourceDocuments, knowledgeDocuments);

        IngestionRun run = service.ingest("https://evil.example/x.pdf", SourceType.PDF, METADATA, null);

        assertThat(connector.fetchCount).isZero();
        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.errorMessage().orElseThrow()).isEqualTo("Source host not allowed: evil.example");
        assertThat(sourceDocuments.lastSaved().status()).isEqualTo(IngestionStatus.FAILED);
        assertThat(knowledgeDocuments.saved).isEmpty();
    }

    @Test
    void uriWithCredentialsFailsTheRunWithoutFetchingAndEchoesNoCredentials() {
        CountingConnector connector = new CountingConnector();
        IngestDocumentServiceTest.FakeSourceDocumentRepository sourceDocuments =
                new IngestDocumentServiceTest.FakeSourceDocumentRepository();
        IngestDocumentServiceTest.FakeKnowledgeDocumentRepository knowledgeDocuments =
                new IngestDocumentServiceTest.FakeKnowledgeDocumentRepository();
        IngestDocumentService service = serviceWith(connector, sourceDocuments, knowledgeDocuments);

        IngestionRun run = service.ingest("https://user:secret@www.ready.gov/x.pdf", SourceType.PDF, METADATA, null);

        assertThat(connector.fetchCount).isZero();
        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.errorMessage().orElseThrow()).isEqualTo("Source URI must not contain credentials.");
        assertThat(sourceDocuments.lastSaved().status()).isEqualTo(IngestionStatus.FAILED);
        assertThat(knowledgeDocuments.saved).isEmpty();
    }

    private static IngestDocumentService serviceWith(CountingConnector connector,
            IngestDocumentServiceTest.FakeSourceDocumentRepository sourceDocuments,
            IngestDocumentServiceTest.FakeKnowledgeDocumentRepository knowledgeDocuments) {
        return new IngestDocumentService(connector, new PdfContentExtractor(),
                new IngestDocumentServiceTest.FakeEmbeddingGenerator(), sourceDocuments,
                new IngestDocumentServiceTest.FakeIngestionRunRepository(), knowledgeDocuments,
                new IngestDocumentServiceTest.FakeChunkRepository(),
                new HostAllowlistSourcePolicy(new IngestionProperties(List.of("www.ready.gov"), null, null)));
    }

    private static final class CountingConnector implements SourceConnector {
        int fetchCount;

        @Override
        public RawContent fetch(String sourceUri) {
            fetchCount++;
            throw new AssertionError("fetch must not be called for a denied URI");
        }
    }
}

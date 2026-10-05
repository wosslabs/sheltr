package com.sheltr.ingestion.application;

import com.sheltr.ingestion.config.HostAllowlistSourcePolicy;
import com.sheltr.ingestion.config.IngestionProperties;
import com.sheltr.ingestion.domain.IngestionRun;
import com.sheltr.ingestion.domain.IngestionStatus;
import com.sheltr.ingestion.domain.RawContent;
import com.sheltr.ingestion.domain.RunStatus;
import com.sheltr.ingestion.domain.SourceConnector;
import com.sheltr.ingestion.domain.SourceMetadata;
import com.sheltr.ingestion.domain.SourcePolicy;
import com.sheltr.ingestion.domain.SourceType;
import com.sheltr.ingestion.infrastructure.PdfContentExtractor;
import com.sheltr.knowledge.domain.Checksum;
import com.sheltr.knowledge.domain.DisasterType;
import com.sheltr.knowledge.domain.KnowledgeDocument;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IngestDocumentServiceSourceTypeTest {

    private static final String HTML_URI = "https://www.gov.uk/guidance/prepare-for-a-flood";
    private static final String PDF_URI = "https://www.ready.gov/guide.pdf";
    private static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private static final SourcePolicy POLICY = new HostAllowlistSourcePolicy(
            new IngestionProperties(List.of("www.gov.uk", "www.ready.gov"), null, null));

    private static final String PAGE_TEXT = "Move valuables upstairs and keep emergency contacts to hand.";

    @Test
    void htmlEntryUsesTheWebConnectorAndSavesHtmlAsTheSourceType() {
        CountingConnector pdf = new CountingConnector(new byte[0], "pdf-sum");
        CountingConnector html = new CountingConnector(PAGE_TEXT.getBytes(StandardCharsets.UTF_8), "html-sum");
        Fixture fixture = new Fixture(pdf, html, POLICY);

        IngestionRun run = fixture.service.ingest(HTML_URI, SourceType.HTML, metadata("GB"), null);

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(html.fetchCount).isEqualTo(1);
        assertThat(pdf.fetchCount).isZero();
        assertThat(fixture.knowledgeDocuments.saved).hasSize(1);
        KnowledgeDocument saved = fixture.knowledgeDocuments.saved.get(0);
        assertThat(saved.sourceType()).isEqualTo("HTML");
        assertThat(saved.checksum()).isEqualTo(new Checksum("html-sum"));
        assertThat(fixture.chunks.inserted).isNotEmpty();
    }

    @Test
    void pdfEntryUsesThePdfConnector() throws IOException {
        CountingConnector pdf = new CountingConnector(IngestDocumentServiceTest.buildTestPdf(), "pdf-sum");
        CountingConnector html = new CountingConnector(new byte[0], "html-sum");
        Fixture fixture = new Fixture(pdf, html, POLICY);

        IngestionRun run = fixture.service.ingest(PDF_URI, SourceType.PDF, metadata(null), null);

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(pdf.fetchCount).isEqualTo(1);
        assertThat(html.fetchCount).isZero();
        assertThat(fixture.knowledgeDocuments.saved.get(0).sourceType()).isEqualTo("PDF");
    }

    @Test
    void savedDocumentCarriesTheCountryOrNullForGlobal() {
        Fixture withCountry = new Fixture(new CountingConnector(new byte[0], "x"),
                new CountingConnector(PAGE_TEXT.getBytes(StandardCharsets.UTF_8), "x"), POLICY);
        withCountry.service.ingest(HTML_URI, SourceType.HTML, metadata("GB"), null);
        assertThat(withCountry.knowledgeDocuments.saved.get(0).country()).contains("GB");

        Fixture global = new Fixture(new CountingConnector(new byte[0], "x"),
                new CountingConnector(PAGE_TEXT.getBytes(StandardCharsets.UTF_8), "x"), POLICY);
        global.service.ingest(HTML_URI, SourceType.HTML, metadata(null), null);
        assertThat(global.knowledgeDocuments.saved.get(0).country()).isEmpty();
    }

    @Test
    void htmlChecksumMismatchFailsWithTheChecksumMessage() {
        CountingConnector html = new CountingConnector(PAGE_TEXT.getBytes(StandardCharsets.UTF_8), "html-sum");
        Fixture fixture = new Fixture(new CountingConnector(new byte[0], "x"), html, POLICY);

        IngestionRun run = fixture.service.ingest(HTML_URI, SourceType.HTML, metadata("GB"), "approved000");

        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.errorMessage().orElseThrow()).contains("Checksum mismatch").contains("approved000")
                .contains("html-sum");
        assertThat(fixture.knowledgeDocuments.saved).isEmpty();
    }

    @Test
    void emptyHtmlPageFailsTheRunWithNoExtractableTextAndRecordsNoChecksum() {
        CountingConnector html = new CountingConnector(new byte[0], EMPTY_SHA256);
        Fixture fixture = new Fixture(new CountingConnector(new byte[0], "x"), html, POLICY);

        IngestionRun run = fixture.service.ingest(HTML_URI, SourceType.HTML, metadata("GB"), null);

        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.errorMessage().orElseThrow()).contains("No extractable text");
        assertThat(fixture.knowledgeDocuments.saved).isEmpty();
        assertThat(fixture.chunks.inserted).isEmpty();
        assertThat(fixture.sourceDocuments.lastSaved().status()).isEqualTo(IngestionStatus.FAILED);
        assertThat(fixture.sourceDocuments.saved).allMatch(d -> d.checksum().isEmpty());
    }

    @Test
    void deniedHtmlUriFailsWithoutFetching() {
        CountingConnector html = new CountingConnector(PAGE_TEXT.getBytes(StandardCharsets.UTF_8), "html-sum");
        Fixture fixture = new Fixture(new CountingConnector(new byte[0], "x"), html, POLICY);

        IngestionRun run = fixture.service.ingest("https://evil.example/page", SourceType.HTML, metadata("GB"), null);

        assertThat(html.fetchCount).isZero();
        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.errorMessage().orElseThrow()).isEqualTo("Source host not allowed: evil.example");
        assertThat(fixture.knowledgeDocuments.saved).isEmpty();
    }

    private static SourceMetadata metadata(String country) {
        return new SourceMetadata("Flood guidance", "Environment Agency", Set.of(DisasterType.FLOOD),
                LocalDate.of(2024, 1, 10), "Open Government Licence v3.0", country);
    }

    private static final class Fixture {
        final IngestDocumentServiceTest.FakeSourceDocumentRepository sourceDocuments =
                new IngestDocumentServiceTest.FakeSourceDocumentRepository();
        final IngestDocumentServiceTest.FakeKnowledgeDocumentRepository knowledgeDocuments =
                new IngestDocumentServiceTest.FakeKnowledgeDocumentRepository();
        final IngestDocumentServiceTest.FakeChunkRepository chunks =
                new IngestDocumentServiceTest.FakeChunkRepository();
        final IngestDocumentService service;

        Fixture(SourceConnector pdf, SourceConnector html, SourcePolicy policy) {
            this.service = new IngestDocumentService(Map.of(SourceType.PDF, pdf, SourceType.HTML, html),
                    new PdfContentExtractor(), new IngestDocumentServiceTest.FakeEmbeddingGenerator(),
                    sourceDocuments, new IngestDocumentServiceTest.FakeIngestionRunRepository(),
                    knowledgeDocuments, chunks, policy);
        }
    }

    private static final class CountingConnector implements SourceConnector {
        final byte[] bytes;
        final String checksum;
        int fetchCount;

        CountingConnector(byte[] bytes, String checksum) {
            this.bytes = bytes;
            this.checksum = checksum;
        }

        @Override
        public RawContent fetch(String sourceUri) {
            fetchCount++;
            return new RawContent(bytes, new Checksum(checksum));
        }
    }
}

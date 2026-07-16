package com.sheltr.ingestion.domain;

import com.sheltr.knowledge.domain.Checksum;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import com.sheltr.knowledge.domain.DocumentId;

import static org.assertj.core.api.Assertions.assertThat;

class SourceDocumentTest {

    @Test
    void startsInDiscoveredStatus() {
        SourceDocument document = new SourceDocument(
                new DocumentId(UUID.randomUUID()), "https://example.org/doc.pdf", SourceType.PDF);

        assertThat(document.status()).isEqualTo(IngestionStatus.DISCOVERED);
        assertThat(document.checksum()).isEmpty();
    }

    @Test
    void transitionsThroughStatusesInOrder() {
        SourceDocument document = new SourceDocument(
                new DocumentId(UUID.randomUUID()), "https://example.org/doc.pdf", SourceType.PDF);

        document.markDownloaded(new Checksum("abc123"));
        assertThat(document.status()).isEqualTo(IngestionStatus.DOWNLOADED);
        assertThat(document.checksum()).contains(new Checksum("abc123"));

        document.markExtracted();
        assertThat(document.status()).isEqualTo(IngestionStatus.EXTRACTED);

        document.markCleaned();
        assertThat(document.status()).isEqualTo(IngestionStatus.CLEANED);

        document.markChunked();
        assertThat(document.status()).isEqualTo(IngestionStatus.CHUNKED);

        document.markIndexed();
        assertThat(document.status()).isEqualTo(IngestionStatus.INDEXED);
    }

    @Test
    void markFailedSetsFailedStatusFromAnyState() {
        SourceDocument document = new SourceDocument(
                new DocumentId(UUID.randomUUID()), "https://example.org/doc.pdf", SourceType.PDF);

        document.markFailed();

        assertThat(document.status()).isEqualTo(IngestionStatus.FAILED);
    }
}

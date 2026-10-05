package com.sheltr.knowledge.domain;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeDocumentTest {

    @Test
    void legacyConstructorDefaultsTheSourceTypeToPdf() {
        KnowledgeDocument doc = new KnowledgeDocument(
                new DocumentId(UUID.randomUUID()), "Title", "Agency", "https://example.org/a.pdf",
                Set.of(DisasterType.FLOOD), LocalDate.of(2024, 1, 1), new DocumentVersion(1),
                new Checksum("abc123"), null, DocumentStatus.ACTIVE, "OGL", "GB");

        assertThat(doc.sourceType()).isEqualTo("PDF");
    }

    @Test
    void sourceTypeConstructorRejectsNull() {
        assertThatThrownBy(() -> new KnowledgeDocument(
                new DocumentId(UUID.randomUUID()), "Title", "Agency", "https://example.org/a",
                Set.of(DisasterType.FLOOD), LocalDate.of(2024, 1, 1), new DocumentVersion(1),
                new Checksum("abc123"), null, DocumentStatus.ACTIVE, "OGL", "GB", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sourceType is required");
    }

    @Test
    void createsActiveDocument() {
        KnowledgeDocument doc = new KnowledgeDocument(
                new DocumentId(UUID.randomUUID()), "Water Safety During Floods", "American Red Cross",
                "https://redcross.org/water-safety", java.util.Set.of(DisasterType.FLOOD),
                LocalDate.of(2024, 1, 1), new DocumentVersion(1), new Checksum("abc123"),
                null, DocumentStatus.ACTIVE);

        assertThat(doc.status()).isEqualTo(DocumentStatus.ACTIVE);
        assertThat(doc.supersedes()).isEmpty();
    }
}

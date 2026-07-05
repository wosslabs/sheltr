package com.sheltr.knowledge.domain;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeDocumentTest {

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

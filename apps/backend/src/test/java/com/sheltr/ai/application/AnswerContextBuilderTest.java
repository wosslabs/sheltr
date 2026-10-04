package com.sheltr.ai.application;

import com.sheltr.ai.domain.Citation;
import com.sheltr.ai.domain.ConfidenceBands;
import com.sheltr.ai.domain.ConfidenceLevel;
import com.sheltr.ai.domain.RelevanceVerdict;
import com.sheltr.knowledge.domain.*;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.SearchQuery;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerContextBuilderTest {

    @Test
    void buildsPromptCitationsAndConfidenceFromRetrievedChunks() {
        DocumentId documentId = new DocumentId(UUID.randomUUID());
        KnowledgeDocument document = new KnowledgeDocument(
                documentId, "Flood Safety and Water Purification", "American Red Cross",
                "https://www.redcross.org/floods", Set.of(DisasterType.FLOOD),
                LocalDate.of(2023, 1, 1), new DocumentVersion(1), new Checksum("seed-fl-1"),
                null, DocumentStatus.ACTIVE);
        KnowledgeDocumentRepository documents = new KnowledgeDocumentRepository() {
            @Override
            public Optional<KnowledgeDocument> findById(DocumentId id) {
                return id.equals(documentId) ? Optional.of(document) : Optional.empty();
            }

            @Override
            public void save(KnowledgeDocument ignored) {
                throw new UnsupportedOperationException("not needed by this test");
            }
        };

        ChunkId chunkId = new ChunkId(UUID.randomUUID());
        RankedResult result = new RankedResult(chunkId, documentId,
                "If tap water is contaminated after a flood, boil it at a rolling boil for one full minute.", 0.88);

        AnswerContext context = new AnswerContextBuilder(documents, ConfidenceBands.DEFAULT)
                .buildContext(new SearchQuery("Is it safe to drink flood water?"), List.of(result),
                        new RelevanceVerdict(true, 0.88));

        assertThat(context.prompt()).contains("Is it safe to drink flood water?");
        assertThat(context.prompt()).contains("boil it at a rolling boil for one full minute");
        assertThat(context.prompt()).contains("You are a disaster-response assistant");
        assertThat(context.citations()).containsExactly(
                new Citation(documentId, chunkId, "Flood Safety and Water Purification",
                        "https://www.redcross.org/floods", null));
        assertThat(context.confidence().score()).isEqualTo(0.88);
        assertThat(context.confidence().level()).isEqualTo(ConfidenceLevel.MEDIUM);
    }

    @Test
    void skipsCitationsWhenDocumentIsMissing() {
        DocumentId missingId = new DocumentId(UUID.randomUUID());
        DocumentId presentId = new DocumentId(UUID.randomUUID());
        KnowledgeDocument document = new KnowledgeDocument(
                presentId, "Present", "FEMA", "https://www.fema.gov/x", Set.of(DisasterType.FLOOD),
                LocalDate.of(2023, 1, 1), new DocumentVersion(1), new Checksum("c"),
                null, DocumentStatus.ACTIVE);
        KnowledgeDocumentRepository documents = new KnowledgeDocumentRepository() {
            @Override
            public Optional<KnowledgeDocument> findById(DocumentId id) {
                return id.equals(presentId) ? Optional.of(document) : Optional.empty();
            }

            @Override
            public void save(KnowledgeDocument ignored) {
                throw new UnsupportedOperationException("not needed by this test");
            }
        };

        RankedResult missing = new RankedResult(new ChunkId(UUID.randomUUID()), missingId, "gone", 0.91);
        RankedResult present = new RankedResult(new ChunkId(UUID.randomUUID()), presentId, "here", 0.80);

        AnswerContext context = new AnswerContextBuilder(documents, ConfidenceBands.DEFAULT)
                .buildContext(new SearchQuery("flood water"), List.of(missing, present),
                        new RelevanceVerdict(true, 0.91));

        assertThat(context.citations()).hasSize(1);
        assertThat(context.citations().get(0).sourceTitle()).isEqualTo("Present");
        assertThat(context.confidence().score()).isEqualTo(0.91);
    }

    private record Fixture(KnowledgeDocumentRepository documents, RankedResult result) {
    }

    private static Fixture floodFixture() {
        DocumentId documentId = new DocumentId(UUID.randomUUID());
        KnowledgeDocument document = new KnowledgeDocument(
                documentId, "Flood Safety and Water Purification", "American Red Cross",
                "https://www.redcross.org/floods", Set.of(DisasterType.FLOOD),
                LocalDate.of(2023, 1, 1), new DocumentVersion(1), new Checksum("seed-fl-1"),
                null, DocumentStatus.ACTIVE);
        KnowledgeDocumentRepository documents = new KnowledgeDocumentRepository() {
            @Override
            public Optional<KnowledgeDocument> findById(DocumentId id) {
                return id.equals(documentId) ? Optional.of(document) : Optional.empty();
            }

            @Override
            public void save(KnowledgeDocument ignored) {
                throw new UnsupportedOperationException("not needed by this test");
            }
        };
        RankedResult result = new RankedResult(new ChunkId(UUID.randomUUID()), documentId,
                "If tap water is contaminated after a flood, boil it at a rolling boil for one full minute.", 0.88);
        return new Fixture(documents, result);
    }

    @Test
    void numbersThePassagesAndAsksForCitationMarkers() {
        Fixture fixture = floodFixture();

        AnswerContext context = new AnswerContextBuilder(fixture.documents(), ConfidenceBands.DEFAULT)
                .buildContext(new SearchQuery("Is it safe to drink flood water?"), List.of(fixture.result()),
                        new RelevanceVerdict(true, 0.88));

        assertThat(context.prompt()).contains("[1] If tap water is contaminated after a flood");
        assertThat(context.prompt()).contains("like [1]");
        assertThat(context.prompt()).doesNotContain("Official alerts report");
    }

    @Test
    void statesActiveHazardsAsReportedContextWithoutAskingTheModelToConfirmThem() {
        Fixture fixture = floodFixture();

        AnswerContext context = new AnswerContextBuilder(fixture.documents(), ConfidenceBands.DEFAULT)
                .buildContext(new SearchQuery("What should I do?", java.util.Set.of(DisasterType.FLOOD)),
                        List.of(fixture.result()), new RelevanceVerdict(true, 0.88));

        assertThat(context.prompt()).contains("Official alerts report these hazards near the user: FLOOD");
        assertThat(context.prompt()).contains("Do not state whether any hazard is or is not occurring");
    }
}

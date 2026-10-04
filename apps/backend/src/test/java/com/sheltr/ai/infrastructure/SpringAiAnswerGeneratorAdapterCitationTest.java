package com.sheltr.ai.infrastructure;

import com.sheltr.ai.application.AnswerContextBuilder;
import com.sheltr.ai.domain.*;
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

class SpringAiAnswerGeneratorAdapterCitationTest {

    private static final SearchQuery QUERY = new SearchQuery("Is it safe to drink flood water?");
    private static final RelevanceVerdict VERDICT = new RelevanceVerdict(true, 0.88);

    private final DocumentId documentId = new DocumentId(UUID.randomUUID());
    private final RankedResult boil = passage("Boil water for one minute.");
    private final RankedResult bleach = passage("Disinfect with unscented bleach.");

    @Test
    void citesOnlyThePassagesTheAnswerUsed() {
        AnswerOutcome outcome = adapter("Boil it for one minute [1].").generate(QUERY, List.of(boil, bleach), VERDICT);

        Message message = ((AnswerGenerated) outcome).message();
        assertThat(message.citations()).extracting(Citation::chunkId).containsExactly(boil.chunkId());
        assertThat(message.retrievedChunkIds()).containsExactly(boil.chunkId(), bleach.chunkId());
    }

    @Test
    void refusesAnAnswerThatCitesNothing() {
        assertThat(adapter("Boil it for one minute.").generate(QUERY, List.of(boil, bleach), VERDICT))
                .isInstanceOf(NoAnswerAvailable.class);
    }

    @Test
    void refusesAnAnswerThatOnlyCitesAPassageThatDoesNotExist() {
        assertThat(adapter("Boil it for one minute [7].").generate(QUERY, List.of(boil, bleach), VERDICT))
                .isInstanceOf(NoAnswerAvailable.class);
    }

    @Test
    void reportsAFailedGeneration() {
        SpringAiAnswerGeneratorAdapter adapter = new SpringAiAnswerGeneratorAdapter(prompt -> {
            throw new IllegalStateException("model unavailable");
        }, new AnswerContextBuilder(documents(), ConfidenceBands.DEFAULT));

        assertThat(adapter.generate(QUERY, List.of(boil), VERDICT)).isInstanceOf(AnswerGenerationFailed.class);
    }

    private SpringAiAnswerGeneratorAdapter adapter(String reply) {
        return new SpringAiAnswerGeneratorAdapter(prompt -> reply,
                new AnswerContextBuilder(documents(), ConfidenceBands.DEFAULT));
    }

    private RankedResult passage(String content) {
        return new RankedResult(new ChunkId(UUID.randomUUID()), documentId, content, 0.88);
    }

    private KnowledgeDocumentRepository documents() {
        KnowledgeDocument document = new KnowledgeDocument(documentId, "Flood Safety", "Example Agency",
                "https://example.org/flood.pdf", Set.of(DisasterType.FLOOD), LocalDate.of(2021, 3, 15),
                new DocumentVersion(1), new Checksum("abc"), null, DocumentStatus.ACTIVE);
        return new KnowledgeDocumentRepository() {
            @Override
            public Optional<KnowledgeDocument> findById(DocumentId id) {
                return id.equals(documentId) ? Optional.of(document) : Optional.empty();
            }

            @Override
            public void save(KnowledgeDocument ignored) {
                throw new UnsupportedOperationException("not needed by this test");
            }
        };
    }
}

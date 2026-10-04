package com.sheltr.eval.application;

import com.sheltr.ai.application.AnswerPipeline;
import com.sheltr.ai.domain.*;
import com.sheltr.eval.domain.EvalKind;
import com.sheltr.eval.domain.EvalQuestion;
import com.sheltr.eval.domain.EvalResult;
import com.sheltr.knowledge.domain.*;
import com.sheltr.search.domain.NoOpReranker;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.Retriever;
import com.sheltr.search.domain.SearchQuery;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EvalRunServiceTest {

    private static final String URI = "https://example.org/flood.pdf";
    private final DocumentId documentId = new DocumentId(UUID.randomUUID());
    private final RankedResult boil = new RankedResult(new ChunkId(UUID.randomUUID()), documentId,
            "Boil water for one minute.", 0.9);
    private final RankedResult bleach = new RankedResult(new ChunkId(UUID.randomUUID()), documentId,
            "Use unscented bleach.", 0.9);
    private final EvalQuestion question = new EvalQuestion("flood-001", "How long should I boil water?",
            EvalKind.ANSWERABLE, Set.of(DisasterType.FLOOD), List.of(), List.of(), "tune");

    @Test
    void recordsPassagesAnswerConfidenceAndCitedPassages() {
        List<SearchQuery> queries = new ArrayList<>();
        Retriever retriever = (query, topK) -> {
            queries.add(query);
            return List.of(boil, bleach);
        };
        AnswerGenerator generator = (query, results, verdict) -> new AnswerGenerated(new Message(
                new MessageId(UUID.randomUUID()), MessageRole.ASSISTANT, new MessageContent("One minute [1]."),
                List.of(new Citation(documentId, boil.chunkId(), "Flood", URI, null)),
                AnswerConfidence.fromSimilarity(0.9), List.of(boil.chunkId(), bleach.chunkId())));

        EvalResult result = service(retriever, generator).run(question);

        assertThat(queries.get(0).activeHazards()).containsExactly(DisasterType.FLOOD);
        assertThat(result.questionId()).isEqualTo("flood-001");
        assertThat(result.refused()).isFalse();
        assertThat(result.answer()).isEqualTo("One minute [1].");
        assertThat(result.confidence()).isEqualTo(0.9);
        assertThat(result.passages()).extracting("sourceUri").containsExactly(URI, URI);
        assertThat(result.cited()).extracting("content").containsExactly("Boil water for one minute.");
        assertThat(result.error()).isNull();
    }

    @Test
    void recordsARefusal() {
        RankedResult weak = new RankedResult(new ChunkId(UUID.randomUUID()), documentId, "unrelated", 0.2);

        EvalResult result = service((query, topK) -> List.of(weak), (query, results, verdict) -> {
            throw new AssertionError("generator must not be called");
        }).run(question);

        assertThat(result.refused()).isTrue();
        assertThat(result.answer()).isNull();
        assertThat(result.confidence()).isEqualTo(0.2);
        assertThat(result.passages()).hasSize(1);
    }

    @Test
    void recordsAFailedGenerationAsRefusedWithTheReason() {
        EvalResult result = service((query, topK) -> List.of(boil),
                (query, results, verdict) -> new AnswerGenerationFailed("model unavailable")).run(question);

        assertThat(result.refused()).isTrue();
        assertThat(result.error()).isEqualTo("model unavailable");
    }

    @Test
    void recordsAPipelineExceptionInsteadOfStoppingTheRun() {
        EvalResult result = service((query, topK) -> {
            throw new IllegalStateException("database down");
        }, (query, results, verdict) -> new AnswerGenerationFailed("unused")).run(question);

        assertThat(result.refused()).isTrue();
        assertThat(result.error()).isEqualTo("database down");
        assertThat(result.passages()).isEmpty();
    }

    private EvalRunService service(Retriever retriever, AnswerGenerator generator) {
        KnowledgeDocument document = new KnowledgeDocument(documentId, "Flood", "Agency", URI,
                Set.of(DisasterType.FLOOD), LocalDate.of(2021, 3, 15), new DocumentVersion(1),
                new Checksum("abc"), null, DocumentStatus.ACTIVE);
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
        return new EvalRunService(new AnswerPipeline(retriever, new NoOpReranker(),
                new ThresholdRelevanceJudge(0.75), generator), documents);
    }
}

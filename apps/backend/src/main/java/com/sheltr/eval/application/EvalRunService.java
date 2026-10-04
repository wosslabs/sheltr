package com.sheltr.eval.application;

import com.sheltr.ai.application.AnswerPipeline;
import com.sheltr.ai.domain.AnswerGenerated;
import com.sheltr.ai.domain.AnswerGenerationFailed;
import com.sheltr.ai.domain.Citation;
import com.sheltr.ai.domain.NoAnswerAvailable;
import com.sheltr.eval.domain.EvalQuestion;
import com.sheltr.eval.domain.EvalResult;
import com.sheltr.eval.domain.RetrievedPassage;
import com.sheltr.knowledge.domain.ChunkId;
import com.sheltr.knowledge.domain.DocumentId;
import com.sheltr.knowledge.domain.KnowledgeDocument;
import com.sheltr.knowledge.domain.KnowledgeDocumentRepository;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.SearchQuery;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Runs one eval question through the same pipeline that serves users and records what happened. */
public class EvalRunService {

    private final AnswerPipeline pipeline;
    private final KnowledgeDocumentRepository documents;
    private final Map<DocumentId, String> sourceUris = new HashMap<>();

    public EvalRunService(AnswerPipeline pipeline, KnowledgeDocumentRepository documents) {
        this.pipeline = pipeline;
        this.documents = documents;
    }

    public EvalResult run(EvalQuestion question) {
        long start = System.nanoTime();
        try {
            AnswerPipeline.PipelineResult result =
                    pipeline.answer(new SearchQuery(question.question(), question.activeHazards()));
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            List<RetrievedPassage> passages = result.passages().stream().map(this::toPassage).toList();
            double confidence = result.verdict().probability();
            return switch (result.outcome()) {
                case AnswerGenerated generated -> {
                    Set<ChunkId> citedIds = generated.message().citations().stream()
                            .map(Citation::chunkId).collect(Collectors.toSet());
                    List<RetrievedPassage> cited = result.passages().stream()
                            .filter(passage -> citedIds.contains(passage.chunkId()))
                            .map(this::toPassage).toList();
                    yield new EvalResult(question.id(), passages, false,
                            generated.message().content().value(), confidence, cited, latencyMs, null);
                }
                case NoAnswerAvailable ignored -> new EvalResult(question.id(), passages, true, null,
                        confidence, List.of(), latencyMs, null);
                case AnswerGenerationFailed failed -> new EvalResult(question.id(), passages, true, null,
                        confidence, List.of(), latencyMs, failed.reason());
            };
        } catch (RuntimeException e) {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            return new EvalResult(question.id(), List.of(), true, null, null, List.of(), latencyMs,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private RetrievedPassage toPassage(RankedResult result) {
        String sourceUri = sourceUris.computeIfAbsent(result.documentId(),
                id -> documents.findById(id).map(KnowledgeDocument::sourceUri).orElse(""));
        return new RetrievedPassage(sourceUri, result.content());
    }
}

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Runs one eval question through the same pipeline that serves users and records what happened. */
public class EvalRunService {

    private static final Logger log = LoggerFactory.getLogger(EvalRunService.class);

    private final AnswerPipeline pipeline;
    private final KnowledgeDocumentRepository documents;

    public EvalRunService(AnswerPipeline pipeline, KnowledgeDocumentRepository documents) {
        this.pipeline = pipeline;
        this.documents = documents;
    }

    public EvalResult run(EvalQuestion question) {
        long start = System.nanoTime();
        AnswerPipeline.PipelineResult result;
        try {
            result = pipeline.answer(new SearchQuery(question.question(), question.activeHazards()));
        } catch (RuntimeException e) {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            return new EvalResult(question.id(), List.of(), true, null, null, List.of(), latencyMs,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        Map<DocumentId, String> sourceUris = new HashMap<>();
        List<RetrievedPassage> passages = result.passages().stream()
                .map(passage -> toPassage(passage, sourceUris)).toList();
        double confidence = result.verdict().probability();
        return switch (result.outcome()) {
            case AnswerGenerated generated -> {
                Set<ChunkId> citedIds = generated.message().citations().stream()
                        .map(Citation::chunkId).collect(Collectors.toSet());
                List<RetrievedPassage> cited = result.passages().stream()
                        .filter(passage -> citedIds.contains(passage.chunkId()))
                        .map(passage -> toPassage(passage, sourceUris)).toList();
                yield new EvalResult(question.id(), passages, false,
                        generated.message().content().value(), confidence, cited, latencyMs, null);
            }
            case NoAnswerAvailable ignored -> new EvalResult(question.id(), passages, true, null,
                    confidence, List.of(), latencyMs, null);
            case AnswerGenerationFailed failed -> new EvalResult(question.id(), passages, true, null,
                    confidence, List.of(), latencyMs, failed.reason());
        };
    }

    private RetrievedPassage toPassage(RankedResult result, Map<DocumentId, String> sourceUris) {
        String sourceUri = sourceUris.computeIfAbsent(result.documentId(), this::lookupSourceUri);
        return new RetrievedPassage(sourceUri, result.content());
    }

    /** A lookup failure only loses the source URI; it must not change the outcome of the answer. */
    private String lookupSourceUri(DocumentId documentId) {
        try {
            return documents.findById(documentId).map(KnowledgeDocument::sourceUri).orElse("");
        } catch (RuntimeException e) {
            log.warn("Source URI lookup failed for document {}; recording an empty URI", documentId, e);
            return "";
        }
    }
}

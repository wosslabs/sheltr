package com.sheltr.ai.application;

import com.sheltr.ai.domain.AnswerConfidence;
import com.sheltr.ai.domain.Citation;
import com.sheltr.ai.domain.ConfidenceBands;
import com.sheltr.ai.domain.RelevanceVerdict;
import com.sheltr.knowledge.domain.KnowledgeDocument;
import com.sheltr.knowledge.domain.KnowledgeDocumentRepository;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.SearchQuery;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class AnswerContextBuilder {

    private final KnowledgeDocumentRepository documentRepository;
    private final ConfidenceBands confidenceBands;

    public AnswerContextBuilder(KnowledgeDocumentRepository documentRepository, ConfidenceBands confidenceBands) {
        this.documentRepository = documentRepository;
        this.confidenceBands = confidenceBands;
    }

    public AnswerContext buildContext(SearchQuery query, List<RankedResult> results, RelevanceVerdict verdict) {
        StringBuilder passages = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            passages.append('[').append(i + 1).append("] ").append(results.get(i).content()).append('\n');
        }

        String hazards = "";
        if (!query.activeHazards().isEmpty()) {
            hazards = "Official alerts report these hazards near the user: "
                    + query.activeHazards().stream().map(Enum::name).sorted().collect(Collectors.joining(", "))
                    + ". Do not state whether any hazard is or is not occurring; only give guidance.\n\n";
        }

        String prompt = """
                You are a disaster-response assistant. Answer the question using ONLY the numbered \
                passages below. Every sentence of your answer must end with the bracketed number of \
                the passage it came from, like [1]. For example: "Boil tap water for one full minute \
                before drinking it. [1]" An answer without bracketed numbers will be rejected. If the \
                passages do not contain enough information to answer, say so clearly rather than \
                guessing, and still end that sentence with the number of the closest passage.

                %sPassages:
                %s
                Question: %s
                """.formatted(hazards, passages, query.text());

        List<Citation> citations = new ArrayList<>();
        for (RankedResult result : results) {
            KnowledgeDocument document = documentRepository.findById(result.documentId()).orElse(null);
            if (document != null) {
                citations.add(new Citation(result.documentId(), result.chunkId(),
                        document.title(), document.sourceUri(), null));
            }
        }

        return new AnswerContext(prompt, citations, AnswerConfidence.of(verdict.probability(), confidenceBands));
    }
}

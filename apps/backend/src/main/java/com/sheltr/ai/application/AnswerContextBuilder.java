package com.sheltr.ai.application;

import com.sheltr.ai.domain.AnswerConfidence;
import com.sheltr.ai.domain.Citation;
import com.sheltr.knowledge.domain.KnowledgeDocument;
import com.sheltr.knowledge.domain.KnowledgeDocumentRepository;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.SearchQuery;

import java.util.ArrayList;
import java.util.List;

public class AnswerContextBuilder {

    private final KnowledgeDocumentRepository documentRepository;

    public AnswerContextBuilder(KnowledgeDocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    public AnswerContext buildContext(SearchQuery query, List<RankedResult> results) {
        StringBuilder context = new StringBuilder();
        for (RankedResult result : results) {
            context.append("- ").append(result.content()).append('\n');
        }
        String prompt = """
                You are a disaster-response assistant. Answer the citizen's question using ONLY the \
                context below. If the context does not contain enough information to answer, say so \
                clearly rather than guessing.

                Context:
                %s
                Question: %s
                """.formatted(context, query.text());

        List<Citation> citations = new ArrayList<>();
        for (RankedResult result : results) {
            KnowledgeDocument document = documentRepository.findById(result.documentId()).orElse(null);
            if (document != null) {
                citations.add(new Citation(result.documentId(), result.chunkId(),
                        document.title(), document.sourceUri(), null));
            }
        }

        return new AnswerContext(prompt, citations, AnswerConfidence.fromSimilarity(results.get(0).score()));
    }
}

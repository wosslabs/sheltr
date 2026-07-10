package com.sheltr.ai.infrastructure;

import com.sheltr.ai.domain.*;
import com.sheltr.knowledge.domain.KnowledgeDocument;
import com.sheltr.knowledge.domain.KnowledgeDocumentRepository;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.SearchQuery;
import org.springframework.ai.chat.model.ChatModel;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class GeminiAnswerGeneratorAdapter implements AnswerGenerator {

    private final ChatModel chatModel;
    private final KnowledgeDocumentRepository documentRepository;

    public GeminiAnswerGeneratorAdapter(ChatModel chatModel, KnowledgeDocumentRepository documentRepository) {
        this.chatModel = chatModel;
        this.documentRepository = documentRepository;
    }

    @Override
    public AnswerOutcome generate(SearchQuery query, List<RankedResult> results) {
        String prompt = buildPrompt(query, results);
        String answerText;
        try {
            answerText = chatModel.call(prompt);
        } catch (Exception e) {
            return new AnswerGenerationFailed("Gemini call failed: " + e.getMessage());
        }

        List<Citation> citations = new ArrayList<>();
        for (RankedResult result : results) {
            KnowledgeDocument document = documentRepository.findById(result.documentId()).orElse(null);
            if (document != null) {
                citations.add(new Citation(result.documentId(), result.chunkId(),
                        document.title(), document.sourceUri(), null));
            }
        }

        double topScore = results.get(0).score();
        Message message = new Message(
                new MessageId(UUID.randomUUID()), MessageRole.ASSISTANT, new MessageContent(answerText),
                citations, AnswerConfidence.fromSimilarity(topScore),
                results.stream().map(RankedResult::chunkId).toList());

        return new AnswerGenerated(message);
    }

    private static String buildPrompt(SearchQuery query, List<RankedResult> results) {
        StringBuilder context = new StringBuilder();
        for (RankedResult result : results) {
            context.append("- ").append(result.content()).append('\n');
        }
        return """
                You are a disaster-response assistant. Answer the citizen's question using ONLY the \
                context below. If the context does not contain enough information to answer, say so \
                clearly rather than guessing.

                Context:
                %s
                Question: %s
                """.formatted(context, query.text());
    }
}

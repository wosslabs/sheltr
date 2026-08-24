package com.sheltr.ai.infrastructure;

import com.google.genai.Client;
import com.sheltr.ai.application.AnswerContextBuilder;
import com.sheltr.ai.domain.*;
import com.sheltr.knowledge.domain.*;
import com.sheltr.search.domain.RankedResult;
import com.sheltr.search.domain.SearchQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class SpringAiAnswerGeneratorAdapterTest {

    @Test
    void generatesCitationBackedAnswerFromRetrievedChunks() {
        Client genAiClient = Client.builder().apiKey(System.getenv("GEMINI_API_KEY")).build();
        ChatModel chatModel = GoogleGenAiChatModel.builder()
                .genAiClient(genAiClient)
                .options(GoogleGenAiChatOptions.builder().model("gemini-2.5-flash").build())
                .build();

        DocumentId documentId = new DocumentId(UUID.randomUUID());
        KnowledgeDocument document = new KnowledgeDocument(
                documentId, "Flood Safety and Water Purification", "American Red Cross",
                "https://www.redcross.org/floods", Set.of(DisasterType.FLOOD),
                LocalDate.of(2023, 1, 1), new DocumentVersion(1), new Checksum("seed-fl-1"),
                null, DocumentStatus.ACTIVE);
        KnowledgeDocumentRepository documentRepository = new KnowledgeDocumentRepository() {
            @Override
            public Optional<KnowledgeDocument> findById(DocumentId id) {
                return id.equals(documentId) ? Optional.of(document) : Optional.empty();
            }

            @Override
            public void save(KnowledgeDocument document) {
                throw new UnsupportedOperationException("not needed by this test");
            }
        };

        SpringAiAnswerGeneratorAdapter adapter = new SpringAiAnswerGeneratorAdapter(
                chatModel, new AnswerContextBuilder(documentRepository));

        ChunkId chunkId = new ChunkId(UUID.randomUUID());
        RankedResult result = new RankedResult(chunkId, documentId,
                "If tap water is contaminated after a flood, boil it at a rolling boil for one full minute.", 0.88);

        AnswerOutcome outcome = adapter.generate(new SearchQuery("Is it safe to drink flood water?"), List.of(result));

        assertThat(outcome).isInstanceOf(AnswerGenerated.class);
        Message message = ((AnswerGenerated) outcome).message();
        assertThat(message.role()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(message.content().value()).isNotBlank();
        assertThat(message.citations()).hasSize(1);
        assertThat(message.citations().get(0).sourceTitle()).isEqualTo("Flood Safety and Water Purification");
        assertThat(message.confidence().level()).isEqualTo(ConfidenceLevel.MEDIUM);
        assertThat(message.retrievedChunkIds()).containsExactly(chunkId);
    }
}

package com.sheltr.interfaces;

import com.sheltr.ai.domain.ConversationRepository;
import com.sheltr.ai.interfaces.ConversationController;
import com.sheltr.ingestion.domain.IngestionRunRepository;
import com.sheltr.ingestion.application.IngestDocumentService;
import com.sheltr.ingestion.interfaces.IngestionController;
import com.sheltr.knowledge.domain.KnowledgeDocumentRepository;
import com.sheltr.knowledge.interfaces.DocumentController;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Asserts that converted 400 and 404 responses carry the {@link ApiError} body shape.
 * Repositories are Mockito mocks, which return {@code Optional.empty()} by default, so every
 * lookup misses and the 404 path runs.
 */
class ErrorBodyMockMvcTest {

    private final MockMvc conversations = MockMvcBuilders
            .standaloneSetup(new ConversationController(mock(ConversationRepository.class), null))
            .setControllerAdvice(new ApiErrorAdvice())
            .build();

    private final MockMvc ingestion = MockMvcBuilders
            .standaloneSetup(new IngestionController(mock(IngestDocumentService.class),
                    mock(IngestionRunRepository.class), uri -> true))
            .setControllerAdvice(new ApiErrorAdvice())
            .build();

    private final MockMvc documents = MockMvcBuilders
            .standaloneSetup(new DocumentController(mock(KnowledgeDocumentRepository.class),
                    mock(ConversationRepository.class)))
            .setControllerAdvice(new ApiErrorAdvice())
            .build();

    @Test
    void unknownConversationReturns404WithApiErrorBody() throws Exception {
        conversations.perform(get("/api/v1/conversations/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Conversation not found."))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void unknownHazardOnAskReturns400WithApiErrorBody() throws Exception {
        conversations.perform(post("/api/v1/conversations/{id}/ask", UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"question\":\"What should I do?\",\"activeHazards\":[\"METEOR\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("activeHazards: contains an unknown hazard"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void unknownIngestionRunReturns404WithApiErrorBody() throws Exception {
        ingestion.perform(get("/api/v1/admin/ingestion/runs/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Ingestion run not found."))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void unknownHazardOnIngestionReturns400WithApiErrorBody() throws Exception {
        ingestion.perform(post("/api/v1/admin/ingestion/runs")
                        .contentType("application/json")
                        .content("{\"sourceUri\":\"https://example.org/a.pdf\",\"sourceType\":\"PDF\","
                                + "\"title\":\"T\",\"sourceAgency\":\"A\",\"hazards\":[\"METEOR\"],"
                                + "\"publishedDate\":\"2021-03-15\",\"license\":\"test-fixture-licence\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void unknownDocumentReturns404WithApiErrorBody() throws Exception {
        documents.perform(get("/api/v1/documents/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Document not found."))
                .andExpect(jsonPath("$.timestamp").exists());
    }
}

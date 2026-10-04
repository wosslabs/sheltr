package com.sheltr.interfaces;

import com.sheltr.ai.interfaces.ConversationController;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AskValidationMockMvcTest {

    // Null collaborators: a 400 here proves the request is rejected before any retrieval runs.
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ConversationController(null, null))
            .setControllerAdvice(new ApiErrorAdvice())
            .build();

    @Test
    void blankQuestionIsRejectedWithValidationError() throws Exception {
        mockMvc.perform(post("/api/v1/conversations/{id}/ask", UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"question\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("question: must not be blank"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void questionLongerThan2000CharactersIsRejectedWithValidationError() throws Exception {
        mockMvc.perform(post("/api/v1/conversations/{id}/ask", UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"question\":\"" + "x".repeat(2001) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("question: size must be between 0 and 2000"));
    }

    @Test
    void malformedJsonStillReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/conversations/{id}/ask", UUID.randomUUID())
                        .contentType("application/json")
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }
}

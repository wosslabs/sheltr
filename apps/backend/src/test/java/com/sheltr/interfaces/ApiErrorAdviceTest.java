package com.sheltr.interfaces;

import com.sheltr.ai.application.AnswerGenerationFailedException;
import com.sheltr.ai.application.ConversationNotFoundException;
import com.sheltr.ai.domain.ConversationId;
import com.sheltr.knowledge.domain.StaleEmbeddingsException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApiErrorAdviceTest {

    private static final String SECRET = "secret-internal-detail";

    private final ApiErrorAdvice advice = new ApiErrorAdvice();

    @Test
    void genericExceptionReturns500WithoutExceptionText() {
        ResponseEntity<ApiError> response = advice.handleUnexpected(new IllegalStateException(SECRET));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().error()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().message()).isEqualTo(ApiErrorAdvice.GENERIC_MESSAGE);
        assertThat(response.getBody().message()).doesNotContain(SECRET);
        assertThat(response.getBody().timestamp()).isNotNull();
    }

    @Test
    void conversationNotFoundReturns404() {
        ResponseEntity<ApiError> response =
                advice.handleNotFound(new ConversationNotFoundException(new ConversationId(UUID.randomUUID())));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().error()).isEqualTo("NOT_FOUND");
    }

    @Test
    void generationFailureReturns502WithoutReasonText() {
        ResponseEntity<ApiError> response = advice.handleGenerationFailed(new AnswerGenerationFailedException(SECRET));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody().error()).isEqualTo("GENERATION_FAILED");
        assertThat(response.getBody().message()).doesNotContain(SECRET);
    }

    @Test
    void staleEmbeddingsReturns503WithoutOperationalDetail() {
        ResponseEntity<ApiError> response = advice.handleStaleEmbeddings(new StaleEmbeddingsException(3, "model-x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().error()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(response.getBody().message()).doesNotContain("model-x");
    }

    @Test
    void limitExceededReturns429() {
        ResponseEntity<ApiError> response = advice.handleLimitExceeded(new LimitExceededException("Too many requests."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody().error()).isEqualTo("LIMIT_EXCEEDED");
        assertThat(response.getBody().message()).isEqualTo("Too many requests.");
    }
}

package com.sheltr.interfaces;

import com.sheltr.ai.application.AnswerGenerationFailedException;
import com.sheltr.ai.application.ConversationNotFoundException;
import com.sheltr.knowledge.domain.StaleEmbeddingsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;

/**
 * Maps every exception that reaches the API to the single {@link ApiError} body.
 * Extends {@link ResponseEntityExceptionHandler} so Spring MVC's own 4xx exceptions
 * (malformed JSON, unsupported method, and so on) keep their standard statuses instead
 * of falling through to the generic 500 handler.
 */
@RestControllerAdvice
public class ApiErrorAdvice extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorAdvice.class);

    static final String GENERIC_MESSAGE = "An unexpected error occurred.";

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        return validationFailed(ex);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        HttpStatus resolved = HttpStatus.resolve(statusCode.value());
        String code = resolved == null ? "ERROR" : resolved.name();
        String message = resolved == null ? "The request could not be processed."
                : resolved.getReasonPhrase();
        return ResponseEntity.status(statusCode).headers(headers)
                .body(new ApiError(code, message, Instant.now()));
    }

    @ExceptionHandler(ConversationNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(ConversationNotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "Conversation not found.");
    }

    @ExceptionHandler(AnswerGenerationFailedException.class)
    public ResponseEntity<ApiError> handleGenerationFailed(AnswerGenerationFailedException ex) {
        log.warn("Answer generation failed", ex);
        return error(HttpStatus.BAD_GATEWAY, "GENERATION_FAILED", "The answer could not be generated. Try again later.");
    }

    @ExceptionHandler(StaleEmbeddingsException.class)
    public ResponseEntity<ApiError> handleStaleEmbeddings(StaleEmbeddingsException ex) {
        log.error("Knowledge base embeddings are stale", ex);
        return error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                "The knowledge base is being updated. Try again later.");
    }

    @ExceptionHandler(LimitExceededException.class)
    public ResponseEntity<ApiError> handleLimitExceeded(LimitExceededException ex) {
        return error(HttpStatus.TOO_MANY_REQUESTS, "LIMIT_EXCEEDED", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("Unhandled exception while serving request", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", GENERIC_MESSAGE);
    }

    private ResponseEntity<Object> validationFailed(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(ApiErrorAdvice::describe)
                .orElse("The request is invalid.");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("VALIDATION_FAILED", message, Instant.now()));
    }

    private static String describe(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message, Instant.now()));
    }
}

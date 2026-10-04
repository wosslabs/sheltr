package com.sheltr.interfaces;

/**
 * Thrown when a request exceeds a rate limit or usage cap. Mapped to HTTP 429.
 */
public class LimitExceededException extends RuntimeException {
    public LimitExceededException(String message) {
        super(message);
    }
}

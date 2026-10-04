package com.sheltr.interfaces;

/**
 * Thrown when a request exceeds a rate limit or usage cap. Mapped to HTTP 429.
 * The message is returned verbatim in the 429 response body, so it must be client-safe;
 * never put internal detail in it.
 */
public class LimitExceededException extends RuntimeException {
    public LimitExceededException(String message) {
        super(message);
    }
}

package com.sheltr.interfaces;

import java.time.Instant;

/**
 * The single error body returned by every API endpoint.
 *
 * @param error     stable machine-readable code, such as {@code VALIDATION_FAILED}
 * @param message   safe, human-readable description; never an internal exception message
 * @param timestamp when the error was produced
 */
public record ApiError(String error, String message, Instant timestamp) {

    /** Builds an error body stamped with the current time. The message must be client-safe. */
    public static ApiError of(String error, String message) {
        return new ApiError(error, message, Instant.now());
    }
}

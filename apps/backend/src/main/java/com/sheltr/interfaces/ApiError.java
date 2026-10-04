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
}

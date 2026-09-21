package com.company.modernizer.controller.dto;

import java.time.Instant;
import java.util.List;

/**
 * Error response body.
 *
 * @param error   short machine-readable code, e.g. {@code "OUTSIDE_ALLOWED_ROOTS"}, so a client can
 *                branch without parsing prose
 * @param message human-readable explanation
 * @param details field-level validation messages, omitted when empty
 */
public record ApiError(
        String error,
        String message,
        List<String> details,
        Instant timestamp) {

    public static ApiError of(String error, String message) {
        return new ApiError(error, message, null, Instant.now());
    }

    public static ApiError of(String error, String message, List<String> details) {
        return new ApiError(error, message,
                details == null || details.isEmpty() ? null : List.copyOf(details),
                Instant.now());
    }
}

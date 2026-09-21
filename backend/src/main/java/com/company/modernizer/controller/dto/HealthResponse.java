package com.company.modernizer.controller.dto;

import java.time.Instant;

/**
 * Response for {@code GET /api/v1/health}.
 *
 * <p>Deliberately reports the effective AI configuration as well as liveness. On demo day the
 * most common question is "is it actually wired up to Claude?", and this answers it in one curl
 * without reading logs.
 */
public record HealthResponse(
        String status,
        String application,
        String analyzerVersion,
        String javaVersion,
        Instant timestamp,
        Ai ai) {

    /**
     * Effective LLM configuration.
     *
     * @param apiKeyPresent whether {@code ANTHROPIC_API_KEY} is set. Presence only - the key value
     *                      is never exposed by this or any other endpoint.
     */
    public record Ai(
            boolean enabled,
            String provider,
            String model,
            boolean apiKeyPresent) {
    }
}

package com.company.modernizer.controller;

import java.time.Instant;

import com.company.modernizer.config.ModernizerProperties;
import com.company.modernizer.controller.dto.HealthResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness and effective-configuration endpoint.
 *
 * <p>Also serves as the smoke test for the whole wiring chain: if this returns a populated
 * {@code ai} block, then application.yml parsed, {@code ModernizerProperties} bound, and
 * component scanning reached the controller package.
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final ModernizerProperties properties;

    public HealthController(ModernizerProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/health")
    public HealthResponse health() {
        ModernizerProperties.Ai ai = properties.ai();
        return new HealthResponse(
                "UP",
                "ai-java-modernization-agent",
                properties.analyzerVersion(),
                System.getProperty("java.version"),
                Instant.now(),
                new HealthResponse.Ai(
                        ai.enabled(),
                        ai.provider(),
                        ai.model(),
                        ai.apiKeyPresent()));
    }
}

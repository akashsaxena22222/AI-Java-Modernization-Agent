package com.company.modernizer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * AI Java Modernization Agent.
 *
 * <p>Phase 1 is an <em>analyzer only</em>. It scans a legacy Java project, derives facts
 * deterministically, asks an LLM to judge them, and returns a modernization report as JSON.
 * It never modifies the project under analysis - see ARCHITECTURE.md section 1.
 *
 * <p>{@code @ConfigurationPropertiesScan} binds {@code ModernizerProperties} without needing an
 * explicit {@code @EnableConfigurationProperties} registration for each new properties record.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ModernizerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ModernizerApplication.class, args);
    }
}

package com.company.modernizer.model;

import java.util.List;

/**
 * The {@code cloudReadiness} block: how close the project is to running on managed infrastructure,
 * and what stands in the way.
 *
 * <p>Separated from the general findings list because it answers a different question. Findings ask
 * "what is outdated?"; this asks "could we deploy this to AWS, and if not, why not?" - which is
 * usually the question that got the modernization funded.
 *
 * @param score 0-100, higher is more cloud-ready
 */
public record CloudReadiness(
        int score,
        List<Blocker> blockers,
        List<Opportunity> opportunities) {

    public CloudReadiness {
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
        opportunities = opportunities == null ? List.of() : List.copyOf(opportunities);
    }

    public static CloudReadiness notAssessed() {
        return new CloudReadiness(0, List.of(), List.of());
    }

    /**
     * Something that prevents cloud deployment outright.
     *
     * @param findingId links back to the {@link Finding} that evidences it, so a blocker is never
     *                  an unsupported assertion
     */
    public record Blocker(
            String issue,
            String findingId) {
    }

    /**
     * A concrete target worth moving to, with the reasoning attached.
     *
     * @param target    e.g. {@code "AWS ECS Fargate"}
     * @param rationale why it fits this project specifically - a generic recommendation is worthless
     * @param effort    cost of getting there
     */
    public record Opportunity(
            String target,
            String rationale,
            EffortSize effort) {
    }
}

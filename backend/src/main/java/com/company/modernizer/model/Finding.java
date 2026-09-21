package com.company.modernizer.model;

import java.util.List;

/**
 * One modernization opportunity.
 *
 * <p>The central record of the whole report. Three fields deserve attention:
 *
 * <ul>
 *   <li>{@link #source} makes the AI's contribution auditable rather than assumed.</li>
 *   <li>{@link #severity} and {@link #risk} are separate axes - how bad the problem is versus how
 *       dangerous the fix is. Collapsing them loses the information the roadmap needs.</li>
 *   <li>{@link #blockedBy} and {@link #blocks} form a dependency graph across findings. This is
 *       what makes the roadmap <em>derivable</em> instead of invented, and it is exactly the input
 *       a future autonomous planner needs to order its work.</li>
 * </ul>
 *
 * @param id             stable within one report, e.g. {@code "F-001"}; referenced by the summary,
 *                       the roadmap, and the blocking graph
 * @param evidence       where this was observed; at least one entry for any {@link FindingSource#STATIC}
 *                       finding
 * @param impact         why it matters, in terms a developer or a manager can act on
 * @param recommendation what to actually do
 * @param currentState   e.g. {@code "Java 8"}
 * @param targetState    e.g. {@code "Java 21 (LTS)"}
 * @param effort         cost to remediate
 * @param risk           danger of remediating, distinct from {@link #severity}
 * @param blockedBy      ids of findings that must be resolved first
 * @param blocks         ids of findings this one prevents progress on
 * @param references     external documentation supporting the recommendation
 */
public record Finding(
        String id,
        FindingCategory category,
        String title,
        Severity severity,
        Confidence confidence,
        FindingSource source,
        List<Evidence> evidence,
        String impact,
        String recommendation,
        String currentState,
        String targetState,
        EffortSize effort,
        RiskLevel risk,
        List<String> blockedBy,
        List<String> blocks,
        List<String> references) {

    public Finding {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        blockedBy = blockedBy == null ? List.of() : List.copyOf(blockedBy);
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        references = references == null ? List.of() : List.copyOf(references);
    }

    /** Whether this finding can be started immediately, i.e. nothing else gates it. */
    public boolean isUnblocked() {
        return blockedBy.isEmpty();
    }

    /** Whether the LLM contributed to this finding at all. */
    public boolean involvedAi() {
        return source == FindingSource.AI || source == FindingSource.STATIC_AI;
    }

    /**
     * Returns a copy with the LLM's enrichment applied and {@link #source} promoted to
     * {@link FindingSource#STATIC_AI}.
     *
     * <p>Used by report assembly (Step 6) to merge model judgement onto a statically detected
     * finding without letting it rewrite the detected facts - {@code category}, {@code evidence},
     * {@code currentState} and the ids are preserved exactly as the analyzer found them.
     */
    public Finding enrichedByAi(
            String impact,
            String recommendation,
            String targetState,
            EffortSize effort,
            RiskLevel risk,
            List<String> references) {
        return new Finding(
                id, category, title, severity, confidence,
                source == FindingSource.STATIC ? FindingSource.STATIC_AI : source,
                evidence,
                impact == null ? this.impact : impact,
                recommendation == null ? this.recommendation : recommendation,
                currentState,
                targetState == null ? this.targetState : targetState,
                effort == null ? this.effort : effort,
                risk == null ? this.risk : risk,
                blockedBy, blocks,
                references == null || references.isEmpty() ? this.references : references);
    }

    /**
     * Minimal static finding. The optional judgement fields are filled in later, either by the
     * analyzer itself or by the LLM.
     */
    public static Finding staticFinding(
            String id,
            FindingCategory category,
            String title,
            Severity severity,
            List<Evidence> evidence) {
        return new Finding(id, category, title, severity, Confidence.HIGH, FindingSource.STATIC,
                evidence, null, null, null, null, null, null, List.of(), List.of(), List.of());
    }
}

package com.company.modernizer.model;

import java.util.List;
import java.util.Map;

/**
 * The {@code summary} block: what someone reads if they read nothing else.
 *
 * @param overallRiskLevel      overall risk of leaving the project as it is
 * @param modernizationScore    0-100, higher is more modern. A single headline number for the demo;
 *                              derived by {@code ReportAssembler} from finding severities and
 *                              counts, so it is reproducible rather than model-invented.
 * @param headline              two or three sentences a manager can read aloud. The most valuable
 *                              single piece of LLM output in the whole report.
 * @param topPriorityFindingIds the few findings that matter most, in order
 * @param categoryCounts        findings per category, for the UI's grouping headers
 */
public record ReportSummary(
        RiskLevel overallRiskLevel,
        int modernizationScore,
        EstimatedEffort estimatedEffort,
        String headline,
        List<String> topPriorityFindingIds,
        Map<FindingCategory, Integer> categoryCounts) {

    public ReportSummary {
        topPriorityFindingIds = topPriorityFindingIds == null ? List.of() : List.copyOf(topPriorityFindingIds);
        categoryCounts = categoryCounts == null ? Map.of() : Map.copyOf(categoryCounts);
    }

    /**
     * Total effort to address the findings.
     *
     * <p>{@code personDays} carries its own {@link Confidence} because an estimate without one
     * invites a precision the model cannot justify.
     */
    public record EstimatedEffort(
            EffortSize size,
            int personDays,
            Confidence confidence) {
    }
}

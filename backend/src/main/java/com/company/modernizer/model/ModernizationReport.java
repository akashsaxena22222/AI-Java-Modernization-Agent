package com.company.modernizer.model;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The deliverable: a complete modernization assessment, serialized as the report JSON documented in
 * ARCHITECTURE.md section 11.
 *
 * <p>This is the API contract. Adding fields is safe; renaming or removing them breaks the Phase 2
 * UI and any saved report, so it is the most expensive thing in the project to change.
 */
public record ModernizationReport(
        String reportId,
        Instant generatedAt,
        ProjectInfo project,
        ReportSummary summary,
        List<Finding> findings,
        List<RoadmapPhase> roadmap,
        CloudReadiness cloudReadiness,
        ReportMeta meta) {

    public ModernizationReport {
        findings = findings == null ? List.of() : List.copyOf(findings);
        roadmap = roadmap == null ? List.of() : List.copyOf(roadmap);
    }

    /** Fresh identifier and timestamp for a report being assembled now. */
    public static String newReportId() {
        return UUID.randomUUID().toString();
    }

    /**
     * A static-analysis-only report - no LLM consulted, so no summary judgement, no roadmap and no
     * cloud assessment. This is what Step 3 produces and what the tool returns whenever
     * {@code modernizer.ai.enabled} is false.
     */
    public static ModernizationReport staticOnly(
            ProjectContext context,
            List<Finding> findings,
            String analyzerVersion) {
        return new ModernizationReport(
                newReportId(),
                Instant.now(),
                ProjectInfo.from(context),
                null,
                findings,
                List.of(),
                null,
                ReportMeta.staticOnly(analyzerVersion, context.scanStats()));
    }

    // --- Derived views ------------------------------------------------------------------------

    /** Findings ordered most severe first - the order the report and UI present them in. */
    public List<Finding> findingsBySeverity() {
        return findings.stream()
                .sorted(Comparator.comparing(Finding::severity).thenComparing(Finding::id))
                .toList();
    }

    /**
     * Finding counts per category, preserving {@link FindingCategory} declaration order and
     * omitting categories with no findings.
     */
    public Map<FindingCategory, Integer> categoryCounts() {
        Map<FindingCategory, Integer> counts = new LinkedHashMap<>();
        for (FindingCategory category : FindingCategory.values()) {
            int count = (int) findings.stream().filter(f -> f.category() == category).count();
            if (count > 0) {
                counts.put(category, count);
            }
        }
        return counts;
    }

    public long findingCount(Severity severity) {
        return findings.stream().filter(f -> f.severity() == severity).count();
    }

    /** Whether the LLM contributed anything to this report. */
    public boolean hasAiContribution() {
        return findings.stream().anyMatch(Finding::involvedAi);
    }
}

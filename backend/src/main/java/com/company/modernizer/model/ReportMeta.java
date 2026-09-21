package com.company.modernizer.model;

import java.util.List;

/**
 * The {@code meta} block: provenance and honesty about how the report was produced.
 *
 * <p>Carries the two admissions that keep the report trustworthy - {@link #truncated}, when the
 * payload had to be reduced to fit the token budget, and {@link #warnings}, naming exactly what was
 * dropped or skipped.
 *
 * @param aiAssessmentEnabled whether the LLM was consulted at all. When {@code false} every finding
 *                            is {@link FindingSource#STATIC} and the report was produced offline.
 * @param llm                 token counts and latency, or {@code null} when AI was not used.
 *                            Present so "what does this cost to run on our real codebase?" is
 *                            answerable from the artifact itself.
 * @param truncated           whether the payload was degraded to fit {@code max-input-tokens}
 */
public record ReportMeta(
        String analyzerVersion,
        boolean aiAssessmentEnabled,
        Llm llm,
        ScanStats scan,
        boolean truncated,
        List<String> warnings) {

    public ReportMeta {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** Report produced with no LLM involvement - Steps 0-3, or AI explicitly disabled. */
    public static ReportMeta staticOnly(String analyzerVersion, ScanStats scan) {
        return new ReportMeta(analyzerVersion, false, null, scan, false, scan.warnings());
    }

    /**
     * Which provider and model ran, and what it cost.
     *
     * <p>Provider-neutral by design: {@code provider} is a plain string so a Gemini or Grok adapter
     * populates the same record with no model change (ARCHITECTURE.md section 10).
     *
     * @param cacheReadInputTokens tokens served from the prompt cache. A high value across repeated
     *                             demo runs is the evidence that caching is working.
     */
    public record Llm(
            String provider,
            String model,
            Integer inputTokens,
            Integer outputTokens,
            Integer cacheReadInputTokens,
            Long latencyMs) {
    }
}

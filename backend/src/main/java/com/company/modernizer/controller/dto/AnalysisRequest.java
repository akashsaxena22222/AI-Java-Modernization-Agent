package com.company.modernizer.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/analysis}.
 *
 * <p>As with {@code ScanRequest}, the bean validation here only produces a tidy 400. The security
 * boundary is {@code ScanGuard}, which canonicalizes the path and checks it against the allowlist.
 *
 * @param path         project root to analyze; must resolve under a configured
 *                     {@code modernizer.scan.allowed-roots} entry
 * @param aiAssessment whether to consult the LLM. Deliberately a boxed {@link Boolean}: null means
 *                     "not specified, use the configured default", which is distinguishable from
 *                     an explicit false. Section 9.5 makes egress an explicit decision, so
 *                     "unspecified" and "declined" should not collapse into the same value.
 */
public record AnalysisRequest(

        @NotBlank(message = "path is required")
        @Size(max = 4096, message = "path is unreasonably long")
        String path,

        Boolean aiAssessment) {
}

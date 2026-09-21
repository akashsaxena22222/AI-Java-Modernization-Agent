package com.company.modernizer.controller;

import com.company.modernizer.controller.dto.AnalysisRequest;
import com.company.modernizer.model.ModernizationReport;
import com.company.modernizer.report.HtmlReportRenderer;
import com.company.modernizer.service.AnalysisOrchestrator;

import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The analysis endpoint: issues found, and what to change.
 *
 * <p>Two representations of the same report, from the same orchestrator call:
 *
 * <ul>
 *   <li>{@code POST /api/v1/analysis} returns the JSON contract documented in ARCHITECTURE.md
 *       section 11. This is the machine-readable artifact everything else builds on.</li>
 *   <li>{@code GET /api/v1/analysis?path=...} returns a self-contained HTML document. A GET with a
 *       query parameter because it can then be pasted straight into a browser - the analysis is
 *       read-only, so GET is honest about what it does.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class AnalysisController {

    private final AnalysisOrchestrator orchestrator;
    private final HtmlReportRenderer htmlRenderer;

    public AnalysisController(AnalysisOrchestrator orchestrator, HtmlReportRenderer htmlRenderer) {
        this.orchestrator = orchestrator;
        this.htmlRenderer = htmlRenderer;
    }

    @PostMapping(value = "/analysis", produces = MediaType.APPLICATION_JSON_VALUE)
    public ModernizationReport analyze(@Valid @RequestBody AnalysisRequest request) {
        return orchestrator.analyze(request.path(), request.aiAssessment());
    }

    /**
     * The same report as a browsable, saveable HTML page.
     *
     * <p>Served as a complete document rather than a fragment so that "save as" produces something
     * that still renders. {@code Content-Disposition: inline} keeps it in the browser rather than
     * prompting a download, while still naming the file if the reader does save it.
     */
    @GetMapping(value = "/analysis", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> analyzeAsHtml(
            @RequestParam String path,
            @RequestParam(required = false) Boolean aiAssessment) {

        ModernizationReport report = orchestrator.analyze(path, aiAssessment);
        String fileName = report.project() == null
                ? "modernization-report.html"
                : report.project().name() + "-modernization-report.html";

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header("Content-Disposition", "inline; filename=\"" + fileName + "\"")
                .body(htmlRenderer.render(report));
    }
}

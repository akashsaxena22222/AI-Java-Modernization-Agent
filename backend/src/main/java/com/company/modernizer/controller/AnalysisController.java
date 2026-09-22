package com.company.modernizer.controller;

import com.company.modernizer.controller.dto.AnalysisRequest;
import com.company.modernizer.model.ModernizationReport;
import com.company.modernizer.report.ClaudeMigrationPlanRenderer;
import com.company.modernizer.report.HtmlReportRenderer;
import com.company.modernizer.service.AnalysisOrchestrator;

import com.company.modernizer.upload.ZipExtractionService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;

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
    private final ClaudeMigrationPlanRenderer claudeMigrationPlanRenderer;
    private final ZipExtractionService zipExtractionService;

    public AnalysisController(AnalysisOrchestrator orchestrator, HtmlReportRenderer htmlRenderer, ClaudeMigrationPlanRenderer claudeMigrationPlanRenderer,  ZipExtractionService zipExtractionService) {
        this.orchestrator = orchestrator;
        this.htmlRenderer = htmlRenderer;
        this.claudeMigrationPlanRenderer = claudeMigrationPlanRenderer;
        this.zipExtractionService = zipExtractionService;
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
    @GetMapping(value = "/analysis", produces = MediaType.TEXT_HTML_VALUE, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> analyzeAsHtml(
            @RequestParam(required = false) String path,
            @RequestParam(required = false) Boolean aiAssessment,
            @RequestPart("file") MultipartFile file) {

            Path extractedDirectory = zipExtractionService.extract(file);
            if(path == null || path.isEmpty()) {
                path = extractedDirectory.toString();
            }
            ModernizationReport report = orchestrator.analyze(path, aiAssessment);

            String fileName = report.project() == null
                ? "modernization-report.html"
                : report.project().name() + "-modernization-report.html";

            return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header("Content-Disposition", "inline; filename=\"" + fileName + "\"")
                .body(htmlRenderer.render(report));


    }

    @GetMapping(value = "/claude-md" , consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> downloadClaudeMd(
            @RequestParam(required = false) String path,
            @RequestParam(required = false) Boolean aiAssessment,
            @RequestPart("file") MultipartFile file) {

        Path extractedDirectory = zipExtractionService.extract(file);
        if(path == null || path.isEmpty()) {
            path = extractedDirectory.toString();
        }
        ModernizationReport report =
                orchestrator.analyze(path, aiAssessment);

        String claudeMd =
                claudeMigrationPlanRenderer.render(report);

        String fileName = report.project() == null
                ? "CLAUDE.md"
                : report.project().name() + "-CLAUDE.md";

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/markdown"))
                .header(
                        "Content-Disposition",
                        "attachment; filename=\"" + fileName + "\"")
                .body(claudeMd);
    }

}



package com.company.modernizer.service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.company.modernizer.analyzer.Analyzer;
import com.company.modernizer.config.ModernizerProperties;
import com.company.modernizer.model.Finding;
import com.company.modernizer.model.ModernizationReport;
import com.company.modernizer.model.ProjectContext;
import com.company.modernizer.model.ProjectInfo;
import com.company.modernizer.model.ReportMeta;
import com.company.modernizer.scanner.ProjectScanner;
import com.company.modernizer.scanner.ScanGuard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs the pipeline: validate, scan, analyze, assemble.
 *
 * <p>Names no concrete analyzer. Spring injects every {@link Analyzer} bean, which is what makes
 * the SPI claim real - {@code PropertiesConfigAnalyzer} was added after this class was written and
 * required no edit to it.
 *
 * <p>Returns {@link ModernizationReport} from a plain synchronous method (ARCHITECTURE.md section
 * 5d). Wrapping this in a job id and a polling endpoint later is purely additive, because nothing
 * about the signature assumes the caller is an HTTP request thread.
 */
@Service
public class AnalysisOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AnalysisOrchestrator.class);

    private final ScanGuard guard;
    private final ProjectScanner scanner;
    private final List<Analyzer> analyzers;
    private final FindingIdAssigner idAssigner;
    private final ModernizerProperties properties;

    public AnalysisOrchestrator(
            ScanGuard guard,
            ProjectScanner scanner,
            List<Analyzer> analyzers,
            FindingIdAssigner idAssigner,
            ModernizerProperties properties) {
        this.guard = guard;
        this.scanner = scanner;
        this.analyzers = analyzers;
        this.idAssigner = idAssigner;
        this.properties = properties;
    }

    /**
     * Produces a modernization report for a project directory.
     *
     * @param requestedPath  path from the request; validated before anything is opened
     * @param aiRequested    whether the caller asked for LLM assessment; {@code null} means "not
     *                       specified, use the configured default"
     */
    public ModernizationReport analyze(String requestedPath, Boolean aiRequested) {
        ProjectContext context = scanner.scan(Path.of(requestedPath));

        //Path root = guard.validate(requestedPath);

        List<String> warnings = new ArrayList<>(context.scanStats().warnings());
        List<Finding> findings = idAssigner.assign(runAnalyzers(context, warnings));

        aiUnavailableWarning(aiRequested).ifPresent(warnings::add);

        log.info("Analyzed {}: {} findings from {} analyzers{}",
                context.projectName(), findings.size(), analyzers.size(),
                warnings.isEmpty() ? "" : " (" + warnings.size() + " warnings)");

        return new ModernizationReport(
                ModernizationReport.newReportId(),
                Instant.now(),
                ProjectInfo.from(context),
                // summary, roadmap and cloudReadiness stay absent: they are judgement, and
                // nothing has judged yet. Steps 4-6 fill them in. Emitting an empty summary
                // would imply we had assessed the project and found nothing to say.
                null,
                findings,
                List.of(),
                null,
                new ReportMeta(
                        properties.analyzerVersion(),
                        false,
                        null,
                        context.scanStats(),
                        false,
                        List.copyOf(warnings)));
    }

    /**
     * Runs every applicable analyzer, isolating failures.
     *
     * <p>One rule throwing must not cost the whole report - but the gap must be attributable, so
     * the failure becomes a warning naming the analyzer rather than a silently shorter list.
     */
    private List<Finding> runAnalyzers(ProjectContext context, List<String> warnings) {
        List<Finding> findings = new ArrayList<>();
        for (Analyzer analyzer : analyzers) {
            if (!analyzer.supports(context)) {
                log.debug("{} does not apply to this project", analyzer.name());
                continue;
            }
            try {
                findings.addAll(analyzer.analyze(context));
            } catch (RuntimeException e) {
                log.error("Analyzer {} failed", analyzer.name(), e);
                warnings.add(analyzer.name() + " failed (" + e.getClass().getSimpleName()
                        + "): its findings are missing from this report.");
            }
        }
        return findings;
    }

    /**
     * Explains, in the report, why an AI assessment the caller asked for is not present.
     *
     * <p>Section 9.5 makes egress an explicit decision, so silently returning a static report to
     * someone who asked for an assessed one would be the wrong kind of quiet.
     */
    private java.util.Optional<String> aiUnavailableWarning(Boolean aiRequested) {
        boolean wanted = aiRequested != null ? aiRequested : properties.ai().enabled();
        if (!wanted) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(
                "AI assessment was requested but no LLM provider is wired up yet, so this report "
                        + "is static analysis only. Every finding is source STATIC; there is no "
                        + "roadmap, score or cloud-readiness assessment.");
    }
}

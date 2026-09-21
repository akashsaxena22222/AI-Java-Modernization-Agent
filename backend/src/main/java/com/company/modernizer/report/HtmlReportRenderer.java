package com.company.modernizer.report;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import com.company.modernizer.model.Evidence;
import com.company.modernizer.model.Finding;
import com.company.modernizer.model.FindingCategory;
import com.company.modernizer.model.FrameworkRef;
import com.company.modernizer.model.ModernizationReport;
import com.company.modernizer.model.ProjectInfo;
import com.company.modernizer.model.ProjectMetrics;
import com.company.modernizer.model.Severity;

import org.springframework.stereotype.Component;

/**
 * Renders a {@link ModernizationReport} as a single self-contained HTML document.
 *
 * <p>No JavaScript, no external stylesheet, no web fonts, no network requests of any kind. The
 * output is one file that renders identically when served over HTTP, saved to disk, or attached to
 * an email - which is what makes it usable as a deliverable rather than only as a screen.
 *
 * <h2>On escaping</h2>
 *
 * <p>Every dynamic value goes through {@link #escape}. This is not routine caution: evidence
 * snippets are lines lifted verbatim out of the analyzed project, and the projects this tool exists
 * to assess are full of unescaped markup - one of the bundled fixtures deliberately contains
 * cross-site scripting bugs. Rendering a snippet unescaped would let an analyzed codebase inject
 * script into the report about it. A template engine with automatic escaping would give the same
 * guarantee; this stays dependency-free instead, and pays for that with one rule that is never
 * bypassed and is covered by a test.
 */
@Component
public class HtmlReportRenderer {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("d MMM yyyy 'at' HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    public String render(ModernizationReport report) {
        StringBuilder html = new StringBuilder(16_384);

        html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
                .append("<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>Modernization report")
                .append(report.project() == null ? "" : " — " + escape(report.project().name()))
                .append("</title>\n<style>\n").append(stylesheet()).append("\n</style>\n</head>\n<body>\n");

        html.append("<main>\n");
        renderHeader(html, report);
        renderWarnings(html, report);
        renderSeveritySummary(html, report);
        renderProject(html, report.project());
        renderFindings(html, report);
        renderFooter(html, report);
        html.append("</main>\n</body>\n</html>\n");

        return html.toString();
    }

    // --- Sections -------------------------------------------------------------------------------

    private void renderHeader(StringBuilder html, ModernizationReport report) {
        ProjectInfo project = report.project();
        html.append("<header class=\"page-head\">\n")
                .append("<p class=\"eyebrow\">Modernization assessment</p>\n")
                .append("<h1>").append(escape(project == null ? "Unknown project" : project.name()))
                .append("</h1>\n<p class=\"subtitle\">");

        if (project != null) {
            html.append(escape(String.valueOf(project.buildTool())))
                    .append(" project");
            if (project.detectedJavaVersion() != null) {
                html.append(" &middot; declares Java ")
                        .append(escape(project.detectedJavaVersion()));
            }
            html.append(" &middot; ");
        }
        html.append(report.findings().size()).append(" finding")
                .append(report.findings().size() == 1 ? "" : "s")
                .append("</p>\n</header>\n");
    }

    private void renderWarnings(StringBuilder html, ModernizationReport report) {
        if (report.meta() == null || report.meta().warnings().isEmpty()) {
            return;
        }
        html.append("<section class=\"callout\">\n")
                .append("<h2>What this report does not cover</h2>\n<ul>\n");
        for (String warning : report.meta().warnings()) {
            html.append("<li>").append(escape(warning)).append("</li>\n");
        }
        html.append("</ul>\n</section>\n");
    }

    private void renderSeveritySummary(StringBuilder html, ModernizationReport report) {
        html.append("<section class=\"tiles\">\n");
        for (Severity severity : Severity.values()) {
            long count = report.findingCount(severity);
            if (count == 0) {
                continue;
            }
            html.append("<div class=\"tile sev-").append(severity.name().toLowerCase())
                    .append("\">\n<span class=\"tile-count\">").append(count)
                    .append("</span>\n<span class=\"tile-label\">")
                    .append(escape(severity.name())).append("</span>\n</div>\n");
        }
        html.append("</section>\n");

        Map<FindingCategory, Integer> counts = report.categoryCounts();
        if (counts.isEmpty()) {
            return;
        }
        html.append("<section>\n<h2>By area</h2>\n<ul class=\"chips\">\n");
        counts.forEach((category, count) -> html.append("<li><span class=\"chip\">")
                .append(escape(category.displayName())).append(" <b>").append(count)
                .append("</b></span></li>\n"));
        html.append("</ul>\n</section>\n");
    }

    private void renderProject(StringBuilder html, ProjectInfo project) {
        if (project == null) {
            return;
        }
        html.append("<section>\n<h2>Project</h2>\n<table class=\"facts\">\n<tbody>\n");

        ProjectMetrics metrics = project.metrics();
        row(html, "Build tool", String.valueOf(project.buildTool()));
        row(html, "Declared Java version",
                project.detectedJavaVersion() == null ? "not declared" : project.detectedJavaVersion());
        row(html, "Modules", String.join(", ", project.modules()));
        if (metrics != null) {
            row(html, "Java sources", String.valueOf(metrics.javaFiles()));
            row(html, "Test classes", metrics.testFiles() + " (ratio "
                    + String.format("%.2f", metrics.testToSourceRatio()) + ")");
            row(html, "Lines of code", String.valueOf(metrics.linesOfCode()));
            row(html, "XML config files", String.valueOf(metrics.xmlConfigFiles()));
            row(html, "WSDL contracts", String.valueOf(metrics.wsdlFiles()));
            row(html, "JSP views", String.valueOf(metrics.jspFiles()));
            row(html, "Declared dependencies", String.valueOf(metrics.declaredDependencies()));
        }
        html.append("</tbody>\n</table>\n");

        List<FrameworkRef> frameworks = project.detectedFrameworks();
        if (!frameworks.isEmpty()) {
            html.append("<h3>Detected frameworks</h3>\n<ul class=\"chips\">\n");
            for (FrameworkRef framework : frameworks) {
                html.append("<li><span class=\"chip\">").append(escape(framework.name()))
                        .append(" <b>").append(escape(framework.version()))
                        .append("</b></span></li>\n");
            }
            html.append("</ul>\n");
        }
        html.append("</section>\n");
    }

    private void renderFindings(StringBuilder html, ModernizationReport report) {
        html.append("<section>\n<h2>Findings</h2>\n");

        if (report.findings().isEmpty()) {
            html.append("<p class=\"empty\">No findings. Either this project is in good shape, "
                    + "or no analyzer applied to it &mdash; check the coverage note above.</p>\n"
                    + "</section>\n");
            return;
        }

        for (Finding finding : report.findingsBySeverity()) {
            renderFinding(html, finding);
        }
        html.append("</section>\n");
    }

    private void renderFinding(StringBuilder html, Finding finding) {
        String severity = finding.severity().name().toLowerCase();

        html.append("<article class=\"finding sev-border-").append(severity).append("\">\n")
                .append("<div class=\"finding-head\">\n")
                .append("<span class=\"badge sev-").append(severity).append("\">")
                .append(escape(finding.severity().name())).append("</span>\n")
                .append("<span class=\"finding-id\">").append(escape(finding.id())).append("</span>\n")
                .append("<h3>").append(escape(finding.title())).append("</h3>\n")
                .append("</div>\n");

        html.append("<p class=\"meta-line\">")
                .append(escape(finding.category().displayName()));
        if (finding.effort() != null) {
            html.append(" &middot; effort <b>").append(escape(finding.effort().name())).append("</b>");
        }
        if (finding.risk() != null) {
            html.append(" &middot; risk of change <b>").append(escape(finding.risk().name()))
                    .append("</b>");
        }
        if (finding.confidence() != null) {
            html.append(" &middot; confidence ").append(escape(finding.confidence().name()));
        }
        html.append(" &middot; source ").append(escape(String.valueOf(finding.source())))
                .append("</p>\n");

        if (finding.currentState() != null || finding.targetState() != null) {
            html.append("<p class=\"transition\"><span class=\"from\">")
                    .append(escape(nullSafe(finding.currentState(), "current")))
                    .append("</span> <span class=\"arrow\">&rarr;</span> <span class=\"to\">")
                    .append(escape(nullSafe(finding.targetState(), "target")))
                    .append("</span></p>\n");
        }

        if (finding.impact() != null) {
            html.append("<h4>Why it matters</h4>\n<p>").append(escape(finding.impact()))
                    .append("</p>\n");
        }
        if (finding.recommendation() != null) {
            html.append("<h4>What to change</h4>\n<p class=\"recommendation\">")
                    .append(escape(finding.recommendation())).append("</p>\n");
        }

        renderEvidence(html, finding.evidence());
        renderGraph(html, finding);
        renderReferences(html, finding.references());

        html.append("</article>\n");
    }

    private void renderEvidence(StringBuilder html, List<Evidence> evidence) {
        if (evidence.isEmpty()) {
            return;
        }
        html.append("<h4>Evidence</h4>\n<ul class=\"evidence\">\n");
        for (Evidence item : evidence) {
            html.append("<li><code class=\"loc\">").append(escape(item.location())).append("</code>");
            if (item.snippet() != null && !item.snippet().isBlank()) {
                html.append("<pre>").append(escape(item.snippet())).append("</pre>");
            }
            html.append("</li>\n");
        }
        html.append("</ul>\n");
    }

    private void renderGraph(StringBuilder html, Finding finding) {
        if (finding.blockedBy().isEmpty() && finding.blocks().isEmpty()) {
            return;
        }
        html.append("<p class=\"graph\">");
        if (!finding.blockedBy().isEmpty()) {
            html.append("Blocked by ").append(escape(String.join(", ", finding.blockedBy())));
        }
        if (!finding.blockedBy().isEmpty() && !finding.blocks().isEmpty()) {
            html.append(" &middot; ");
        }
        if (!finding.blocks().isEmpty()) {
            html.append("Blocks ").append(escape(String.join(", ", finding.blocks())));
        }
        html.append("</p>\n");
    }

    private void renderReferences(StringBuilder html, List<String> references) {
        if (references.isEmpty()) {
            return;
        }
        html.append("<p class=\"refs\">");
        for (int i = 0; i < references.size(); i++) {
            if (i > 0) {
                html.append(" &middot; ");
            }
            String url = references.get(i);
            // Only http(s) becomes a link. A javascript: or data: URL from a future rule or an
            // LLM-supplied reference must never become a clickable anchor.
            if (url.startsWith("https://") || url.startsWith("http://")) {
                html.append("<a href=\"").append(escape(url))
                        .append("\" rel=\"noopener noreferrer nofollow\">")
                        .append(escape(url)).append("</a>");
            } else {
                html.append(escape(url));
            }
        }
        html.append("</p>\n");
    }

    private void renderFooter(StringBuilder html, ModernizationReport report) {
        html.append("<footer>\n<p>");
        if (report.meta() != null) {
            html.append("Analyzer ").append(escape(report.meta().analyzerVersion()))
                    .append(" &middot; ")
                    .append(report.meta().aiAssessmentEnabled()
                            ? "AI assessment enabled"
                            : "static analysis only, no AI assessment");
            if (report.meta().scan() != null) {
                html.append(" &middot; ").append(report.meta().scan().filesScanned())
                        .append(" files scanned in ").append(report.meta().scan().durationMs())
                        .append(" ms");
            }
            html.append(" &middot; ");
        }
        html.append("generated ")
                .append(report.generatedAt() == null ? "" : escape(TIMESTAMP.format(report.generatedAt())))
                .append("</p>\n<p class=\"report-id\">")
                .append(escape(String.valueOf(report.reportId()))).append("</p>\n</footer>\n");
    }

    // --- Helpers ----------------------------------------------------------------------------------

    private static void row(StringBuilder html, String label, String value) {
        html.append("<tr><th scope=\"row\">").append(escape(label)).append("</th><td>")
                .append(escape(value)).append("</td></tr>\n");
    }

    private static String nullSafe(String value, String fallback) {
        return value == null ? fallback : value;
    }

    /**
     * Escapes text for HTML element and attribute contexts.
     *
     * <p>Covers both because every interpolation in this class lands in one or the other. Quotes
     * are escaped as well as angle brackets, so a value can be dropped into a double-quoted
     * attribute without breaking out of it.
     */
    static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    private static String stylesheet() {
        return """
                :root {
                  --ink: #1a1c1f; --muted: #5c6470; --line: #e3e6ea; --bg: #ffffff;
                  --panel: #f7f8fa;
                  --critical: #b3261e; --high: #c2570a; --medium: #96700a;
                  --low: #2a6098; --info: #5c6470;
                }
                * { box-sizing: border-box; }
                body {
                  margin: 0; background: var(--bg); color: var(--ink);
                  font: 15px/1.6 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto,
                        "Helvetica Neue", Arial, sans-serif;
                }
                main { max-width: 860px; margin: 0 auto; padding: 40px 24px 72px; }
                h1 { font-size: 30px; line-height: 1.2; margin: 4px 0 8px; }
                h2 { font-size: 19px; margin: 40px 0 14px; padding-bottom: 8px;
                     border-bottom: 1px solid var(--line); }
                h3 { font-size: 16px; margin: 0; }
                h4 { font-size: 12px; text-transform: uppercase; letter-spacing: .06em;
                     color: var(--muted); margin: 18px 0 6px; }
                p { margin: 0 0 12px; }

                .eyebrow { text-transform: uppercase; letter-spacing: .1em; font-size: 11px;
                           color: var(--muted); margin: 0; font-weight: 600; }
                .page-head { border-bottom: 2px solid var(--ink); padding-bottom: 20px; }
                .subtitle { color: var(--muted); margin: 0; }

                .callout { background: #fff8e6; border: 1px solid #f0dca8;
                           border-radius: 8px; padding: 4px 20px 16px; margin-top: 28px; }
                .callout h2 { border: 0; font-size: 14px; margin: 16px 0 8px; }
                .callout ul { margin: 0; padding-left: 20px; color: #6b5617; font-size: 14px; }

                .tiles { display: flex; flex-wrap: wrap; gap: 12px; margin: 28px 0 0; }
                .tile { flex: 1 1 96px; border: 1px solid var(--line); border-top-width: 3px;
                        border-radius: 8px; padding: 12px 14px; background: var(--panel); }
                .tile-count { display: block; font-size: 26px; font-weight: 650; line-height: 1; }
                .tile-label { display: block; font-size: 11px; letter-spacing: .07em;
                              text-transform: uppercase; color: var(--muted); margin-top: 6px; }
                .tile.sev-critical { border-top-color: var(--critical); }
                .tile.sev-high     { border-top-color: var(--high); }
                .tile.sev-medium   { border-top-color: var(--medium); }
                .tile.sev-low      { border-top-color: var(--low); }
                .tile.sev-info     { border-top-color: var(--info); }

                .chips { list-style: none; display: flex; flex-wrap: wrap; gap: 8px;
                         padding: 0; margin: 0 0 8px; }
                .chip { display: inline-block; border: 1px solid var(--line); border-radius: 999px;
                        padding: 3px 12px; font-size: 13px; background: var(--panel); }

                table.facts { border-collapse: collapse; width: 100%; font-size: 14px; }
                table.facts th, table.facts td {
                  text-align: left; padding: 7px 10px; border-bottom: 1px solid var(--line);
                  vertical-align: top;
                }
                table.facts th { color: var(--muted); font-weight: 500; width: 40%; }

                .finding { border: 1px solid var(--line); border-left-width: 4px;
                           border-radius: 8px; padding: 18px 20px; margin: 0 0 16px; }
                .sev-border-critical { border-left-color: var(--critical); }
                .sev-border-high     { border-left-color: var(--high); }
                .sev-border-medium   { border-left-color: var(--medium); }
                .sev-border-low      { border-left-color: var(--low); }
                .sev-border-info     { border-left-color: var(--info); }

                .finding-head { display: flex; align-items: baseline; flex-wrap: wrap; gap: 10px; }
                .badge { font-size: 10px; font-weight: 700; letter-spacing: .08em;
                         text-transform: uppercase; color: #fff; border-radius: 4px;
                         padding: 3px 7px; white-space: nowrap; }
                .badge.sev-critical { background: var(--critical); }
                .badge.sev-high     { background: var(--high); }
                .badge.sev-medium   { background: var(--medium); }
                .badge.sev-low      { background: var(--low); }
                .badge.sev-info     { background: var(--info); }
                .finding-id { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
                              font-size: 12px; color: var(--muted); }

                .meta-line { font-size: 13px; color: var(--muted); margin: 10px 0 0; }
                .transition { font-size: 14px; margin: 10px 0 0; }
                .from { background: #fdecea; border-radius: 4px; padding: 2px 8px; }
                .to   { background: #e8f4ea; border-radius: 4px; padding: 2px 8px; }
                .arrow { color: var(--muted); margin: 0 4px; }
                .recommendation { border-left: 3px solid #cfd6de; padding-left: 14px; }

                ul.evidence { list-style: none; padding: 0; margin: 0; }
                ul.evidence li { margin-bottom: 8px; }
                code.loc { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
                           font-size: 12.5px; background: var(--panel); border: 1px solid var(--line);
                           border-radius: 4px; padding: 1px 6px; }
                ul.evidence pre { margin: 6px 0 0; padding: 9px 12px; background: #1f2328;
                                  color: #e6edf3; border-radius: 6px; overflow-x: auto;
                                  font-size: 12.5px; line-height: 1.5; }

                .graph, .refs { font-size: 13px; color: var(--muted); margin: 14px 0 0; }
                .refs a { color: var(--low); }
                .empty { color: var(--muted); }

                footer { margin-top: 48px; padding-top: 16px; border-top: 1px solid var(--line);
                         font-size: 12px; color: var(--muted); }
                .report-id { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
                             font-size: 11px; }

                @media print {
                  main { max-width: none; padding: 0; }
                  .finding { break-inside: avoid; }
                }
                """;
    }
}

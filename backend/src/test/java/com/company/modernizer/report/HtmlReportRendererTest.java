package com.company.modernizer.report;

import java.time.Instant;
import java.util.List;

import com.company.modernizer.model.BuildTool;
import com.company.modernizer.model.Confidence;
import com.company.modernizer.model.EffortSize;
import com.company.modernizer.model.Evidence;
import com.company.modernizer.model.Finding;
import com.company.modernizer.model.FindingCategory;
import com.company.modernizer.model.FindingSource;
import com.company.modernizer.model.ModernizationReport;
import com.company.modernizer.model.ProjectInfo;
import com.company.modernizer.model.ProjectMetrics;
import com.company.modernizer.model.ReportMeta;
import com.company.modernizer.model.RiskLevel;
import com.company.modernizer.model.ScanStats;
import com.company.modernizer.model.Severity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the HTML rendering.
 *
 * <p>{@link Escaping} is the half that matters. Evidence snippets are lines copied out of the
 * analyzed project, and the projects this tool exists to assess are full of unescaped markup - one
 * bundled fixture contains deliberate cross-site scripting bugs. Without escaping, analyzing a
 * hostile codebase would execute its markup in the report about it.
 */
class HtmlReportRendererTest {

    private final HtmlReportRenderer renderer = new HtmlReportRenderer();

    private ModernizationReport reportWith(Finding... findings) {
        return new ModernizationReport(
                "3f2c1b-report-id",
                Instant.parse("2026-09-21T09:15:00Z"),
                new ProjectInfo("legacy-order-service", "legacy-order-service", BuildTool.MAVEN,
                        List.of("."), "1.8", List.of(),
                        new ProjectMetrics(8, 1, 416L, 4, 1, 5, 16)),
                null,
                List.of(findings),
                List.of(),
                null,
                new ReportMeta("0.1.0", false, null,
                        new ScanStats(25, 1, 60082L, 7L, false, List.of()), false, List.of()));
    }

    private Finding finding(String id, Severity severity, String title, Evidence... evidence) {
        return new Finding(id, FindingCategory.SECURITY, title, severity, Confidence.HIGH,
                FindingSource.STATIC, List.of(evidence), "Impact text.", "Recommendation text.",
                "current", "target", EffortSize.M, RiskLevel.LOW,
                List.of(), List.of(), List.of());
    }

    @Nested
    @DisplayName("document")
    class Document {

        @Test
        @DisplayName("is a complete, self-contained page with no external requests")
        void selfContained() {
            String html = renderer.render(reportWith(
                    finding("F-001", Severity.HIGH, "A finding", Evidence.ofFile("pom.xml"))));

            assertThat(html).startsWith("<!DOCTYPE html>");
            assertThat(html).contains("<html lang=\"en\">").endsWith("</html>\n");

            // The whole point of a saveable report: nothing loaded from the network, and no
            // script at all. Both are also what make it safe to email.
            assertThat(html).doesNotContain("<script")
                    .doesNotContain("src=\"http")
                    .doesNotContain("href=\"http://fonts")
                    .doesNotContain("@import");
            assertThat(html).contains("<style>");
        }

        @Test
        @DisplayName("shows the project facts and the finding's impact and recommendation")
        void showsContent() {
            String html = renderer.render(reportWith(
                    finding("F-001", Severity.CRITICAL, "Plaintext credentials committed",
                            Evidence.of("conf/db.properties", 12, "db.password=***REDACTED***"))));

            assertThat(html)
                    .contains("legacy-order-service")
                    .contains("MAVEN")
                    .contains("Plaintext credentials committed")
                    .contains("F-001")
                    .contains("CRITICAL")
                    .contains("Why it matters")
                    .contains("What to change")
                    .contains("conf/db.properties:12")
                    .contains("db.password=***REDACTED***");
        }

        @Test
        @DisplayName("says so plainly when there are no findings, rather than rendering an empty page")
        void emptyState() {
            assertThat(renderer.render(reportWith()))
                    .contains("No findings")
                    .contains("no analyzer applied");
        }

        @Test
        @DisplayName("reports that the assessment was static only, so the AI's absence is visible")
        void declaresStaticOnly() {
            assertThat(renderer.render(reportWith()))
                    .contains("static analysis only, no AI assessment");
        }

        @Test
        @DisplayName("renders a severity tile for each severity actually present")
        void severityTiles() {
            String html = renderer.render(reportWith(
                    finding("F-001", Severity.CRITICAL, "One", Evidence.ofFile("a")),
                    finding("F-002", Severity.LOW, "Two", Evidence.ofFile("b")),
                    finding("F-003", Severity.LOW, "Three", Evidence.ofFile("c"))));

            assertThat(html).contains("tile sev-critical").contains("tile sev-low");
            // Severities with no findings are omitted rather than shown as zero.
            assertThat(html).doesNotContain("tile sev-info");
        }
    }

    @Nested
    @DisplayName("escaping")
    class Escaping {

        @Test
        @DisplayName("neutralizes markup in an evidence snippet taken from the analyzed project")
        void escapesEvidenceSnippet() {
            String hostile = "<script>alert('xss')</script>";
            String html = renderer.render(reportWith(
                    finding("F-001", Severity.HIGH, "Unescaped output",
                            Evidence.of("web/list.jsp", 44, hostile))));

            assertThat(html).doesNotContain(hostile);
            assertThat(html).contains("&lt;script&gt;alert(&#39;xss&#39;)&lt;/script&gt;");
        }

        @Test
        @DisplayName("neutralizes markup in a finding title, which can carry a file name")
        void escapesTitle() {
            String html = renderer.render(reportWith(
                    finding("F-001", Severity.HIGH, "<img src=x onerror=alert(1)>",
                            Evidence.ofFile("a.jsp"))));

            assertThat(html).doesNotContain("<img src=x").contains("&lt;img src=x");
        }

        @Test
        @DisplayName("neutralizes markup in a file path, so a crafted filename cannot break out")
        void escapesEvidencePath() {
            String html = renderer.render(reportWith(
                    finding("F-001", Severity.LOW, "Finding",
                            Evidence.ofFile("src/\"><script>alert(1)</script>.java"))));

            assertThat(html).doesNotContain("<script>alert(1)</script>");
        }

        @Test
        @DisplayName("escapes quotes so a value cannot escape an HTML attribute")
        void escapesQuotes() {
            assertThat(HtmlReportRenderer.escape("\" onmouseover=\"alert(1)"))
                    .isEqualTo("&quot; onmouseover=&quot;alert(1)");
        }

        @Test
        @DisplayName("escapes ampersands without double-escaping the entities it produces")
        void escapesAmpersandsOnce() {
            assertThat(HtmlReportRenderer.escape("a & b < c")).isEqualTo("a &amp; b &lt; c");
        }

        @Test
        @DisplayName("links only http(s) references, rendering any other scheme as inert text")
        void refusesNonHttpSchemes() {
            Finding withBadRef = new Finding("F-001", FindingCategory.SECURITY, "t",
                    Severity.LOW, Confidence.HIGH, FindingSource.STATIC,
                    List.of(Evidence.ofFile("a")), null, null, null, null, null, null,
                    List.of(), List.of(),
                    List.of("javascript:alert(1)", "https://example.test/guide"));

            String html = renderer.render(reportWith(withBadRef));

            // The dangerous scheme never becomes an href; the safe one does.
            assertThat(html).doesNotContain("href=\"javascript:");
            assertThat(html).contains("href=\"https://example.test/guide\"");
        }
    }
}

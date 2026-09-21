package com.company.modernizer.model;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the derived logic in the domain model. No Spring context - these are pure data
 * structures and should stay fast.
 */
class DomainModelTest {

    @Nested
    @DisplayName("ProjectMetrics")
    class Metrics {

        @Test
        @DisplayName("derives testToSourceRatio so it cannot go stale")
        void derivesRatio() {
            ProjectMetrics metrics = new ProjectMetrics(412, 22, 58230L, 37, 6, 44, 64);
            assertThat(metrics.testToSourceRatio()).isEqualTo(0.053);
        }

        @Test
        @DisplayName("reports a zero ratio for a project with no Java sources rather than dividing by zero")
        void handlesNoSources() {
            assertThat(ProjectMetrics.empty().testToSourceRatio()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("Severity")
    class Severities {

        @Test
        @DisplayName("orders most-severe-first, which is also the payload drop order")
        void ordering() {
            assertThat(Severity.CRITICAL.isAtLeast(Severity.HIGH)).isTrue();
            assertThat(Severity.HIGH.isAtLeast(Severity.HIGH)).isTrue();
            assertThat(Severity.LOW.isAtLeast(Severity.HIGH)).isFalse();
        }
    }

    @Nested
    @DisplayName("ScannedFile")
    class Files {

        private final ScannedFile soapService = new ScannedFile(
                "src/main/java/com/acme/OrderService.java",
                FileKind.JAVA_SOURCE, 4096L, 120, 15, 25,
                List.of("javax.jws.WebService", "java.util.List"),
                List.of("WebService"));

        @Test
        @DisplayName("codeLines excludes blanks and comments")
        void codeLines() {
            assertThat(soapService.codeLines()).isEqualTo(80);
        }

        @Test
        @DisplayName("detects legacy imports by package prefix")
        void importPrefix() {
            assertThat(soapService.importsPackage("javax.jws")).isTrue();
            assertThat(soapService.importsPackage("jakarta.")).isFalse();
        }

        @Test
        @DisplayName("matches annotations by simple or qualified name")
        void annotations() {
            assertThat(soapService.hasAnnotation("WebService")).isTrue();
            assertThat(soapService.hasAnnotation("RestController")).isFalse();
        }

        @Test
        @DisplayName("files inventoried by name only carry no content-derived metrics")
        void notRead() {
            ScannedFile jar = ScannedFile.notRead("lib/legacy.jar", 9_000_000L);
            assertThat(jar.kind()).isEqualTo(FileKind.NOT_READ);
            assertThat(jar.lineCount()).isZero();
            assertThat(jar.imports()).isEmpty();
        }

        @Test
        @DisplayName("collections are defensively copied, so a context cannot be mutated after construction")
        void immutability() {
            assertThatThrownBy(() -> soapService.imports().add("x"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("ProjectContext")
    class Context {

        private final ProjectContext ctx = new ProjectContext(
                "legacy-order-service",
                Path.of("/workspace/legacy-order-service").toAbsolutePath(),
                BuildTool.MAVEN,
                List.of("order-api", "order-core"),
                "1.8",
                List.of(new FrameworkRef("Spring Framework", "4.3.9.RELEASE", "pom.xml")),
                List.of(
                        new ScannedFile("src/main/java/A.java", FileKind.JAVA_SOURCE, 100L, 10, 0, 0,
                                List.of("javax.jws.WebService"), List.of("WebService")),
                        new ScannedFile("src/test/java/ATest.java", FileKind.JAVA_TEST, 100L, 10, 0, 0,
                                List.of("junit.framework.TestCase"), List.of()),
                        new ScannedFile("src/main/webapp/WEB-INF/web.xml", FileKind.WEB_XML, 100L, 10, 0, 0,
                                List.of(), List.of())),
                List.of(
                        new DependencyRef("org.springframework", "spring-core", "4.3.9.RELEASE",
                                "compile", false, "pom.xml"),
                        new DependencyRef("junit", "junit", "3.8.1", "test", false, "pom.xml")),
                new ProjectMetrics(1, 1, 20L, 1, 0, 0, 2),
                ScanStats.empty());

        @Test
        @DisplayName("filters files by kind, the main entry point for analyzers")
        void filesOfKind() {
            assertThat(ctx.filesOfKind(FileKind.JAVA_SOURCE, FileKind.JAVA_TEST)).hasSize(2);
            assertThat(ctx.filesOfKind(FileKind.WSDL)).isEmpty();
        }

        @Test
        @DisplayName("finds SOAP usage by import and annotation without touching the filesystem")
        void legacyDetection() {
            assertThat(ctx.filesImporting("javax.jws")).hasSize(1);
            assertThat(ctx.filesAnnotatedWith("WebService")).hasSize(1);
            assertThat(ctx.filesImporting("junit.framework")).hasSize(1);
        }

        @Test
        @DisplayName("looks up dependencies by coordinates")
        void dependencyLookup() {
            assertThat(ctx.hasDependency("junit", "junit")).isTrue();
            assertThat(ctx.hasDependency("org.junit.jupiter", "junit-jupiter")).isFalse();
            assertThat(ctx.dependency("junit", "junit")).get()
                    .extracting(DependencyRef::version).isEqualTo("3.8.1");
            assertThat(ctx.dependenciesInGroup("org.springframework")).hasSize(1);
        }

        @Test
        @DisplayName("resolves a scanned file to an absolute path for analyzers needing content")
        void resolvesPaths() {
            ScannedFile webXml = ctx.filesOfKind(FileKind.WEB_XML).getFirst();
            Path resolved = ctx.resolve(webXml);

            // Compared by value, not with AssertJ's Path.endsWith - that canonicalizes via
            // toRealPath() and would demand the fixture actually exist on disk.
            assertThat(resolved).isAbsolute();
            assertThat(resolved).isEqualTo(ctx.rootPath().resolve("src/main/webapp/WEB-INF/web.xml"));
        }

        @Test
        @DisplayName("recognizes a multi-module project")
        void multiModule() {
            assertThat(ctx.isMultiModule()).isTrue();
        }
    }

    @Nested
    @DisplayName("Finding")
    class Findings {

        private final Finding staticFinding = Finding.staticFinding(
                "F-001", FindingCategory.JAVA_VERSION, "Project targets Java 8",
                Severity.HIGH, List.of(Evidence.of("pom.xml", 24, "<source>1.8</source>")));

        @Test
        @DisplayName("a bare static finding is attributed to STATIC and has no AI involvement")
        void staticAttribution() {
            assertThat(staticFinding.source()).isEqualTo(FindingSource.STATIC);
            assertThat(staticFinding.involvedAi()).isFalse();
            assertThat(staticFinding.isUnblocked()).isTrue();
        }

        @Test
        @DisplayName("AI enrichment promotes the source to STATIC_AI but cannot rewrite detected facts")
        void enrichmentPreservesFacts() {
            Finding enriched = staticFinding.enrichedByAi(
                    "Blocks every framework upgrade.",
                    "Move to Java 21.",
                    "Java 21 (LTS)",
                    EffortSize.M,
                    RiskLevel.MEDIUM,
                    List.of("https://example.test/guide"));

            assertThat(enriched.source()).isEqualTo(FindingSource.STATIC_AI);
            assertThat(enriched.involvedAi()).isTrue();
            assertThat(enriched.impact()).isEqualTo("Blocks every framework upgrade.");
            assertThat(enriched.effort()).isEqualTo(EffortSize.M);

            // The facts the analyzer established are untouched by the model.
            assertThat(enriched.id()).isEqualTo(staticFinding.id());
            assertThat(enriched.category()).isEqualTo(staticFinding.category());
            assertThat(enriched.evidence()).isEqualTo(staticFinding.evidence());
            assertThat(enriched.severity()).isEqualTo(staticFinding.severity());
        }

        @Test
        @DisplayName("evidence renders as a pasteable file:line location")
        void evidenceLocation() {
            assertThat(Evidence.of("pom.xml", 24, "x").location()).isEqualTo("pom.xml:24");
            assertThat(Evidence.ofFile("pom.xml").location()).isEqualTo("pom.xml");
        }
    }

    @Nested
    @DisplayName("ModernizationReport")
    class Report {

        private ModernizationReport reportWith(Finding... findings) {
            return new ModernizationReport("id", java.time.Instant.now(), null, null,
                    List.of(findings), List.of(), null, null);
        }

        private Finding finding(String id, FindingCategory category, Severity severity) {
            return Finding.staticFinding(id, category, id, severity, List.of());
        }

        @Test
        @DisplayName("sorts findings most severe first, tie-broken by id for stable output")
        void sortsBySeverity() {
            ModernizationReport report = reportWith(
                    finding("F-003", FindingCategory.TESTING, Severity.LOW),
                    finding("F-001", FindingCategory.SECURITY, Severity.CRITICAL),
                    finding("F-002", FindingCategory.BUILD, Severity.CRITICAL));

            assertThat(report.findingsBySeverity())
                    .extracting(Finding::id)
                    .containsExactly("F-001", "F-002", "F-003");
        }

        @Test
        @DisplayName("counts by category in enum declaration order, omitting empty categories")
        void categoryCounts() {
            ModernizationReport report = reportWith(
                    finding("F-001", FindingCategory.SECURITY, Severity.HIGH),
                    finding("F-002", FindingCategory.JAVA_VERSION, Severity.HIGH),
                    finding("F-003", FindingCategory.SECURITY, Severity.LOW));

            assertThat(report.categoryCounts())
                    .containsExactly(
                            java.util.Map.entry(FindingCategory.JAVA_VERSION, 1),
                            java.util.Map.entry(FindingCategory.SECURITY, 2));
        }

        @Test
        @DisplayName("reports no AI contribution when every finding is static")
        void detectsAiContribution() {
            assertThat(reportWith(finding("F-001", FindingCategory.BUILD, Severity.LOW))
                    .hasAiContribution()).isFalse();
        }
    }
}

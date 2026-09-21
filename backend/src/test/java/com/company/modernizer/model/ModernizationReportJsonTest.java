package com.company.modernizer.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the report JSON contract documented in ARCHITECTURE.md section 11.
 *
 * <p>Worth having as a real test rather than trusting Jackson: the Phase 2 UI and any saved report
 * are written against these exact field names, so a rename should fail the build rather than be
 * discovered in a browser. It also pins the two Jackson 3 behaviours Boot 4 brought in - ISO-8601
 * dates and null omission - which were the source of the Step 0 build failure.
 *
 * <p>Assertions run against whitespace-stripped JSON so they are immune to the pretty-printing
 * configured in application.yml.
 */
@SpringBootTest
class ModernizationReportJsonTest {

    @Autowired
    private JsonMapper jsonMapper;

    private ModernizationReport report;

    @BeforeEach
    void setUp() {
        report = sampleReport();
    }

    private String compactJson() {
        return jsonMapper.writeValueAsString(report).replaceAll("\\s+", "");
    }

    @Test
    @DisplayName("Top-level report keys match the documented contract")
    void topLevelKeys() {
        String json = compactJson();
        assertThat(json)
                .contains("\"reportId\":")
                .contains("\"generatedAt\":")
                .contains("\"project\":")
                .contains("\"summary\":")
                .contains("\"findings\":")
                .contains("\"roadmap\":")
                .contains("\"cloudReadiness\":")
                .contains("\"meta\":");
    }

    @Test
    @DisplayName("Instant serializes as an ISO-8601 string, not a numeric timestamp")
    void instantIsIso8601() {
        // Jackson 3 (Boot 4) makes this the default; Jackson 2 needed configuration. Pinned here
        // because a numeric timestamp would silently break the UI's date rendering.
        assertThat(compactJson()).contains("\"generatedAt\":\"2026-");
    }

    @Test
    @DisplayName("Enums serialize by name, including as map keys")
    void enumsSerializeByName() {
        String json = compactJson();
        assertThat(json)
                .contains("\"category\":\"JAVA_VERSION\"")
                .contains("\"severity\":\"HIGH\"")
                .contains("\"source\":\"STATIC_AI\"")
                .contains("\"buildTool\":\"MAVEN\"")
                .contains("\"risk\":\"MEDIUM\"")
                // categoryCounts is keyed by the enum
                .contains("\"JAVA_VERSION\":1");
    }

    @Test
    @DisplayName("Finding carries checkable file:line evidence and the blocking graph")
    void findingShape() {
        String json = compactJson();
        assertThat(json)
                .contains("\"id\":\"F-001\"")
                .contains("\"file\":\"pom.xml\"")
                .contains("\"line\":24")
                .contains("\"blocks\":[\"F-004\"]")
                .contains("\"currentState\":\"Java8\"")
                .contains("\"targetState\":\"Java21(LTS)\"");
    }

    @Test
    @DisplayName("Null fields are omitted so the payload stays lean")
    void nullsAreOmitted() {
        // 'snippet' is null on the second Evidence entry, and 'llm' is null in meta.
        String json = compactJson();
        assertThat(json).doesNotContain("\"snippet\":null");
        assertThat(json).doesNotContain("\"llm\":null");
    }

    @Test
    @DisplayName("meta reports provenance: analyzer version, AI flag, scan stats, truncation")
    void metaShape() {
        String json = compactJson();
        assertThat(json)
                .contains("\"analyzerVersion\":\"0.1.0\"")
                .contains("\"aiAssessmentEnabled\":true")
                .contains("\"truncated\":false")
                .contains("\"filesScanned\":531");
    }

    @Test
    @DisplayName("A static-only report omits summary, roadmap and cloud readiness entirely")
    void staticOnlyReportShape() {
        ModernizationReport staticReport = ModernizationReport.staticOnly(
                sampleContext(),
                List.of(sampleFinding()),
                "0.1.0");

        String json = jsonMapper.writeValueAsString(staticReport).replaceAll("\\s+", "");

        assertThat(json)
                .contains("\"aiAssessmentEnabled\":false")
                .contains("\"findings\":[")
                .doesNotContain("\"summary\":")
                .doesNotContain("\"cloudReadiness\":");
    }

    @Test
    @DisplayName("The absolute project root never reaches the serialized report")
    void absolutePathIsNotLeaked() {
        // ProjectInfo.from() reduces ProjectContext's absolute Path to a display name. Section 9.2
        // forbids absolute paths in anything that leaves the process.
        ModernizationReport staticReport = ModernizationReport.staticOnly(
                sampleContext(), List.of(), "0.1.0");

        String json = jsonMapper.writeValueAsString(staticReport);

        assertThat(json).doesNotContain("C:").doesNotContain("/tmp/");
        assertThat(json).contains("\"rootPath\" : \"legacy-order-service\"");
    }

    // --- Fixtures -----------------------------------------------------------------------------

    private static ProjectContext sampleContext() {
        return new ProjectContext(
                "legacy-order-service",
                java.nio.file.Path.of("/tmp/workspace/legacy-order-service").toAbsolutePath(),
                BuildTool.MAVEN,
                List.of("order-api", "order-core"),
                "1.8",
                List.of(new FrameworkRef("Spring Framework", "4.3.9.RELEASE", "pom.xml")),
                List.of(),
                List.of(new DependencyRef("org.springframework", "spring-core",
                        "4.3.9.RELEASE", "compile", false, "pom.xml")),
                new ProjectMetrics(412, 22, 58230L, 37, 6, 44, 64),
                new ScanStats(531, 12, 4_200_000L, 1840L, false, List.of()));
    }

    private static Finding sampleFinding() {
        return new Finding(
                "F-001",
                FindingCategory.JAVA_VERSION,
                "Project targets Java 8; five LTS releases behind",
                Severity.HIGH,
                Confidence.HIGH,
                FindingSource.STATIC_AI,
                List.of(
                        Evidence.of("pom.xml", 24, "<maven.compiler.source>1.8</maven.compiler.source>"),
                        Evidence.ofFile("order-core/pom.xml")),
                "Blocks every other framework upgrade; Spring Boot 3 requires Java 17+.",
                "Move to Java 21 LTS in one step. Run jdeps for removed-API usage first.",
                "Java 8",
                "Java 21 (LTS)",
                EffortSize.M,
                RiskLevel.MEDIUM,
                List.of(),
                List.of("F-004"),
                List.of("https://docs.oracle.com/en/java/javase/21/migrate/"));
    }

    private static ModernizationReport sampleReport() {
        return new ModernizationReport(
                "b3f1c2a8-4e5d-4f21-9a77-2c0e5d1b8e44",
                Instant.parse("2026-09-19T14:22:08Z"),
                ProjectInfo.from(sampleContext()),
                new ReportSummary(
                        RiskLevel.HIGH,
                        34,
                        new ReportSummary.EstimatedEffort(EffortSize.L, 45, Confidence.MEDIUM),
                        "Java 8 / Spring 4.3 monolith with XML-driven config and six SOAP endpoints.",
                        List.of("F-001"),
                        Map.of(FindingCategory.JAVA_VERSION, 1)),
                List.of(sampleFinding()),
                List.of(new RoadmapPhase(1, "Stabilize and measure",
                        "Reproducible build and JUnit 5 in place.",
                        List.of("F-001"), EffortSize.S, 6, List.of())),
                new CloudReadiness(
                        25,
                        List.of(new CloudReadiness.Blocker("Filesystem session state", "F-019")),
                        List.of(new CloudReadiness.Opportunity("AWS ECS Fargate",
                                "Stateless after phase 3.", EffortSize.M))),
                new ReportMeta(
                        "0.1.0",
                        true,
                        new ReportMeta.Llm("anthropic", "claude-opus-5", 18422, 6110, 17104, 42310L),
                        new ScanStats(531, 12, 4_200_000L, 1840L, false, List.of()),
                        false,
                        List.of()));
    }
}

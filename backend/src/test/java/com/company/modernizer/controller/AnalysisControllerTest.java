package com.company.modernizer.controller;

import java.util.List;

import com.company.modernizer.model.ModernizationReport;
import com.company.modernizer.service.AnalysisOrchestrator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for {@code /api/v1/analysis}, against both committed fixtures.
 *
 * <p>Runs the real pipeline - guard, scanner, POM parser, every registered analyzer, id assignment
 * - over real projects on disk. Deliberately not mocked: the value of this suite is that it would
 * catch an analyzer that throws, a rule that never fires, or a dangling blocking reference, none of
 * which a mocked orchestrator could show.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class AnalysisControllerTest {

    private static final String SPRING_FIXTURE = "demo/legacy-sample-app";
    private static final String ANT_FIXTURE = "demo/legacy-payroll-ant";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private AnalysisOrchestrator orchestrator;

    private MvcTestResult analyze(String path) {
        return mvc.post().uri("/api/v1/analysis")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"" + path + "\"}")
                .exchange();
    }

    @Nested
    @DisplayName("the Spring/Maven fixture")
    class SpringFixture {

        @Test
        @DisplayName("produces findings with impact, recommendation and file:line evidence")
        void producesActionableFindings() {
            var json = assertThat(analyze(SPRING_FIXTURE)).hasStatusOk().bodyJson();

            json.extractingPath("$.project.name").isEqualTo("legacy-sample-app");
            json.extractingPath("$.findings").asArray().isNotEmpty();

            // Every finding must be actionable and checkable, not just named.
            json.extractingPath("$.findings[*].impact").asArray().isNotEmpty();
            json.extractingPath("$.findings[*].recommendation").asArray().isNotEmpty();
            json.extractingPath("$.findings[*].evidence[0].file").asArray().isNotEmpty();
        }

        @Test
        @DisplayName("detects the planted problems the fixture README promises")
        void detectsPlantedProblems() {
            ModernizationReport report = orchestrator.analyze(SPRING_FIXTURE, false);
            List<String> categories = report.findings().stream()
                    .map(f -> f.category().name()).distinct().toList();

            assertThat(categories).contains(
                    "JAVA_VERSION",          // <maven.compiler.source>1.8
                    "SPRING_MODERNIZATION",  // Spring 4.3.9
                    "DEPENDENCY_HEALTH",     // log4j 1.2.17, commons-collections 3.2.1
                    "DEPRECATED_API",        // javax.* and sun.misc
                    "SOAP_WEBSERVICE",       // @WebService
                    "XML_CONFIGURATION",     // web.xml DTD, XML beans
                    "SECURITY",              // MD5, CSRF disabled, plaintext credentials
                    "TESTING",               // JUnit 3, one test class
                    "BUILD",                 // unpinned plugins, missing version
                    "CLOUD_READINESS");      // session state, absolute paths
        }

        @Test
        @DisplayName("flags the committed plaintext credentials as critical")
        void flagsCredentials() {
            ModernizationReport report = orchestrator.analyze(SPRING_FIXTURE, false);

            assertThat(report.findings())
                    .filteredOn(f -> f.id() != null && f.title().contains("plaintext"))
                    .singleElement()
                    .satisfies(f -> assertThat(f.severity().name()).isEqualTo("CRITICAL"));
        }

        @Test
        @DisplayName("never puts a discovered secret into the report it returns")
        void doesNotEchoSecrets() {
            // The fixture's fabricated passwords, which the credential rule reads directly.
            // Detecting a secret and then printing it would be a self-inflicted leak.
            assertThat(analyze(SPRING_FIXTURE)).hasStatusOk().bodyText()
                    .doesNotContain("Ord3rsPr0d2011")
                    .doesNotContain("mailer-secret-2013")
                    .doesNotContain("AKIAIOSFODNN7EXAMPLE")
                    .contains("***REDACTED***");
        }

        @Test
        @DisplayName("orders findings most severe first and numbers them stably")
        void ordersAndNumbers() {
            var json = assertThat(analyze(SPRING_FIXTURE)).hasStatusOk().bodyJson();

            json.extractingPath("$.findings[0].id").isEqualTo("F-001");
            json.extractingPath("$.findings[0].severity").isEqualTo("CRITICAL");
            json.extractingPath("$.findings[*].source").asArray()
                    .allSatisfy(source -> assertThat(source).isEqualTo("STATIC"));
        }

        @Test
        @DisplayName("leaves summary, roadmap and cloudReadiness absent until an LLM has judged")
        void omitsJudgement() {
            var json = assertThat(analyze(SPRING_FIXTURE)).hasStatusOk().bodyJson();

            json.extractingPath("$.roadmap").asArray().isEmpty();
            json.extractingPath("$.meta.aiAssessmentEnabled").isEqualTo(false);
            assertThat(analyze(SPRING_FIXTURE)).hasStatusOk().bodyText()
                    .doesNotContain("\"summary\"")
                    .doesNotContain("\"cloudReadiness\"");
        }

        @Test
        @DisplayName("explains in the report when an AI assessment was asked for but is unavailable")
        void explainsMissingAi() {
            ModernizationReport report = orchestrator.analyze(SPRING_FIXTURE, true);

            assertThat(report.meta().warnings())
                    .anySatisfy(w -> assertThat(w).contains("AI assessment was requested"));
            assertThat(report.meta().aiAssessmentEnabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("the Ant/Struts fixture")
    class AntFixture {

        @Test
        @DisplayName("analyzes a project with no Maven POM at all")
        void analyzesAntProject() {
            var json = assertThat(analyze(ANT_FIXTURE)).hasStatusOk().bodyJson();

            json.extractingPath("$.project.name").isEqualTo("legacy-payroll-ant");
            json.extractingPath("$.project.buildTool").isEqualTo("ANT");
            json.extractingPath("$.findings").asArray().isNotEmpty();
        }

        @Test
        @DisplayName("stands down the Maven analyzer rather than reporting a dependency-free project")
        void mavenAnalyzerStandsDown() {
            ModernizationReport report = orchestrator.analyze(ANT_FIXTURE, false);

            // No pom.xml means no dependency facts. The absence of DEPENDENCY_HEALTH findings
            // here is honest silence, not a clean bill of health - MavenPomAnalyzer.supports()
            // returned false rather than the rules running and finding nothing.
            assertThat(report.project().metrics().declaredDependencies()).isZero();
            assertThat(report.findings())
                    .noneSatisfy(f -> assertThat(f.category().name()).isEqualTo("DEPENDENCY_HEALTH"));
        }

        @Test
        @DisplayName("still finds the credential, filesystem and web-tier problems")
        void findsNonMavenProblems() {
            ModernizationReport report = orchestrator.analyze(ANT_FIXTURE, false);
            List<String> categories = report.findings().stream()
                    .map(f -> f.category().name()).distinct().toList();

            assertThat(categories).contains("SECURITY", "CLOUD_READINESS", "XML_CONFIGURATION");
        }

        @Test
        @DisplayName("counts the top-level test/ directory as tests, not as production source")
        void recognizesAntTestLayout() {
            ModernizationReport report = orchestrator.analyze(ANT_FIXTURE, false);

            // Ant puts tests in test/, not src/test/java. Counting PayrollUtilsTest as a source
            // file would inflate the test-to-source ratio in the flattering direction.
            assertThat(report.project().metrics().testFiles()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("HTML rendering")
    class Html {

        private MvcTestResult analyzeAsHtml(String path) {
            return mvc.get().uri("/api/v1/analysis?path=" + path).exchange();
        }

        @Test
        @DisplayName("serves the same report as a self-contained HTML document")
        void servesHtml() {
            assertThat(analyzeAsHtml(SPRING_FIXTURE))
                    .hasStatusOk()
                    .hasContentTypeCompatibleWith(MediaType.TEXT_HTML)
                    .bodyText()
                    .startsWith("<!DOCTYPE html>")
                    .contains("legacy-sample-app")
                    .contains("Why it matters")
                    .contains("What to change")
                    .doesNotContain("<script");
        }

        @Test
        @DisplayName("names the file so a saved copy is identifiable")
        void suggestsAFilename() {
            assertThat(analyzeAsHtml(SPRING_FIXTURE))
                    .hasStatusOk()
                    .headers()
                    .hasValue("Content-Disposition",
                            "inline; filename=\"legacy-sample-app-modernization-report.html\"");
        }

        @Test
        @DisplayName("does not leak secrets into the HTML either")
        void htmlDoesNotEchoSecrets() {
            assertThat(analyzeAsHtml(SPRING_FIXTURE)).hasStatusOk().bodyText()
                    .doesNotContain("Ord3rsPr0d2011")
                    .contains("***REDACTED***");
        }

        @Test
        @DisplayName("applies the same path guard as the JSON endpoint")
        void enforcesTheGuard() {
            assertThat(mvc.get().uri("/api/v1/analysis?path=backend").exchange())
                    .hasStatus(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("rejections")
    class Rejections {

        @Test
        @DisplayName("refuses a path outside the allowlist, as the scan endpoint does")
        void outsideAllowlist() {
            assertThat(analyze("backend"))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson()
                    .extractingPath("$.error").isEqualTo("OUTSIDE_ALLOWED_ROOTS");
        }

        @Test
        @DisplayName("refuses a blank path")
        void blankPath() {
            assertThat(analyze(""))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson()
                    .extractingPath("$.error").isEqualTo("VALIDATION_FAILED");
        }
    }
}

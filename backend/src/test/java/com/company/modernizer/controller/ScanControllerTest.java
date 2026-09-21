package com.company.modernizer.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

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
 * End-to-end tests for {@code POST /api/v1/scan}.
 *
 * <p>Runs against the real application.yml, so the allowlist under test is the shipped one
 * ({@code ./demo}) rather than a convenient test value. That is deliberate: these verify the
 * boundary a deployed instance actually enforces.
 *
 * <p>Request paths are relative to the Surefire working directory, which backend/pom.xml sets to
 * the repository root so it matches {@code spring-boot:run}. The bodies below are therefore
 * identical to the curl commands in the README.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class ScanControllerTest {

    private static final String FIXTURE = "demo/legacy-sample-app";

    @Autowired
    private MockMvcTester mvc;

    private MvcTestResult scan(String path) {
        return mvc.post().uri("/api/v1/scan")
                .contentType(MediaType.APPLICATION_JSON)
                .content(path == null ? "{}" : "{\"path\":\"" + path + "\"}")
                .exchange();
    }

    @Nested
    @DisplayName("scanning the demo fixture")
    class HappyPath {

        @Test
        @DisplayName("returns the inventory with no interpretation layered on")
        void returnsInventory() {
            var json = assertThat(scan(FIXTURE)).hasStatusOk().bodyJson();

            json.extractingPath("$.project.name").isEqualTo("legacy-sample-app");
            json.extractingPath("$.project.buildTool").isEqualTo("MAVEN");
            json.extractingPath("$.project.modules").asArray().containsExactly(".");
            // A documented planted problem: one test file for the whole project.
            json.extractingPath("$.project.metrics.testFiles").isEqualTo(1);
            json.extractingPath("$.scan.limitsHit").isEqualTo(false);

            // Build facts, as of Step 3: the scan now parses the POMs it finds, so this endpoint
            // reports the declared dependency list and the frameworks derived from it. Still no
            // interpretation - nothing here says 4.3.9 is old. That is a finding, and findings
            // come from /api/v1/analysis.
            json.extractingPath("$.project.detectedJavaVersion").isEqualTo("1.8");
            json.extractingPath("$.project.metrics.declaredDependencies").isEqualTo(16);
            json.extractingPath("$.project.detectedFrameworks[*].name").asArray()
                    .contains("Spring Framework", "Spring Security", "Hibernate", "JAX-WS");
        }

        @Test
        @DisplayName("counts files by kind, the quickest read on what a legacy project contains")
        void countsFilesByKind() {
            assertThat(scan(FIXTURE))
                    .hasStatusOk()
                    .bodyJson()
                    .extractingPath("$.fileCountsByKind").asMap()
                    .containsKeys("MAVEN_POM", "JAVA_SOURCE", "JAVA_TEST", "WEB_XML",
                            "SPRING_XML", "PROPERTIES", "WSDL", "XSD", "JSP", "NOT_READ")
                    // Kinds with no files are omitted rather than reported as zero.
                    .doesNotContainKey("GRADLE_BUILD")
                    .doesNotContainKey("DOCKERFILE");
        }

        @Test
        @DisplayName("leaks no absolute filesystem path, so machine layout stays on the machine")
        void leaksNoAbsolutePath() {
            String absoluteRoot = Path.of("").toAbsolutePath().toString();

            // ProjectContext holds the absolute root; ProjectInfo.from drops it (section 9.2).
            // Asserted at the edge, where it matters, rather than trusting that every future field
            // addition remembers to sanitize.
            assertThat(scan(FIXTURE))
                    .hasStatusOk()
                    .bodyText()
                    .doesNotContain(absoluteRoot)
                    // JSON escapes Windows separators, so the escaped form must be excluded too.
                    .doesNotContain(absoluteRoot.replace("\\", "\\\\"));
        }

        @Test
        @DisplayName("reports the project root by name only")
        void reportsRootByNameOnly() {
            assertThat(scan(FIXTURE))
                    .hasStatusOk()
                    .bodyJson()
                    .extractingPath("$.project.rootPath").isEqualTo("legacy-sample-app");
        }

        @Test
        @DisplayName("does not modify the project it scanned")
        void isReadOnly() throws IOException {
            // Phase 1 is read-only by construction (section 1), but "by construction" is a claim
            // about the code. This checks the observable consequence: every path and modification
            // time in the scanned tree is unchanged afterwards.
            Map<Path, FileTime> before = snapshotFixture();

            assertThat(scan(FIXTURE)).hasStatusOk();

            assertThat(snapshotFixture()).isEqualTo(before);
        }

        private Map<Path, FileTime> snapshotFixture() throws IOException {
            Map<Path, FileTime> snapshot = new TreeMap<>();
            try (Stream<Path> paths = Files.walk(Path.of(FIXTURE))) {
                for (Path path : paths.toList()) {
                    snapshot.put(path, Files.getLastModifiedTime(path));
                }
            }
            return snapshot;
        }
    }

    @Nested
    @DisplayName("rejects with 400 and a machine-readable code")
    class Rejections {

        private void assertRejected(String path, String expectedCode) {
            assertThat(scan(path))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson()
                    .extractingPath("$.error").isEqualTo(expectedCode);
        }

        @Test
        @DisplayName("a path outside the configured allowlist")
        void outsideAllowlist() {
            // 'backend' exists and is a real directory - it is simply not under ./demo.
            assertRejected("backend", "OUTSIDE_ALLOWED_ROOTS");
        }

        @Test
        @DisplayName("a .. sequence that climbs out of the allowlist")
        void traversalOutOfAllowlist() {
            assertRejected("demo/legacy-sample-app/../..", "OUTSIDE_ALLOWED_ROOTS");
        }

        @Test
        @DisplayName("a path that does not exist, rather than an empty report for a typo")
        void notFound() {
            assertRejected("demo/no-such-project", "NOT_FOUND");
        }

        @Test
        @DisplayName("a file where a project directory was expected")
        void notADirectory() {
            assertRejected("demo/legacy-sample-app/pom.xml", "NOT_A_DIRECTORY");
        }

        @Test
        @DisplayName("a directory inside the allowlist holding no Java project")
        void notAJavaProject() {
            // demo/ is the allowed root itself: reachable, but not a project.
            assertRejected("demo", "NOT_A_JAVA_PROJECT");
        }

        @Test
        @DisplayName("a blank path, as a validation failure carrying field detail")
        void blankPath() {
            var json = assertThat(scan("")).hasStatus(HttpStatus.BAD_REQUEST).bodyJson();

            json.extractingPath("$.error").isEqualTo("VALIDATION_FAILED");
            json.extractingPath("$.details").asArray().containsExactly("path: path is required");
        }

        @Test
        @DisplayName("a missing path field")
        void missingPath() {
            assertRejected(null, "VALIDATION_FAILED");
        }
    }
}

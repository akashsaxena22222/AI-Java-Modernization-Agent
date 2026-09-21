package com.company.modernizer.scanner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.company.modernizer.config.ModernizerProperties;
import com.company.modernizer.model.BuildTool;
import com.company.modernizer.model.FileKind;
import com.company.modernizer.model.ProjectContext;
import com.company.modernizer.model.ScannedFile;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the single-pass inventory walk.
 *
 * <p>Two halves, on purpose. {@link Limits} builds throwaway trees so each cap can be driven
 * deterministically. {@link DemoFixture} scans the committed {@code demo/legacy-sample-app}, which
 * is the same thing the README tells a reader to curl - so the demo and the test suite cannot drift
 * apart without one of them failing.
 */
class ProjectScannerTest {

    @TempDir
    Path root;

    private ProjectContext scanWith(ModernizerProperties properties, Path target) {
        return new ProjectScanner(properties, new FileClassifier(), new BuildFileParser(), new FrameworkCatalog()).scan(target);
    }

    private ProjectContext scan(ModernizerProperties properties) {
        return scanWith(properties, root);
    }

    private Path write(String relativePath, String content) throws IOException {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private static ScannedFile fileNamed(ProjectContext context, String relativePath) {
        return context.files().stream()
                .filter(f -> f.relativePath().equals(relativePath))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Not inventoried: " + relativePath + " (have "
                                + context.files().stream().map(ScannedFile::relativePath).toList()
                                + ")"));
    }

    // -------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("inventory")
    class Inventory {

        @Test
        @DisplayName("reports project-relative, forward-slashed paths so output is identical on every platform")
        void pathsAreRelativeAndForwardSlashed() throws IOException {
            write("pom.xml", "<project/>");
            write("src/main/java/com/acme/Order.java", "package com.acme;\n");

            ProjectContext context = scan(ScanProperties.defaults().build());

            assertThat(context.files()).extracting(ScannedFile::relativePath)
                    .containsExactlyInAnyOrder("pom.xml", "src/main/java/com/acme/Order.java");
            assertThat(context.files()).allSatisfy(
                    file -> assertThat(file.relativePath()).doesNotContain("\\"));
        }

        @Test
        @DisplayName("extracts the import block and top-level annotations, which drive most legacy detection")
        void extractsImportsAndTypeAnnotations() throws IOException {
            write("src/main/java/com/acme/Svc.java", """
                    package com.acme;

                    import javax.jws.WebService;
                    import javax.persistence.Entity;
                    import static java.util.Collections.emptyList;

                    /**
                     * Javadoc, not an annotation.
                     */
                    @WebService(targetNamespace = "http://acme.test/")
                    @Deprecated
                    public class Svc {

                        @Autowired
                        private Dep dep;

                        @Override
                        public String toString() {
                            return "svc";
                        }
                    }
                    """);

            ScannedFile svc = fileNamed(scan(ScanProperties.defaults().build()),
                    "src/main/java/com/acme/Svc.java");

            assertThat(svc.imports()).containsExactly(
                    "javax.jws.WebService", "javax.persistence.Entity",
                    "java.util.Collections.emptyList");

            // Only annotations above the first type declaration. A member annotation such as
            // @Autowired must not masquerade as a type-level one, or every Spring bean would look
            // like a SOAP endpoint to an analyzer asking "is this @WebService annotated?".
            assertThat(svc.annotations()).containsExactly("WebService", "Deprecated");
            assertThat(svc.hasAnnotation("WebService")).isTrue();
            assertThat(svc.hasAnnotation("Autowired")).isFalse();
            assertThat(svc.importsPackage("javax.")).isTrue();
        }

        @Test
        @DisplayName("counts blank and comment lines separately, so linesOfCode means code")
        void countsBlanksAndComments() throws IOException {
            // 9 lines: 3 code, 2 blank, 4 comment (the // line plus a three-line block).
            write("src/main/java/A.java", """
                    package p;

                    // a line comment
                    /* a block comment
                       still the block
                    */
                    public class A {

                    }
                    """);

            ScannedFile a = fileNamed(scan(ScanProperties.defaults().build()),
                    "src/main/java/A.java");

            assertThat(a.lineCount()).isEqualTo(9);
            assertThat(a.blankLines()).isEqualTo(2);
            assertThat(a.commentLines()).isEqualTo(4);
            assertThat(a.codeLines()).isEqualTo(3);
        }

        @Test
        @DisplayName("reads a non-UTF-8 source file instead of aborting the scan")
        void tolerantOfLegacyEncodings() throws IOException {
            // 0xE9 is 'e-acute' in ISO-8859-1 and not valid UTF-8 on its own. Legacy codebases are
            // full of this, and a strict decoder would fail on exactly the projects this tool
            // exists to analyze - the demo pom even declares ISO-8859-1 as its source encoding.
            Path file = root.resolve("src/main/java/Accent.java");
            Files.createDirectories(file.getParent());
            byte[] latin1 = "package p;\n// référence client\nclass Accent {}\n"
                    .getBytes(StandardCharsets.ISO_8859_1);
            Files.write(file, latin1);

            ProjectContext context = scan(ScanProperties.defaults().build());

            ScannedFile accent = fileNamed(context, "src/main/java/Accent.java");
            assertThat(accent.kind()).isEqualTo(FileKind.JAVA_SOURCE);
            assertThat(accent.lineCount()).isEqualTo(3);
            assertThat(context.scanStats().warnings()).isEmpty();
        }

        @Test
        @DisplayName("inventories never-read files by name only, with no content-derived metrics")
        void neverReadFilesAreInventoriedNotOpened() throws IOException {
            write("pom.xml", "<project/>");
            Files.write(root.resolve("logo.png"), new byte[] {1, 2, 3, 4, 5});

            ProjectContext context = scan(ScanProperties.defaults().build());

            ScannedFile logo = fileNamed(context, "logo.png");
            assertThat(logo.kind()).isEqualTo(FileKind.NOT_READ);
            assertThat(logo.sizeBytes()).isEqualTo(5L);
            assertThat(logo.lineCount()).isZero();
            assertThat(logo.imports()).isEmpty();

            // Counted as skipped, and no bytes of it were read against the budget.
            assertThat(context.scanStats().filesSkipped()).isEqualTo(1);
            assertThat(context.scanStats().bytesRead()).isEqualTo(10L); // pom.xml only
            assertThat(context.scanStats().warnings()).isEmpty();
        }

        @Test
        @DisplayName("aggregates the headline metrics from the classified inventory")
        void aggregatesMetrics() throws IOException {
            write("pom.xml", "<project/>");
            write("src/main/java/A.java", "package p;\nclass A {}\n");
            write("src/main/java/B.java", "package p;\nclass B {}\n");
            write("src/test/java/ATest.java", "package p;\nclass ATest {}\n");
            write("src/main/webapp/WEB-INF/web.xml", "<web-app/>");
            write("src/main/resources/applicationContext.xml", "<beans/>");
            write("src/main/resources/svc.wsdl", "<definitions/>");
            write("src/main/webapp/list.jsp", "<html/>");

            ProjectContext context = scan(ScanProperties.defaults().build());

            assertThat(context.metrics().javaFiles()).isEqualTo(2);
            assertThat(context.metrics().testFiles()).isEqualTo(1);
            assertThat(context.metrics().xmlConfigFiles()).isEqualTo(2);
            assertThat(context.metrics().wsdlFiles()).isEqualTo(1);
            assertThat(context.metrics().jspFiles()).isEqualTo(1);
            assertThat(context.metrics().linesOfCode()).isEqualTo(6);

            // Left at zero on purpose: dependencies need build-file parsing, which arrives at
            // Step 3 through ProjectContext.withBuildFacts.
            assertThat(context.metrics().declaredDependencies()).isZero();
            assertThat(context.dependencies()).isEmpty();
            assertThat(context.detectedJavaVersion()).isNull();
            assertThat(context.detectedFrameworks()).isEmpty();
        }
    }

    @Nested
    @DisplayName("build tool and modules")
    class Structure {

        @Test
        @DisplayName("detects Maven, Gradle, Ant, or nothing at all from the root build file")
        void detectsBuildTool() throws IOException {
            assertThat(scan(ScanProperties.defaults().build()).buildTool())
                    .isEqualTo(BuildTool.UNKNOWN);

            write("build.xml", "<project/>");
            assertThat(scan(ScanProperties.defaults().build()).buildTool())
                    .isEqualTo(BuildTool.ANT);

            write("build.gradle", "");
            assertThat(scan(ScanProperties.defaults().build()).buildTool())
                    .isEqualTo(BuildTool.GRADLE);

            // Maven wins when several markers are present, matching the priority in detectBuildTool.
            write("pom.xml", "<project/>");
            assertThat(scan(ScanProperties.defaults().build()).buildTool())
                    .isEqualTo(BuildTool.MAVEN);
        }

        @Test
        @DisplayName("reports a single-module project as one module named '.'")
        void singleModule() throws IOException {
            write("pom.xml", "<project/>");

            ProjectContext context = scan(ScanProperties.defaults().build());

            assertThat(context.modules()).containsExactly(".");
            assertThat(context.isMultiModule()).isFalse();
        }

        @Test
        @DisplayName("derives module names from the directories holding a build file")
        void multiModule() throws IOException {
            write("pom.xml", "<project/>");
            write("order-api/pom.xml", "<project/>");
            write("order-core/pom.xml", "<project/>");
            write("order-soap-gateway/build.gradle", "");

            ProjectContext context = scan(ScanProperties.defaults().build());

            assertThat(context.modules()).containsExactlyInAnyOrder(
                    ".", "order-api", "order-core", "order-soap-gateway");
            assertThat(context.isMultiModule()).isTrue();
        }
    }

    @Nested
    @DisplayName("limits")
    class Limits {

        @Test
        @DisplayName("prunes excluded directories instead of descending and filtering afterwards")
        void prunesExcludedDirectories() throws IOException {
            write("pom.xml", "<project/>");
            write("src/main/java/Kept.java", "class Kept {}\n");
            write("target/classes/Generated.java", "class Generated {}\n");
            write("target/nested/deeper/Also.java", "class Also {}\n");
            write("node_modules/pkg/index.js", "module.exports = {};\n");
            write(".git/config", "[core]\n");

            ProjectContext context = scan(ScanProperties.defaults().build());

            assertThat(context.files()).extracting(ScannedFile::relativePath)
                    .containsExactlyInAnyOrder("pom.xml", "src/main/java/Kept.java");
            // Pruned, not skipped: nothing under target/ was ever visited, so it is not counted.
            assertThat(context.scanStats().filesSkipped()).isZero();
            assertThat(context.scanStats().limitsHit()).isFalse();
        }

        @Test
        @DisplayName("matches excluded directories without regard to case")
        void exclusionIsCaseInsensitive() throws IOException {
            write("pom.xml", "<project/>");
            write("Target/Generated.java", "class Generated {}\n");

            ProjectContext context = scan(ScanProperties.defaults().build());

            assertThat(context.files()).extracting(ScannedFile::relativePath)
                    .containsExactly("pom.xml");
        }

        @Test
        @DisplayName("stops at the file cap and says so, rather than reporting on half a project silently")
        void reportsFileCap() throws IOException {
            write("pom.xml", "<project/>");
            for (int i = 0; i < 5; i++) {
                write("src/main/java/C" + i + ".java", "class C" + i + " {}\n");
            }

            ProjectContext context = scan(ScanProperties.defaults().maxFiles(2).build());

            assertThat(context.files()).hasSize(2);
            assertThat(context.scanStats().limitsHit()).isTrue();
            assertThat(context.scanStats().warnings())
                    .anySatisfy(w -> assertThat(w).contains("File limit of 2"));
        }

        @Test
        @DisplayName("stops at the wall-clock timeout and says so")
        void reportsTimeout() throws IOException {
            write("pom.xml", "<project/>");
            write("src/main/java/A.java", "class A {}\n");

            // A zero budget is already spent by the time the first file is visited, which makes
            // the timeout path deterministic instead of a sleep.
            ProjectContext context = scan(
                    ScanProperties.defaults().timeout(java.time.Duration.ZERO).build());

            assertThat(context.files()).isEmpty();
            assertThat(context.scanStats().limitsHit()).isTrue();
            assertThat(context.scanStats().warnings())
                    .anySatisfy(w -> assertThat(w).contains("Scan timeout"));
        }

        @Test
        @DisplayName("stops at the total read budget and says so")
        void reportsTotalByteBudget() throws IOException {
            write("pom.xml", "<project/>");
            write("src/main/java/A.java", "class A {}\n");
            write("src/main/java/B.java", "class B {}\n");

            ProjectContext context = scan(
                    ScanProperties.defaults().maxTotalBytes(DataSize.ofBytes(1)).build());

            // One file fits under a budget checked before reading; the next trips it.
            assertThat(context.files()).hasSize(1);
            assertThat(context.scanStats().warnings())
                    .anySatisfy(w -> assertThat(w).contains("Total read budget"));
        }

        @Test
        @DisplayName("inventories an oversized file by name only and names it in a warning")
        void reportsOversizedFile() throws IOException {
            write("pom.xml", "<project/>");
            write("src/main/java/Huge.java", "class Huge {}\n".repeat(20));

            ProjectContext context = scan(
                    ScanProperties.defaults().maxFileSize(DataSize.ofBytes(64)).build());

            ScannedFile huge = fileNamed(context, "src/main/java/Huge.java");
            assertThat(huge.kind()).isEqualTo(FileKind.NOT_READ);
            assertThat(huge.sizeBytes()).isGreaterThan(64L);
            assertThat(huge.lineCount()).isZero();

            assertThat(context.scanStats().limitsHit()).isTrue();
            assertThat(context.scanStats().warnings()).anySatisfy(w -> assertThat(w)
                    .contains("src/main/java/Huge.java")
                    .contains("file size cap"));

            // The small pom was still read: one oversized file does not abandon the scan.
            assertThat(fileNamed(context, "pom.xml").kind()).isEqualTo(FileKind.MAVEN_POM);
        }

        @Test
        @DisplayName("stops descending at the depth cap and names the cap in a warning")
        void reportsDepthCap() throws IOException {
            write("pom.xml", "<project/>");
            write("src/Shallow.java", "class Shallow {}\n");
            write("src/main/java/Deep.java", "class Deep {}\n");

            ProjectContext context = scan(ScanProperties.defaults().maxDepth(2).build());

            assertThat(context.files()).extracting(ScannedFile::relativePath)
                    .contains("pom.xml", "src/Shallow.java")
                    .doesNotContain("src/main/java/Deep.java");

            assertThat(context.scanStats().limitsHit()).isTrue();
            assertThat(context.scanStats().warnings())
                    .anySatisfy(w -> assertThat(w).contains("depth limit of 2"));
        }
    }

    // -------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the committed demo fixture")
    class DemoFixture {

        /**
         * Resolved against the Surefire working directory, which backend/pom.xml sets to the
         * repository root so that the relative allow-roots in application.yml behave in tests
         * exactly as they do under spring-boot:run.
         */
        private static final Path FIXTURE = Path.of("demo", "legacy-sample-app");

        @BeforeAll
        static void fixtureIsReachable() {
            assertThat(FIXTURE)
                    .as("The demo fixture must be reachable from the Surefire working directory. "
                            + "If this fails, check <workingDirectory> on maven-surefire-plugin "
                            + "in backend/pom.xml. Working directory is: "
                            + Path.of("").toAbsolutePath())
                    .isDirectory();
        }

        private ProjectContext scanFixture() {
            return scanWith(ScanProperties.defaults().build(), FIXTURE.toAbsolutePath());
        }

        @Test
        @DisplayName("scans cleanly within the production limits, hitting no caps")
        void scansWithinProductionLimits() {
            ProjectContext context = scanFixture();

            assertThat(context.projectName()).isEqualTo("legacy-sample-app");
            assertThat(context.buildTool()).isEqualTo(BuildTool.MAVEN);
            assertThat(context.modules()).containsExactly(".");
            assertThat(context.scanStats().filesScanned()).isGreaterThan(10);
            assertThat(context.scanStats().limitsHit())
                    .as("warnings: %s", context.scanStats().warnings())
                    .isFalse();
        }

        @Test
        @DisplayName("exercises every file kind the classifier can assign in a legacy project")
        void coversTheClassifier() {
            Map<FileKind, Long> counts = scanFixture().fileCountsByKind();

            // The point of the fixture: a scan of it must produce a realistically varied
            // inventory, so Step 3's analyzers have something of each kind to interpret.
            assertThat(counts).containsKeys(
                    FileKind.MAVEN_POM,
                    FileKind.JAVA_SOURCE,
                    FileKind.JAVA_TEST,
                    FileKind.WEB_XML,
                    FileKind.SPRING_XML,
                    FileKind.PROPERTIES,
                    FileKind.WSDL,
                    FileKind.XSD,
                    FileKind.JSP,
                    FileKind.NOT_READ,
                    FileKind.OTHER);
            assertThat(counts).doesNotContainKey(FileKind.GRADLE_BUILD);
        }

        @Test
        @DisplayName("finds the planted SOAP endpoint by import and annotation, with no filesystem re-walk")
        void findsPlantedSoapEndpoint() {
            ProjectContext context = scanFixture();

            assertThat(context.filesAnnotatedWith("WebService"))
                    .extracting(ScannedFile::relativePath)
                    .contains("src/main/java/com/acme/orders/OrderService.java");
            assertThat(context.filesImporting("javax.jws")).isNotEmpty();
            assertThat(context.filesImporting("javax.servlet")).isNotEmpty();
        }

        @Test
        @DisplayName("finds the planted JUnit 3 test, the signal behind the test-to-source ratio")
        void findsPlantedJUnit3Test() {
            ProjectContext context = scanFixture();

            List<ScannedFile> tests = context.filesOfKind(FileKind.JAVA_TEST);

            // "One test file for six classes" is a documented planted problem, so this count is
            // the fixture's contract rather than an incidental number.
            assertThat(tests).hasSize(1);
            assertThat(tests.getFirst().importsPackage("junit.framework")).isTrue();
            assertThat(context.metrics().testToSourceRatio()).isLessThan(0.2);
        }

        @Test
        @DisplayName("inventories the binary asset without reading it")
        void doesNotReadTheBinary() {
            ScannedFile logo = fileNamed(scanFixture(), "src/main/webapp/images/acme-logo.png");

            assertThat(logo.kind()).isEqualTo(FileKind.NOT_READ);
            assertThat(logo.lineCount()).isZero();
            assertThat(logo.sizeBytes()).isPositive();
        }

        @Test
        @DisplayName("holds no absolute path anywhere in the inventory")
        void inventoryIsFreeOfAbsolutePaths() {
            ProjectContext context = scanFixture();
            String absoluteRoot = context.rootPath().toString();

            // rootPath is the one absolute path in the model, and ProjectInfo.from drops it before
            // anything reaches HTTP or the LLM (section 9.2). Nothing else may leak one.
            assertThat(context.files()).allSatisfy(file -> assertThat(file.relativePath())
                    .doesNotContain(absoluteRoot)
                    .doesNotStartWith("/")
                    .doesNotContain(":"));
        }
    }
}

package com.company.modernizer.scanner;

import java.util.List;

import com.company.modernizer.model.FileKind;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the by-name classification table.
 *
 * <p>A table-driven suite because that is what the class is: a table. The cases that matter are the
 * ones where two rules could both fire, since classification decides what each analyzer is even
 * shown - a misclassified {@code pom.xml} makes the whole dependency analysis disappear.
 */
class FileClassifierTest {

    private final FileClassifier classifier = new FileClassifier();

    @Nested
    @DisplayName("classifies")
    class Classifies {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                // Build files
                "pom.xml,                                       MAVEN_POM",
                "order-core/pom.xml,                            MAVEN_POM",
                "build.gradle,                                  GRADLE_BUILD",
                "build.gradle.kts,                              GRADLE_BUILD",
                "settings.gradle,                               GRADLE_BUILD",
                "settings.gradle.kts,                           GRADLE_BUILD",
                "build.xml,                                     ANT_BUILD",

                // Ant layout: a top-level test/ source root, with no src/main split
                "src/com/acme/payroll/EmployeeDao.java,         JAVA_SOURCE",
                "test/com/acme/payroll/PayrollUtilsTest.java,   JAVA_TEST",
                "tests/com/acme/SmokeTest.java,                 JAVA_TEST",

                // Java, split by source root rather than by class name
                "src/main/java/com/acme/Order.java,             JAVA_SOURCE",
                "src/test/java/com/acme/OrderTest.java,         JAVA_TEST",
                "src/it/java/com/acme/OrderIT.java,             JAVA_TEST",
                "src/integration-test/java/com/acme/X.java,     JAVA_TEST",
                "module/src/test/java/com/acme/OrderTest.java,  JAVA_TEST",

                // Web and XML
                "src/main/webapp/WEB-INF/web.xml,               WEB_XML",
                "src/main/resources/applicationContext.xml,     SPRING_XML",
                "src/main/resources/application-context.xml,    SPRING_XML",
                "src/main/resources/spring-security.xml,        SPRING_XML",
                "src/main/webapp/WEB-INF/dispatcher-servlet.xml,SPRING_XML",
                "src/main/resources/beans.xml,                  SPRING_XML",
                "src/main/resources/mvc-config.xml,             SPRING_XML",

                // SOAP contracts
                "src/main/resources/wsdl/OrderService.wsdl,     WSDL",
                "src/main/resources/wsdl/order-types.xsd,       XSD",

                // View layer
                "src/main/webapp/WEB-INF/jsp/order-list.jsp,    JSP",
                "src/main/webapp/list.jspx,                     JSP",
                "src/main/webapp/fragment.jspf,                 JSP",
                "src/main/webapp/WEB-INF/tags/money.tag,        JSP",

                // Configuration
                "src/main/resources/application.properties,     PROPERTIES",
                "src/main/resources/application.yml,            YAML",
                "src/main/resources/application.yaml,           YAML",

                // Containers and pipelines
                "Dockerfile,                                    DOCKERFILE",
                "Dockerfile.prod,                               DOCKERFILE",
                "prod.dockerfile,                               DOCKERFILE",
                "Jenkinsfile,                                   CI_CONFIG",
                ".gitlab-ci.yml,                                CI_CONFIG",
                ".github/workflows/build.yml,                   CI_CONFIG",
                ".circleci/config.yml,                          CI_CONFIG",
                "azure-pipelines.yml,                           CI_CONFIG",

                // Everything else is inventoried, not ignored
                "README.md,                                     OTHER",
                "src/main/webapp/css/legacy.css,                OTHER",
                "LICENSE,                                       OTHER",
                "src/main/resources/data.sql,                   OTHER",
        })
        void byNameAndPath(String relativePath, FileKind expected) {
            assertThat(classifier.classify(relativePath)).isEqualTo(expected);
        }

        @Test
        @DisplayName("regardless of filename case, since Windows and Linux trees differ")
        void caseInsensitive() {
            assertThat(classifier.classify("POM.XML")).isEqualTo(FileKind.MAVEN_POM);
            assertThat(classifier.classify("src/main/java/Order.JAVA")).isEqualTo(FileKind.JAVA_SOURCE);
            assertThat(classifier.classify("src/main/webapp/WEB-INF/WEB.XML")).isEqualTo(FileKind.WEB_XML);
            assertThat(classifier.classify("SRC/TEST/java/OrderTest.java")).isEqualTo(FileKind.JAVA_TEST);
        }

        @Test
        @DisplayName("a package literally named 'test' under src/main as source, not as tests")
        void topLevelTestRootDoesNotOverMatch() {
            // The Ant rule is anchored to the start of the path. A contains("/test/") would
            // capture this production package and inflate the test-to-source ratio.
            assertThat(classifier.classify("src/main/java/com/acme/test/Helper.java"))
                    .isEqualTo(FileKind.JAVA_SOURCE);
            assertThat(classifier.classify("src/com/acme/testing/Fixtures.java"))
                    .isEqualTo(FileKind.JAVA_SOURCE);
        }

        @Test
        @DisplayName("a production class named like a test as a source, not a test")
        void doesNotGuessTestsFromClassNames() {
            // Misclassifying this would inflate testToSourceRatio, one of the report's headline
            // signals, and would do so in the flattering direction.
            assertThat(classifier.classify("src/main/java/com/acme/TestDataLoader.java"))
                    .isEqualTo(FileKind.JAVA_SOURCE);
            assertThat(classifier.classify("src/main/java/com/acme/OrderTestSupport.java"))
                    .isEqualTo(FileKind.JAVA_SOURCE);
        }
    }

    @Nested
    @DisplayName("refuses to read")
    class NeverRead {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                // Credential material: not read, not hashed, not sampled (section 9.2)
                "src/main/resources/keystore.jks",
                "src/main/resources/server.p12",
                "src/main/resources/client.pfx",
                "certs/server.pem",
                "certs/ca.crt",
                "certs/signing.key",
                "config/app.truststore",
                // Bare, extensionless spellings: caught by name, not by extension.
                "config/keystore",
                "config/truststore",
                ".env",
                ".env.production",
                ".netrc",
                ".pgpass",
                "credentials",
                ".htpasswd",
                "ssh/id_rsa",
                "ssh/id_rsa.pub",
                "ssh/id_ed25519",

                // Binaries and archives carry no analyzable text
                "lib/legacy-util.jar",
                "target/orders.war",
                "build/Order.class",
                "lib/native.so",
                "lib/native.dll",
                "dist/bundle.zip",
                "dist/backup.tar.gz",
                "src/main/webapp/images/acme-logo.png",
                "docs/architecture.pdf",
                "docs/requirements.docx",
        })
        void neverReadFiles(String relativePath) {
            assertThat(classifier.classify(relativePath)).isEqualTo(FileKind.NOT_READ);
        }

        @Test
        @DisplayName("credential material even when another rule would otherwise claim it")
        void credentialRulesWinOverEverythingElse() {
            // '.key' is checked before the properties/yaml/xml rules, so a file cannot smuggle
            // itself into the read set by choosing a friendly-looking name.
            assertThat(classifier.classify("config/private.key")).isEqualTo(FileKind.NOT_READ);
            assertThat(classifier.classify("src/main/resources/spring-beans.jar"))
                    .isEqualTo(FileKind.NOT_READ);
        }
    }

    @Nested
    @DisplayName("precedence")
    class Precedence {

        @Test
        @DisplayName("prefers the more specific rule when two could fire")
        void specificWins() {
            // .xml, but a pom first.
            assertThat(classifier.classify("pom.xml")).isEqualTo(FileKind.MAVEN_POM);
            // .yml, but CI configuration first.
            assertThat(classifier.classify(".gitlab-ci.yml")).isEqualTo(FileKind.CI_CONFIG);
            assertThat(classifier.classify(".github/workflows/release.yaml"))
                    .isEqualTo(FileKind.CI_CONFIG);
            // Named web.xml, so not merely 'some Spring XML'.
            assertThat(classifier.classify("WEB-INF/web.xml")).isEqualTo(FileKind.WEB_XML);
            // An .xml with no Spring hint in its name stays generic rather than being assumed.
            assertThat(classifier.classify("src/main/resources/logback.xml"))
                    .isEqualTo(FileKind.OTHER);
        }
    }

    @Nested
    @DisplayName("directory pruning")
    class DirectoryPruning {

        private final List<String> excluded = ScanProperties.DEFAULT_EXCLUDED_DIRECTORIES;

        @Test
        @DisplayName("excludes the configured build and tooling directories")
        void excludesConfigured() {
            assertThat(classifier.isExcludedDirectory("target", excluded)).isTrue();
            assertThat(classifier.isExcludedDirectory("node_modules", excluded)).isTrue();
            assertThat(classifier.isExcludedDirectory(".git", excluded)).isTrue();
        }

        @Test
        @DisplayName("matches without regard to case, because Windows trees are not case-sensitive")
        void caseInsensitive() {
            assertThat(classifier.isExcludedDirectory("TARGET", excluded)).isTrue();
            assertThat(classifier.isExcludedDirectory("Build", excluded)).isTrue();
        }

        @Test
        @DisplayName("keeps directories that merely resemble an excluded one")
        void doesNotOverMatch() {
            // Pruning 'target-classes' or 'src' would silently drop real source from the report.
            assertThat(classifier.isExcludedDirectory("target-classes", excluded)).isFalse();
            assertThat(classifier.isExcludedDirectory("src", excluded)).isFalse();
            assertThat(classifier.isExcludedDirectory("buildSrc", excluded)).isFalse();
        }
    }
}

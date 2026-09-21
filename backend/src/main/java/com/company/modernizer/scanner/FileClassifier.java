package com.company.modernizer.scanner;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.company.modernizer.model.FileKind;

import org.springframework.stereotype.Component;

/**
 * Assigns a {@link FileKind} to every file, by name and path only.
 *
 * <p>Purely mechanical - it never opens a file. Classification decides what an analyzer will
 * <em>look at</em>; deciding what a file <em>means</em> is the analyzer's job
 * (ARCHITECTURE.md section 8, stage 3).
 *
 * <p>Order matters in {@link #classify}: CI configuration is checked before generic YAML, and
 * {@code web.xml} before generic Spring XML, because the more specific rule must win.
 */
@Component
public class FileClassifier {

    /**
     * Extensions we never read. Binary formats carry no analyzable text, and credential formats
     * must not be opened at all - not read, not hashed, not sampled (section 9.2).
     */
    private static final Set<String> NEVER_READ_EXTENSIONS = Set.of(
            // Archives and binaries
            "jar", "war", "ear", "class", "so", "dll", "dylib", "exe", "bin", "dat", "o", "a",
            "zip", "tar", "gz", "tgz", "bz2", "7z", "rar",
            // Images, media, fonts
            "png", "jpg", "jpeg", "gif", "bmp", "ico", "tif", "tiff", "webp",
            "mp3", "mp4", "avi", "mov", "woff", "woff2", "ttf", "eot", "otf",
            // Documents
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            // Credential material
            "pem", "jks", "p12", "pfx", "keystore", "truststore", "key", "crt", "cer", "der", "asc");

    /**
     * Filenames that are credential material regardless of extension.
     *
     * <p>{@code keystore} and {@code truststore} appear here as well as in
     * {@link #NEVER_READ_EXTENSIONS} because both spellings occur: {@code app.keystore} is caught by
     * extension, while a bare {@code keystore} has none and would otherwise fall through to being
     * read as text.
     */
    private static final Set<String> NEVER_READ_NAMES = Set.of(
            ".env", "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519", ".netrc", ".pgpass",
            "credentials", ".htpasswd", "keystore", "truststore");

    private static final Set<String> CI_FILENAMES = Set.of(
            "jenkinsfile", ".gitlab-ci.yml", ".gitlab-ci.yaml", "azure-pipelines.yml",
            "bitbucket-pipelines.yml", ".travis.yml", "appveyor.yml", "cloudbuild.yaml");

    private static final Set<String> GRADLE_FILENAMES = Set.of(
            "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts");

    /**
     * Filename fragments that indicate a Spring bean-definition XML. Name-based on purpose - this
     * classifier does not open files, so it cannot look for a {@code <beans>} root element.
     */
    private static final List<String> SPRING_XML_HINTS = List.of(
            "applicationcontext", "application-context", "spring", "beans", "-servlet",
            "dispatcher", "mvc-config", "security-config");

    /**
     * Classifies a file from its project-relative, forward-slashed path.
     *
     * @param relativePath e.g. {@code "src/test/java/com/acme/OrderTest.java"}
     */
    public FileKind classify(String relativePath) {
        String path = relativePath.toLowerCase(Locale.ROOT);
        String fileName = fileName(path);
        String extension = extension(fileName);

        // Credential and binary material first: these must never be read, whatever else they
        // might look like.
        if (isNeverRead(fileName, extension)) {
            return FileKind.NOT_READ;
        }

        if (fileName.equals("pom.xml")) {
            return FileKind.MAVEN_POM;
        }
        if (GRADLE_FILENAMES.contains(fileName)) {
            return FileKind.GRADLE_BUILD;
        }
        // Before the generic .xml rules: an Ant build file is a build file first.
        if (fileName.equals("build.xml")) {
            return FileKind.ANT_BUILD;
        }
        if (fileName.equals("dockerfile") || extension.equals("dockerfile")
                || fileName.startsWith("dockerfile.")) {
            return FileKind.DOCKERFILE;
        }
        if (isCiConfig(path, fileName)) {
            return FileKind.CI_CONFIG;
        }

        if (extension.equals("java")) {
            return isTestPath(path) ? FileKind.JAVA_TEST : FileKind.JAVA_SOURCE;
        }
        if (extension.equals("wsdl")) {
            return FileKind.WSDL;
        }
        if (extension.equals("xsd")) {
            return FileKind.XSD;
        }
        if (extension.equals("jsp") || extension.equals("jspx") || extension.equals("jspf")
                || extension.equals("tag") || extension.equals("tagx")) {
            return FileKind.JSP;
        }
        if (fileName.equals("web.xml")) {
            return FileKind.WEB_XML;
        }
        if (extension.equals("xml") && isSpringXml(fileName)) {
            return FileKind.SPRING_XML;
        }
        if (extension.equals("properties")) {
            return FileKind.PROPERTIES;
        }
        if (extension.equals("yml") || extension.equals("yaml")) {
            return FileKind.YAML;
        }

        return FileKind.OTHER;
    }

    /** Whether a directory should be pruned - not descended into at all. */
    public boolean isExcludedDirectory(String directoryName, List<String> excluded) {
        return excluded.stream().anyMatch(e -> e.equalsIgnoreCase(directoryName));
    }

    private boolean isNeverRead(String fileName, String extension) {
        if (NEVER_READ_EXTENSIONS.contains(extension) || NEVER_READ_NAMES.contains(fileName)) {
            return true;
        }
        // id_rsa.pub, id_ed25519.pub, .env.production, .env.local
        return NEVER_READ_NAMES.stream().anyMatch(n -> fileName.startsWith(n + "."));
    }

    private boolean isCiConfig(String path, String fileName) {
        return CI_FILENAMES.contains(fileName)
                || path.contains(".github/workflows/")
                || path.contains(".circleci/");
    }

    /**
     * Whether the file sits on a test source path.
     *
     * <p>Matches the Maven and Gradle conventions rather than guessing from the class name, so a
     * production class called {@code TestDataLoader} is not miscounted as a test - which would
     * quietly inflate the test-to-source ratio, one of the report's headline signals.
     */
    private boolean isTestPath(String path) {
        return path.contains("src/test/")
                || path.contains("src/it/")
                || path.contains("src/integration-test/")
                || path.contains("/test/java/")
                // Ant convention: a top-level test/ source root, with no src/main split. Anchored
                // to the start of the path on purpose - a bare contains("/test/") would also
                // capture src/main/java/com/acme/test/Helper.java, a production package.
                || path.startsWith("test/")
                || path.startsWith("tests/");
    }

    private boolean isSpringXml(String fileName) {
        return SPRING_XML_HINTS.stream().anyMatch(fileName::contains);
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1 ? "" : fileName.substring(dot + 1);
    }
}

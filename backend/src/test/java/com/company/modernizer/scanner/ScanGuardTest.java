package com.company.modernizer.scanner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.company.modernizer.scanner.ScanException.Reason;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the scan security boundary.
 *
 * <p>This is the suite ARCHITECTURE.md section 8 stage 1 calls for. The scan path arrives over
 * HTTP, so these are not input-validation niceties - each rejection below is the difference between
 * a project analyzer and an arbitrary-directory reader with a JSON API.
 *
 * <p>Every expectation is asserted on {@link Reason}, never on message wording, so the tests pin
 * behaviour rather than prose.
 */
class ScanGuardTest {

    @TempDir
    Path sandbox;

    /** The one directory scans are permitted under. */
    private Path allowedRoot;

    /** A valid Maven project inside the allowlist: the happy path. */
    private Path project;

    /**
     * A valid Maven project <em>outside</em> the allowlist.
     *
     * <p>Deliberately a real project rather than an empty directory: if the guard rejects it, the
     * allowlist is demonstrably what did so, not an incidentally missing project marker.
     */
    private Path outside;

    @BeforeEach
    void layOutSandbox() throws IOException {
        allowedRoot = Files.createDirectories(sandbox.resolve("allowed"));

        project = Files.createDirectories(allowedRoot.resolve("legacy-app"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");

        outside = Files.createDirectories(sandbox.resolve("secret"));
        Files.writeString(outside.resolve("pom.xml"), "<project/>");
    }

    private ScanGuard guardAllowing(Path... roots) {
        return new ScanGuard(ScanProperties.allowing(roots));
    }

    private void assertRejected(ScanGuard guard, String path, Reason expected) {
        assertThatThrownBy(() -> guard.validate(path))
                .isInstanceOf(ScanException.class)
                .extracting(e -> ((ScanException) e).reason())
                .isEqualTo(expected);
    }

    @Nested
    @DisplayName("accepts")
    class Accepts {

        @Test
        @DisplayName("a Maven project inside an allowed root, returning the canonicalized real path")
        void projectInsideAllowedRoot() throws IOException {
            Path validated = guardAllowing(allowedRoot).validate(project.toString());

            // Compared against toRealPath(), not the raw temp path: on Windows and macOS the
            // temporary directory is itself reached through a symlink or a short (8.3) name, so
            // the two differ and only the real path is a stable expectation.
            assertThat(validated).isEqualTo(project.toRealPath());
        }

        @Test
        @DisplayName("an allowed root that is itself the project, since startsWith holds for equal paths")
        void rootItself() throws IOException {
            assertThat(guardAllowing(project).validate(project.toString()))
                    .isEqualTo(project.toRealPath());
        }

        @Test
        @DisplayName("a project recognized by src/ alone, so a Gradle-less or Ant-less tree still scans")
        void srcDirectoryIsEnoughOfAMarker() throws IOException {
            Path sourcesOnly = Files.createDirectories(allowedRoot.resolve("sources-only/src"));

            assertThat(guardAllowing(allowedRoot).validate(sourcesOnly.getParent().toString()))
                    .isEqualTo(sourcesOnly.getParent().toRealPath());
        }

        @ParameterizedTest(name = "a Gradle or Ant project marked by {0}")
        @ValueSource(strings = {"build.gradle", "build.gradle.kts", "build.xml"})
        @DisplayName("projects of other build tools, which are reported rather than silently ignored")
        void otherBuildTools(String marker) throws IOException {
            Path other = Files.createDirectories(allowedRoot.resolve("other-" + marker));
            Files.writeString(other.resolve(marker), "");

            assertThat(guardAllowing(allowedRoot).validate(other.toString())).isNotNull();
        }

        @Test
        @DisplayName("a messy but legitimate path, collapsing redundant segments")
        void canonicalizesRedundantSegments() throws IOException {
            String messy = allowedRoot.resolve(".").resolve("legacy-app").resolve(".").toString();

            assertThat(guardAllowing(allowedRoot).validate(messy)).isEqualTo(project.toRealPath());
        }

        @Test
        @DisplayName("a project under any one of several configured roots")
        void severalRoots() throws IOException {
            Path secondRoot = Files.createDirectories(sandbox.resolve("also-allowed"));
            Path elsewhere = Files.createDirectories(secondRoot.resolve("another-app"));
            Files.writeString(elsewhere.resolve("pom.xml"), "<project/>");

            ScanGuard guard = guardAllowing(allowedRoot, secondRoot);

            assertThat(guard.validate(project.toString())).isEqualTo(project.toRealPath());
            assertThat(guard.validate(elsewhere.toString())).isEqualTo(elsewhere.toRealPath());
        }

        @Test
        @DisplayName("a configured root that does not exist, as long as another one does")
        void tolerantOfOneMissingRoot() throws IOException {
            ScanGuard guard = guardAllowing(sandbox.resolve("never-created"), allowedRoot);

            assertThat(guard.validate(project.toString())).isEqualTo(project.toRealPath());
        }
    }

    @Nested
    @DisplayName("fails closed")
    class FailsClosed {

        @Test
        @DisplayName("refusing every scan when no allowed roots are configured")
        void emptyAllowlistRefusesEverything() {
            assertRejected(guardAllowing(), project.toString(), Reason.ALLOWLIST_EMPTY);
        }

        @Test
        @DisplayName("refusing every scan when none of the configured roots exist")
        void unresolvableAllowlistRefusesEverything() {
            ScanGuard guard = guardAllowing(
                    sandbox.resolve("never-created"), sandbox.resolve("also-never-created"));

            // Not OUTSIDE_ALLOWED_ROOTS: an allowlist that resolves to nothing is a server
            // misconfiguration, and conflating it with a bad request would hide that.
            assertRejected(guard, project.toString(), Reason.ALLOWLIST_EMPTY);
        }

        @Test
        @DisplayName("checking the allowlist before deciding whether the target even looks like a project")
        void allowlistIsCheckedBeforeProjectMarkers() throws IOException {
            Path junk = Files.createDirectories(sandbox.resolve("not-a-project"));

            // Outside the allowlist AND not a project. The allowlist must be the reported reason,
            // so a probing caller cannot use the error to learn what exists out there.
            assertRejected(guardAllowing(allowedRoot), junk.toString(), Reason.OUTSIDE_ALLOWED_ROOTS);
        }
    }

    @Nested
    @DisplayName("rejects traversal")
    class RejectsTraversal {

        @Test
        @DisplayName("a .. sequence that climbs out of the allowed root")
        void dotDotEscape() {
            String traversal = project.resolve("..").resolve("..").resolve("secret").toString();

            assertRejected(guardAllowing(allowedRoot), traversal, Reason.OUTSIDE_ALLOWED_ROOTS);
        }

        @Test
        @DisplayName("an absolute path to a sibling of the allowed root")
        void siblingDirectory() {
            assertRejected(guardAllowing(allowedRoot), outside.toString(),
                    Reason.OUTSIDE_ALLOWED_ROOTS);
        }

        @Test
        @DisplayName("a symlink inside the allowed root that points out of it, because real paths are compared")
        void symlinkEscape() {
            Path shortcut = allowedRoot.resolve("shortcut");
            try {
                Files.createSymbolicLink(shortcut, outside);
            } catch (IOException | UnsupportedOperationException e) {
                // Windows requires elevation or developer mode to create symlinks. Skipping is
                // honest; quietly passing would claim coverage this machine did not provide.
                Assumptions.abort("Cannot create symlinks on this machine: " + e.getMessage());
            }

            assertRejected(guardAllowing(allowedRoot), shortcut.toString(),
                    Reason.OUTSIDE_ALLOWED_ROOTS);
        }

        @Test
        @DisplayName("a directory whose name merely starts with the allowed root's name")
        void prefixCollisionIsNotContainment() throws IOException {
            // 'allowed-extra' shares a string prefix with 'allowed' but is not inside it. Path
            // containment must be by name element, not by string prefix - the bug this pins.
            Path lookalike = Files.createDirectories(sandbox.resolve("allowed-extra"));
            Files.writeString(lookalike.resolve("pom.xml"), "<project/>");

            assertRejected(guardAllowing(allowedRoot), lookalike.toString(),
                    Reason.OUTSIDE_ALLOWED_ROOTS);
        }

        @Test
        @DisplayName("without echoing the resolved target back to the caller")
        void doesNotDiscloseTheResolvedTarget() {
            assertThatThrownBy(() -> guardAllowing(allowedRoot).validate(outside.toString()))
                    .isInstanceOf(ScanException.class)
                    .hasMessageNotContaining(outside.getFileName().toString());
        }
    }

    @Nested
    @DisplayName("rejects unusable targets")
    class RejectsUnusableTargets {

        @ParameterizedTest(name = "a {0} path")
        @NullAndEmptySource
        @ValueSource(strings = {" ", "\t"})
        @DisplayName("a missing or blank path, before the allowlist is even consulted")
        void blankPath(String path) {
            assertRejected(guardAllowing(allowedRoot), path, Reason.PATH_REQUIRED);
        }

        @Test
        @DisplayName("a path that does not exist")
        void nonExistentPath() {
            assertRejected(guardAllowing(allowedRoot),
                    allowedRoot.resolve("no-such-project").toString(), Reason.NOT_FOUND);
        }

        @Test
        @DisplayName("a file where a directory was expected, rather than returning an empty report")
        void fileInsteadOfDirectory() {
            assertRejected(guardAllowing(allowedRoot), project.resolve("pom.xml").toString(),
                    Reason.NOT_A_DIRECTORY);
        }

        @Test
        @DisplayName("a directory inside the allowlist that contains no project marker")
        void directoryWithoutProjectMarker() throws IOException {
            Path empty = Files.createDirectories(allowedRoot.resolve("just-a-folder"));
            Files.writeString(empty.resolve("notes.txt"), "nothing to build here");

            assertRejected(guardAllowing(allowedRoot), empty.toString(),
                    Reason.NOT_A_JAVA_PROJECT);
        }
    }

    @Nested
    @DisplayName("Reason")
    class Reasons {

        @Test
        @DisplayName("attributes caller-fixable causes to the caller and configuration causes to us")
        void statusMapping() {
            assertThat(Reason.OUTSIDE_ALLOWED_ROOTS.isClientError()).isTrue();
            assertThat(Reason.NOT_A_JAVA_PROJECT.isClientError()).isTrue();
            assertThat(Reason.NOT_FOUND.isClientError()).isTrue();

            // An empty allowlist is a deployment mistake, not a bad request. Reporting it as 400
            // would send an operator hunting through their curl command instead of their config.
            assertThat(Reason.ALLOWLIST_EMPTY.isClientError()).isFalse();
            assertThat(Reason.SCAN_FAILED.isClientError()).isFalse();
        }
    }
}

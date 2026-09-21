package com.company.modernizer.scanner;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import com.company.modernizer.config.ModernizerProperties;
import com.company.modernizer.config.ModernizerProperties.Ai;
import com.company.modernizer.config.ModernizerProperties.Scan;

import org.springframework.util.unit.DataSize;

/**
 * Builds {@link ModernizerProperties} for scanner tests without starting Spring.
 *
 * <p>The defaults here mirror application.yml deliberately. A test that tightens one limit is then
 * unambiguous about what it is exercising, and a drift between this builder and the shipped defaults
 * shows up as a failing assertion in {@code ModernizerApplicationTests} rather than as tests that
 * quietly stop reflecting production behaviour.
 */
final class ScanProperties {

    /** Same list as application.yml, so pruning tests exercise the real exclusion set. */
    static final List<String> DEFAULT_EXCLUDED_DIRECTORIES =
            List.of("target", "build", "out", "bin", ".git", ".idea", "node_modules", ".mvn");

    private List<String> allowedRoots = List.of();
    private List<String> excludedDirectories = DEFAULT_EXCLUDED_DIRECTORIES;
    private int maxDepth = 25;
    private int maxFiles = 20_000;
    private DataSize maxFileSize = DataSize.ofMegabytes(1);
    private DataSize maxTotalBytes = DataSize.ofMegabytes(200);
    private Duration timeout = Duration.ofSeconds(60);

    private ScanProperties() {
    }

    static ScanProperties defaults() {
        return new ScanProperties();
    }

    ScanProperties allowedRoots(Path... roots) {
        this.allowedRoots = Arrays.stream(roots).map(Path::toString).toList();
        return this;
    }

    ScanProperties excludedDirectories(String... directories) {
        this.excludedDirectories = List.of(directories);
        return this;
    }

    ScanProperties maxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
        return this;
    }

    ScanProperties maxFiles(int maxFiles) {
        this.maxFiles = maxFiles;
        return this;
    }

    ScanProperties maxFileSize(DataSize maxFileSize) {
        this.maxFileSize = maxFileSize;
        return this;
    }

    ScanProperties maxTotalBytes(DataSize maxTotalBytes) {
        this.maxTotalBytes = maxTotalBytes;
        return this;
    }

    ScanProperties timeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    ModernizerProperties build() {
        return new ModernizerProperties(
                "0.1.0-test",
                new Scan(allowedRoots, excludedDirectories, maxDepth, maxFiles,
                        maxFileSize, maxTotalBytes, timeout),
                // The scanner never reads the ai block; a disabled mock provider keeps it honest.
                new Ai(false, "mock", "mock-model", "LOW", false,
                        1_000, 1_000, 40, 200, Duration.ofSeconds(1)));
    }

    /** Shorthand for the common case: production limits, allowlist pointed at the given roots. */
    static ModernizerProperties allowing(Path... roots) {
        return defaults().allowedRoots(roots).build();
    }
}

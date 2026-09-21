package com.company.modernizer.scanner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.company.modernizer.config.ModernizerProperties;
import com.company.modernizer.scanner.ScanException.Reason;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Validates a requested scan target before anything reads the filesystem.
 *
 * <p><strong>This is a security boundary, not a formality.</strong> The scan path arrives over
 * HTTP, so without this class {@code POST {"path": "C:\\Users"}} would happily inventory the
 * user's home directory and ship the result to an LLM.
 *
 * <p>The design rule is <em>fail closed</em>: an unconfigured or unresolvable allowlist refuses
 * every scan rather than falling back to permissive behaviour.
 *
 * @see ARCHITECTURE.md section 8, stage 1
 */
@Component
public class ScanGuard {

    private static final Logger log = LoggerFactory.getLogger(ScanGuard.class);

    /** Any one of these marks a directory as plausibly a Java project. */
    private static final List<String> PROJECT_MARKERS =
            List.of("pom.xml", "build.gradle", "build.gradle.kts", "build.xml", "src");

    private final ModernizerProperties properties;

    public ScanGuard(ModernizerProperties properties) {
        this.properties = properties;
    }

    /**
     * Validates the requested path and returns it canonicalized.
     *
     * <p>The returned path is the <em>real</em> path - symlinks resolved, {@code ..} collapsed - so
     * callers can rely on it staying inside an allowed root.
     *
     * @throws ScanException if the path is missing, unusable, outside the allowlist, or not a Java
     *                       project
     */
    public Path validate(String requestedPath) {
        if (requestedPath == null || requestedPath.isBlank()) {
            throw new ScanException(Reason.PATH_REQUIRED, "A project path is required.");
        }

        List<Path> allowedRoots = resolvedAllowedRoots();
        if (allowedRoots.isEmpty()) {
            throw new ScanException(Reason.ALLOWLIST_EMPTY,
                    "Scanning is disabled: modernizer.scan.allowed-roots is empty or none of its "
                            + "entries exist. Configure at least one readable directory.");
        }

        Path target = canonicalize(requestedPath);

        if (!Files.isDirectory(target)) {
            throw new ScanException(Reason.NOT_A_DIRECTORY,
                    "Not a directory: " + requestedPath);
        }
        if (!Files.isReadable(target)) {
            throw new ScanException(Reason.NOT_READABLE,
                    "Directory is not readable: " + requestedPath);
        }

        if (!isUnderAnyRoot(target, allowedRoots)) {
            // The message names the roots but never echoes the resolved target, so a probing
            // caller learns nothing about the host's directory layout from the error alone.
            throw new ScanException(Reason.OUTSIDE_ALLOWED_ROOTS,
                    "Path is outside the configured allowed roots. Allowed: " + allowedRoots);
        }

        if (!looksLikeJavaProject(target)) {
            throw new ScanException(Reason.NOT_A_JAVA_PROJECT,
                    "No Java project found at that path - expected one of " + PROJECT_MARKERS + ".");
        }

        log.info("Scan target accepted: {}", target);
        return target;
    }

    /**
     * Resolves and canonicalizes the configured allowed roots, dropping any that do not exist.
     *
     * <p>A configured root that is missing is a misconfiguration worth a warning, but it is not
     * fatal on its own - it simply cannot match anything. It becomes fatal only when it leaves the
     * allowlist empty, which {@link #validate} treats as "refuse everything".
     */
    private List<Path> resolvedAllowedRoots() {
        List<Path> roots = new ArrayList<>();
        for (String configured : properties.scan().allowedRoots()) {
            if (configured == null || configured.isBlank()) {
                continue;
            }
            try {
                roots.add(canonicalize(configured));
            } catch (ScanException e) {
                log.warn("Configured allowed root is unusable and will be ignored: {} ({})",
                        configured, e.getMessage());
            }
        }
        return roots;
    }

    /**
     * Turns a possibly-relative, possibly-messy path string into a real path.
     *
     * <p>{@link Path#toRealPath} is what does the security-relevant work: it collapses {@code ..}
     * segments, resolves symlinks, and normalizes Windows short (8.3) names, so the subsequent
     * prefix check cannot be fooled by a path that merely <em>looks</em> like it is inside an
     * allowed root.
     */
    private Path canonicalize(String path) {
        try {
            return Path.of(path).toAbsolutePath().toRealPath();
        } catch (InvalidPathException e) {
            throw new ScanException(Reason.INVALID_PATH, "Not a valid path: " + path, e);
        } catch (IOException e) {
            throw new ScanException(Reason.NOT_FOUND, "Path does not exist: " + path, e);
        }
    }

    private boolean isUnderAnyRoot(Path target, List<Path> allowedRoots) {
        return allowedRoots.stream().anyMatch(target::startsWith);
    }

    private boolean looksLikeJavaProject(Path target) {
        return PROJECT_MARKERS.stream().anyMatch(marker -> Files.exists(target.resolve(marker)));
    }
}

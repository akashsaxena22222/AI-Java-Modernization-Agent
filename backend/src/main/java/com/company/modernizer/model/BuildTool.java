package com.company.modernizer.model;

/**
 * Build system detected at the project root.
 *
 * <p>Phase 1 analyzes {@link #MAVEN} only. {@link #GRADLE} and {@link #ANT} are detected and
 * reported as unanalyzed rather than silently ignored - a report that quietly skips the build
 * system is worse than one that says it did.
 */
public enum BuildTool {

    MAVEN,
    GRADLE,
    ANT,
    UNKNOWN;

    /** Whether Phase 1 has an analyzer capable of parsing this build system. */
    public boolean isAnalyzed() {
        return this == MAVEN;
    }
}

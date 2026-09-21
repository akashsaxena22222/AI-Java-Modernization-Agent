package com.company.modernizer.model;

/**
 * Aggregate counts for the project, as reported in {@code project.metrics}.
 *
 * <p>{@code testToSourceRatio} is derived, so prefer the seven-argument constructor and let it be
 * computed - passing it separately is how a report ends up internally inconsistent.
 *
 * @param linesOfCode        sum of {@link ScannedFile#codeLines()} - excludes blanks and comments
 * @param testToSourceRatio  test files divided by production Java files; {@code 0.0} when there are
 *                           no production files. A conspicuously low value is one of the strongest
 *                           signals that a codebase cannot be safely refactored yet.
 */
public record ProjectMetrics(
        int javaFiles,
        int testFiles,
        long linesOfCode,
        int xmlConfigFiles,
        int wsdlFiles,
        int jspFiles,
        int declaredDependencies,
        double testToSourceRatio) {

    /** Preferred constructor: derives {@code testToSourceRatio} so it cannot go stale. */
    public ProjectMetrics(
            int javaFiles,
            int testFiles,
            long linesOfCode,
            int xmlConfigFiles,
            int wsdlFiles,
            int jspFiles,
            int declaredDependencies) {
        this(javaFiles, testFiles, linesOfCode, xmlConfigFiles, wsdlFiles, jspFiles,
                declaredDependencies, ratio(javaFiles, testFiles));
    }

    private static double ratio(int javaFiles, int testFiles) {
        return javaFiles <= 0 ? 0.0 : Math.round((double) testFiles / javaFiles * 1000.0) / 1000.0;
    }

    public static ProjectMetrics empty() {
        return new ProjectMetrics(0, 0, 0L, 0, 0, 0, 0);
    }
}

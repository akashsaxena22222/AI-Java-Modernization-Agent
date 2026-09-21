package com.company.modernizer.model;

/**
 * A framework or library detected in the project, with the version found and where it was seen.
 *
 * <p>Produced by looking up dependency coordinates in a known-framework catalog - a table lookup,
 * not judgement, which is why it sits alongside the scanner's mechanical facts rather than being
 * an LLM contribution.
 *
 * @param evidenceFile project-relative path of the file the version was read from, so the claim is
 *                     checkable
 */
public record FrameworkRef(
        String name,
        String version,
        String evidenceFile) {
}

package com.company.modernizer.analyzer;

import java.util.List;

import com.company.modernizer.model.Finding;
import com.company.modernizer.model.ProjectContext;

/**
 * The detection service provider interface: one rule set over the scanned fact base.
 *
 * <p>This is an interface rather than a class on purpose (ARCHITECTURE.md section 5a). Spring
 * injects {@code List<Analyzer>}, so adding SOAP-to-REST mapping, a Gradle analyzer, or a
 * CVE-database lookup later means adding one {@code @Component} and touching <em>zero</em> existing
 * files. That is the architectural claim this project exists to demonstrate, and it is only true if
 * nothing in the orchestrator ever names a concrete analyzer.
 *
 * <p>Three rules for implementations:
 *
 * <ol>
 *   <li><strong>Deterministic.</strong> The same {@link ProjectContext} must always produce the
 *       same findings, in the same order. No clocks, no randomness, no network. Judgement that
 *       cannot be made deterministically belongs to the LLM (Step 5), not here.</li>
 *   <li><strong>Evidence-bearing.</strong> Every finding carries at least one {@code Evidence} with
 *       a project-relative path. A finding a developer cannot check is a finding they will
 *       dismiss.</li>
 *   <li><strong>Read-only.</strong> Analyzers may read file content through
 *       {@code ProjectContext.resolve}, but never write, and never re-walk the tree.</li>
 * </ol>
 *
 * <p>Findings are built with {@link FindingBuilder}, which sets {@code id} to a stable rule key.
 * Sequential {@code F-001} ids are assigned once, across all analyzers, by
 * {@code FindingIdAssigner} - an analyzer cannot know its own numbering because it does not know
 * what the others found.
 */
public interface Analyzer {

    /**
     * Examines the fact base and returns everything this rule set detected.
     *
     * <p>Returns an empty list rather than null when nothing is found. Must not throw for a
     * well-formed context: a malformed project file is a finding, not an exception.
     */
    List<Finding> analyze(ProjectContext context);

    /**
     * Whether this analyzer has anything to say about this project.
     *
     * <p>Lets a Maven-specific rule set stand down on a Gradle project instead of returning an
     * empty list and leaving the reader wondering whether it ran.
     */
    default boolean supports(ProjectContext context) {
        return true;
    }

    /** Reported in {@code meta.warnings} when an analyzer fails, so the gap is attributable. */
    default String name() {
        return getClass().getSimpleName();
    }
}

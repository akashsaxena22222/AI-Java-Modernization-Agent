package com.company.modernizer.model;

/**
 * A concrete, checkable pointer to where a finding was observed.
 *
 * <p>This is the difference between a report a developer trusts and one they dismiss. Every static
 * finding must carry at least one, and it becomes a clickable link in the Phase 2 UI.
 *
 * @param file    project-relative path, never absolute (ARCHITECTURE.md section 9.2)
 * @param line    1-indexed line number, or {@code null} for a whole-file observation
 * @param snippet the offending text, already redacted and capped at
 *                {@code modernizer.ai.max-snippet-chars}. May be {@code null} where the file and
 *                line alone make the point.
 */
public record Evidence(
        String file,
        Integer line,
        String snippet) {

    /** Whole-file evidence, with no specific line. */
    public static Evidence ofFile(String file) {
        return new Evidence(file, null, null);
    }

    public static Evidence of(String file, int line, String snippet) {
        return new Evidence(file, line, snippet);
    }

    /** Renders as {@code path/to/File.java:42}, the form developers can paste into an IDE. */
    public String location() {
        return line == null ? file : file + ":" + line;
    }
}

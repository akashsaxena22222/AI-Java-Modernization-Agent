package com.company.modernizer.scanner;

import java.util.Map;

import com.company.modernizer.model.ScannedFile;

import org.apache.maven.model.Model;

/**
 * One {@code pom.xml}, parsed, together with the properties needed to interpolate it.
 *
 * <p>Deliberately <strong>not</strong> part of {@code ProjectContext}. The context is serialized
 * into the LLM prompt, and a full Maven {@link Model} is both large and mostly irrelevant to
 * judgement - what the prompt needs is the resolved dependency list, which is what the context
 * carries. Rules that genuinely need POM <em>structure</em>, such as "are plugin versions pinned",
 * ask {@link BuildFileParser#readPoms} for this instead.
 *
 * @param file       the scanned file this was read from, for evidence paths
 * @param model      the parsed Maven model of this single POM, uninterpolated
 * @param properties this POM's own {@code <properties>} plus the {@code project.*} built-ins,
 *                   which is everything available to interpolate it without a parent resolver
 */
public record ParsedPom(
        ScannedFile file,
        Model model,
        Map<String, String> properties) {

    public String relativePath() {
        return file.relativePath();
    }

    /** Whether this is the project's root POM rather than a module's. */
    public boolean isRoot() {
        return !relativePath().contains("/");
    }
}

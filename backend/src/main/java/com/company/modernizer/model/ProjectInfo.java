package com.company.modernizer.model;

import java.util.List;

/**
 * The {@code project} block of the report: the outward-facing view of {@link ProjectContext}.
 *
 * <p>Exists separately from {@code ProjectContext} for one reason - {@code rootPath} here is a
 * project-relative or display string, never the absolute machine path, and the full file inventory
 * is reduced to {@link ProjectMetrics}. That keeps section 9.2 enforced by the type system rather
 * than by remembering to sanitize at the edge.
 */
public record ProjectInfo(
        String name,
        String rootPath,
        BuildTool buildTool,
        List<String> modules,
        String detectedJavaVersion,
        List<FrameworkRef> detectedFrameworks,
        ProjectMetrics metrics) {

    public ProjectInfo {
        modules = modules == null ? List.of() : List.copyOf(modules);
        detectedFrameworks = detectedFrameworks == null ? List.of() : List.copyOf(detectedFrameworks);
    }

    /**
     * Projects the scanner's context into the report view, dropping the absolute path and the file
     * inventory.
     */
    public static ProjectInfo from(ProjectContext ctx) {
        return new ProjectInfo(
                ctx.projectName(),
                ctx.rootPath() == null ? null : ctx.rootPath().getFileName().toString(),
                ctx.buildTool(),
                ctx.modules(),
                ctx.detectedJavaVersion(),
                ctx.detectedFrameworks(),
                ctx.metrics());
    }
}

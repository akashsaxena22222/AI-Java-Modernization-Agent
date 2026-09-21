package com.company.modernizer.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Everything the scanner learned about the project: the complete, deterministic fact base.
 *
 * <p>This is the single input every {@code Analyzer} and the LLM prompt builder receives. Two
 * consequences, both deliberate:
 *
 * <ul>
 *   <li>Analyzers are unit-testable against a hand-built {@code ProjectContext} with no temp
 *       directories and no filesystem at all.</li>
 *   <li>It is the future autonomous agent's world model, which is why it is worth getting right
 *       now rather than treating as a scratch DTO.</li>
 * </ul>
 *
 * <p><strong>Not serialized to HTTP directly.</strong> It holds an absolute {@link #rootPath},
 * which section 9.2 forbids from leaving the process. The REST layer maps it to a response DTO,
 * and the prompt builder emits only project-relative paths.
 *
 * @param rootPath            absolute, canonicalized project root. Internal use only.
 * @param detectedJavaVersion as declared by the build, e.g. {@code "1.8"}; {@code null} if absent
 * @param detectedFrameworks  catalog lookups against the dependency list
 * @param files               the full inventory
 * @param dependencies        mechanically extracted from the build files
 * @param scanStats           what the scan did, including any limits hit
 */
public record ProjectContext(
        String projectName,
        Path rootPath,
        BuildTool buildTool,
        List<String> modules,
        String detectedJavaVersion,
        List<FrameworkRef> detectedFrameworks,
        List<ScannedFile> files,
        List<DependencyRef> dependencies,
        ProjectMetrics metrics,
        ScanStats scanStats) {

    public ProjectContext {
        modules = modules == null ? List.of() : List.copyOf(modules);
        detectedFrameworks = detectedFrameworks == null ? List.of() : List.copyOf(detectedFrameworks);
        files = files == null ? List.of() : List.copyOf(files);
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
    }

    /**
     * Returns a copy with the facts that require parsing build files filled in.
     *
     * <p>The scanner produces a context from the filesystem alone, so {@code dependencies},
     * {@code detectedJavaVersion} and {@code detectedFrameworks} start empty. Build-file parsing
     * arrives at Step 3 with {@code maven-model}, and calls this to complete the context rather
     * than mutating it - the record stays immutable and the two phases stay separately testable.
     *
     * <p>{@link ProjectMetrics#declaredDependencies()} is recomputed here so the metric block can
     * never disagree with the dependency list beside it.
     */
    public ProjectContext withBuildFacts(
            String detectedJavaVersion,
            List<FrameworkRef> detectedFrameworks,
            List<DependencyRef> dependencies) {
        List<DependencyRef> deps = dependencies == null ? List.of() : dependencies;
        ProjectMetrics updated = new ProjectMetrics(
                metrics.javaFiles(), metrics.testFiles(), metrics.linesOfCode(),
                metrics.xmlConfigFiles(), metrics.wsdlFiles(), metrics.jspFiles(), deps.size());
        return new ProjectContext(projectName, rootPath, buildTool, modules, detectedJavaVersion,
                detectedFrameworks, files, deps, updated, scanStats);
    }

    // --- File queries -------------------------------------------------------------------------

    /** Every scanned file of any of the given kinds. The main entry point for analyzers. */
    public List<ScannedFile> filesOfKind(FileKind... kinds) {
        Set<FileKind> wanted = Set.of(kinds);
        return files.stream().filter(f -> wanted.contains(f.kind())).toList();
    }

    public Map<FileKind, Long> fileCountsByKind() {
        return files.stream().collect(Collectors.groupingBy(ScannedFile::kind, Collectors.counting()));
    }

    /** Java sources and tests whose import block contains the given package prefix. */
    public List<ScannedFile> filesImporting(String packagePrefix) {
        return files.stream().filter(f -> f.importsPackage(packagePrefix)).toList();
    }

    /** Java sources and tests carrying the given annotation, e.g. {@code "WebService"}. */
    public List<ScannedFile> filesAnnotatedWith(String annotationName) {
        return files.stream().filter(f -> f.hasAnnotation(annotationName)).toList();
    }

    /**
     * Resolves a scanned file back to an absolute path so an analyzer can read its content.
     *
     * <p>The escape hatch for analyzers that genuinely need file text, such as XML config
     * inspection. It resolves a path already discovered by the scan - it does not re-walk the
     * tree, and it cannot reach outside {@link #rootPath}.
     */
    public Path resolve(ScannedFile file) {
        return rootPath.resolve(file.relativePath());
    }

    // --- Dependency queries -------------------------------------------------------------------

    public Optional<DependencyRef> dependency(String groupId, String artifactId) {
        return dependencies.stream().filter(d -> d.matches(groupId, artifactId)).findFirst();
    }

    public boolean hasDependency(String groupId, String artifactId) {
        return dependency(groupId, artifactId).isPresent();
    }

    /** Every dependency in the given group, e.g. all of {@code org.springframework}. */
    public List<DependencyRef> dependenciesInGroup(String groupIdPrefix) {
        return dependencies.stream().filter(d -> d.groupId().startsWith(groupIdPrefix)).toList();
    }

    public Optional<FrameworkRef> framework(String name) {
        return detectedFrameworks.stream().filter(f -> f.name().equals(name)).findFirst();
    }

    public boolean isMultiModule() {
        return modules.size() > 1;
    }
}

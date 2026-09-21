package com.company.modernizer.scanner;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.company.modernizer.model.DependencyRef;
import com.company.modernizer.model.FileKind;
import com.company.modernizer.model.ProjectContext;
import com.company.modernizer.model.ScannedFile;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Extracts declared build facts from {@code pom.xml} files.
 *
 * <p>Fact extraction, not judgement: this class reports that a dependency is declared at version
 * {@code 1.2.17}, and never that 1.2.17 is old. That distinction is the project's core design
 * principle, and it is why this sits beside the scanner rather than among the analyzers.
 *
 * <h2>What "parsed with maven-model" does and does not mean</h2>
 *
 * <p>{@link MavenXpp3Reader} gives a faithful object model of <em>one</em> POM file. It does not
 * resolve parents and does not interpolate - Maven's own {@code ModelBuilder} does that, and needs a
 * repository resolver, i.e. most of the Maven runtime. So this class:
 *
 * <ul>
 *   <li>interpolates {@code ${...}} against that POM's own {@code <properties>} and the
 *       {@code project.*} built-ins, which covers the common case of a version property declared
 *       alongside the dependency that uses it;</li>
 *   <li>resolves a missing version from the same POM's {@code <dependencyManagement>}, flagging it
 *       {@link DependencyRef#managed()};</li>
 *   <li>leaves {@code version} <strong>null</strong> when only an unreachable parent or an
 *       unresolvable placeholder could supply it.</li>
 * </ul>
 *
 * <p>The last point is a feature, not a shortfall. A dependency whose version cannot be determined
 * from the project's own sources is a real build-hygiene problem, and {@code DependencyRef} already
 * documents a null version as worth reporting. Guessing would be worse than admitting.
 */
@Component
public class BuildFileParser {

    private static final Logger log = LoggerFactory.getLogger(BuildFileParser.class);

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    /** Guards against {@code a=${b}}, {@code b=${a}} in a hand-edited POM. */
    private static final int MAX_INTERPOLATION_DEPTH = 10;

    /**
     * Properties that declare the Java language level, most specific first.
     *
     * <p>{@code release} beats {@code source}/{@code target} because it is what javac actually
     * honours when all three are present.
     */
    private static final List<String> JAVA_VERSION_PROPERTIES = List.of(
            "maven.compiler.release", "maven.compiler.source", "maven.compiler.target",
            "java.version");

    /** The same three settings as compiler-plugin configuration elements. */
    private static final List<String> JAVA_VERSION_PLUGIN_ELEMENTS =
            List.of("release", "source", "target");

    /**
     * Everything one scan needs to complete its {@link ProjectContext}.
     *
     * @param javaVersion  as declared, e.g. {@code "1.8"}; null when nothing declares it
     * @param dependencies every declaration across every POM, attributable to the POM that made it
     * @param warnings     POMs that could not be parsed, so a missing dependency list is never
     *                     mistaken for a project having no dependencies
     */
    public record Result(
            String javaVersion,
            List<DependencyRef> dependencies,
            List<String> warnings) {

        public static Result empty() {
            return new Result(null, List.of(), List.of());
        }
    }

    /**
     * Parses every POM in the inventory.
     *
     * @param root the canonicalized project root, used only to resolve the inventoried paths
     */
    public Result parse(Path root, List<ScannedFile> files) {
        List<ParsedPom> poms = readPoms(root, files);
        if (poms.isEmpty()) {
            return Result.empty();
        }

        List<String> warnings = new ArrayList<>();
        int expected = (int) files.stream().filter(f -> f.kind() == FileKind.MAVEN_POM).count();
        if (poms.size() < expected) {
            warnings.add((expected - poms.size()) + " of " + expected + " pom.xml files could not "
                    + "be parsed; their dependencies are absent from this report.");
        }

        List<DependencyRef> dependencies = new ArrayList<>();
        for (ParsedPom pom : poms) {
            dependencies.addAll(dependenciesOf(pom));
        }

        return new Result(detectJavaVersion(poms), List.copyOf(dependencies), List.copyOf(warnings));
    }

    /**
     * Re-reads the POMs for a rule that needs POM structure rather than the resolved dependency
     * list.
     *
     * <p>This parses a second time, after the scan already did. That is a deliberate trade: POMs are
     * small and few, whereas widening {@code ProjectContext} to carry Maven {@link Model} objects
     * would push build-tool detail into the record that gets serialized to the LLM.
     */
    public List<ParsedPom> readPoms(ProjectContext context) {
        return readPoms(context.rootPath(), context.files());
    }

    private List<ParsedPom> readPoms(Path root, List<ScannedFile> files) {
        List<ParsedPom> poms = new ArrayList<>();
        for (ScannedFile file : files) {
            if (file.kind() != FileKind.MAVEN_POM) {
                continue;
            }
            readPom(root, file).ifPresent(poms::add);
        }
        // Root POM first, so Java-version detection and evidence prefer it over a module's.
        return poms.stream()
                .sorted((a, b) -> Boolean.compare(b.isRoot(), a.isRoot()))
                .toList();
    }

    private java.util.Optional<ParsedPom> readPom(Path root, ScannedFile file) {
        Path absolute = root.resolve(file.relativePath());
        try (Reader reader = Files.newBufferedReader(absolute, StandardCharsets.UTF_8)) {
            // Lenient: a legacy POM that Maven itself would grumble about should still yield its
            // dependency list rather than costing us the whole file.
            Model model = new MavenXpp3Reader().read(reader, false);
            return java.util.Optional.of(new ParsedPom(file, model, propertiesOf(model)));
        } catch (Exception e) {
            // Broad on purpose: read() throws XmlPullParserException, a checked Exception that is
            // not an IOException, and a truncated POM can surface as almost anything. One
            // unparseable POM must cost its own dependency list and nothing more - the caller
            // turns the shortfall into a report warning.
            log.warn("Could not parse {}: {}", file.relativePath(), e.toString());
            return java.util.Optional.empty();
        }
    }

    // --- Dependencies -------------------------------------------------------------------------

    private List<DependencyRef> dependenciesOf(ParsedPom pom) {
        Map<String, String> managedVersions = managedVersions(pom);

        List<DependencyRef> refs = new ArrayList<>();
        for (Dependency dependency : pom.model().getDependencies()) {
            String groupId = resolve(dependency.getGroupId(), pom.properties());
            String artifactId = resolve(dependency.getArtifactId(), pom.properties());
            if (groupId == null || artifactId == null) {
                continue;
            }

            String declared = resolve(dependency.getVersion(), pom.properties());
            boolean managed = false;
            if (declared == null) {
                declared = managedVersions.get(groupId + ":" + artifactId);
                managed = declared != null;
            }

            refs.add(new DependencyRef(
                    groupId,
                    artifactId,
                    declared,
                    dependency.getScope() == null ? "compile" : dependency.getScope(),
                    managed,
                    pom.relativePath()));
        }
        return refs;
    }

    private Map<String, String> managedVersions(ParsedPom pom) {
        Map<String, String> versions = new LinkedHashMap<>();
        if (pom.model().getDependencyManagement() == null) {
            return versions;
        }
        for (Dependency managed : pom.model().getDependencyManagement().getDependencies()) {
            String groupId = resolve(managed.getGroupId(), pom.properties());
            String artifactId = resolve(managed.getArtifactId(), pom.properties());
            String version = resolve(managed.getVersion(), pom.properties());
            if (groupId != null && artifactId != null && version != null) {
                versions.put(groupId + ":" + artifactId, version);
            }
        }
        return versions;
    }

    // --- Java version -------------------------------------------------------------------------

    private String detectJavaVersion(List<ParsedPom> poms) {
        // POMs are already root-first, and within a POM the most specific setting wins.
        for (ParsedPom pom : poms) {
            String version = javaVersionOf(pom);
            if (version != null) {
                return version;
            }
        }
        return null;
    }

    private String javaVersionOf(ParsedPom pom) {
        for (String property : JAVA_VERSION_PROPERTIES) {
            String value = resolve(pom.properties().get(property), pom.properties());
            if (value != null) {
                return value;
            }
        }
        return compilerPluginJavaVersion(pom);
    }

    private String compilerPluginJavaVersion(ParsedPom pom) {
        Plugin compiler = compilerPlugin(pom);
        if (compiler == null || !(compiler.getConfiguration() instanceof Xpp3Dom configuration)) {
            return null;
        }
        for (String element : JAVA_VERSION_PLUGIN_ELEMENTS) {
            Xpp3Dom child = configuration.getChild(element);
            if (child != null) {
                String value = resolve(child.getValue(), pom.properties());
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    /** The compiler plugin declaration, or null. Public so build-hygiene rules can reuse it. */
    public static Plugin compilerPlugin(ParsedPom pom) {
        return pluginsOf(pom).stream()
                .filter(p -> "maven-compiler-plugin".equals(p.getArtifactId()))
                .findFirst()
                .orElse(null);
    }

    /** Every {@code <build><plugins>} entry, or empty when the POM declares no build section. */
    public static List<Plugin> pluginsOf(ParsedPom pom) {
        if (pom.model().getBuild() == null) {
            return List.of();
        }
        return pom.model().getBuild().getPlugins();
    }

    // --- Interpolation ------------------------------------------------------------------------

    private static Map<String, String> propertiesOf(Model model) {
        Map<String, String> properties = new LinkedHashMap<>();
        model.getProperties().forEach((key, value) ->
                properties.put(String.valueOf(key), String.valueOf(value)));

        // The project.* built-ins Maven injects. Only those derivable from this POM alone: a
        // version inherited from a parent is genuinely unknown here, and stays unknown.
        putIfPresent(properties, "project.groupId", model.getGroupId());
        putIfPresent(properties, "project.artifactId", model.getArtifactId());
        putIfPresent(properties, "project.version", model.getVersion());
        putIfPresent(properties, "pom.groupId", model.getGroupId());
        putIfPresent(properties, "pom.artifactId", model.getArtifactId());
        putIfPresent(properties, "pom.version", model.getVersion());
        return properties;
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    /**
     * Interpolates {@code ${...}} placeholders.
     *
     * @return the resolved value, or {@code null} if the input was absent, blank, or still contains
     *         a placeholder this POM cannot resolve on its own
     */
    private static String resolve(String raw, Map<String, String> properties) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String current = raw;
        for (int depth = 0; depth < MAX_INTERPOLATION_DEPTH && current.contains("${"); depth++) {
            Matcher matcher = PLACEHOLDER.matcher(current);
            StringBuilder resolved = new StringBuilder();
            boolean substituted = false;
            while (matcher.find()) {
                String replacement = properties.get(matcher.group(1));
                if (replacement == null) {
                    // Leave it in place: the loop exits and we report "unresolvable" as null.
                    matcher.appendReplacement(resolved, Matcher.quoteReplacement(matcher.group()));
                } else {
                    substituted = true;
                    matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
                }
            }
            matcher.appendTail(resolved);
            current = resolved.toString();
            if (!substituted) {
                break;
            }
        }

        return current.contains("${") || current.isBlank() ? null : current;
    }
}

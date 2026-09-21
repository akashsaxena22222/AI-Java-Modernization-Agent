package com.company.modernizer.scanner;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.company.modernizer.config.ModernizerProperties;
import com.company.modernizer.model.BuildTool;
import com.company.modernizer.model.FileKind;
import com.company.modernizer.model.ProjectContext;
import com.company.modernizer.model.ProjectMetrics;
import com.company.modernizer.model.ScanStats;
import com.company.modernizer.model.ScannedFile;
import com.company.modernizer.scanner.ScanException.Reason;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Walks a validated project directory once and produces the deterministic fact base.
 *
 * <p>Read-only: this class opens files for reading and never writes. It also never interprets -
 * it counts, classifies, and extracts import blocks and top-level annotations, leaving every
 * judgement to the analyzers (ARCHITECTURE.md section 8).
 *
 * <p>Every configured limit that is reached is <em>reported</em> in {@link ScanStats#warnings()}.
 * A scan that silently inventoried half a project is worse than one that says so.
 */
@Component
public class ProjectScanner {

    private static final Logger log = LoggerFactory.getLogger(ProjectScanner.class);

    private static final Pattern IMPORT =
            Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.$]+(?:\\.\\*)?)\\s*;");
    private static final Pattern ANNOTATION =
            Pattern.compile("^\\s*@([\\w.]+)");
    private static final Pattern TYPE_DECLARATION =
            Pattern.compile("^\\s*(?:(?:public|protected|private|abstract|final|static|sealed|"
                    + "non-sealed)\\s+)*(?:class|interface|enum|record|@interface)\\s+\\w+");

    private final ModernizerProperties properties;
    private final FileClassifier classifier;
    private final BuildFileParser buildFileParser;
    private final FrameworkCatalog frameworkCatalog;



    public ProjectScanner(
            ModernizerProperties properties,
            FileClassifier classifier,
            BuildFileParser buildFileParser,
            FrameworkCatalog frameworkCatalog) {
        this.properties = properties;
        this.classifier = classifier;
        this.buildFileParser = buildFileParser;
        this.frameworkCatalog = frameworkCatalog;
    }

    /**
     * Scans a project root.
     *
     * <p>The {@code root} must already have been through {@link ScanGuard#validate} - this method
     * does no security checking of its own and assumes a canonicalized, allowlisted directory.
     *
     * <p>Two passes over different things, in one call. The filesystem walk produces the file
     * inventory; {@link BuildFileParser} then reads the POMs it found, so the returned context is
     * the <em>complete</em> fact base - dependencies, declared Java version and detected frameworks
     * included - rather than a partial one every caller would have to finish.
     */
    public ProjectContext scan(Path root) {
        ModernizerProperties.Scan limits = properties.scan();
        long startedAt = System.nanoTime();
        InventoryVisitor visitor = new InventoryVisitor(root, limits, startedAt);

        try {
            Files.walkFileTree(root, Set.of(), limits.maxDepth(), visitor);
        } catch (IOException e) {
            throw new ScanException(Reason.SCAN_FAILED, "Failed to scan " + root.getFileName(), e);
        }

        List<ScannedFile> files = visitor.files;
        BuildFileParser.Result build = buildFileParser.parse(root, files);

        // Parse warnings join the walk's, so "3 poms could not be parsed" is as visible as
        // "3 files exceeded the size cap". Both mean the report is less complete than it looks.
        List<String> warnings = new ArrayList<>(visitor.warnings);
        warnings.addAll(build.warnings());

        long durationMs = (System.nanoTime() - startedAt) / 1_000_000L;
        ScanStats stats = new ScanStats(
                visitor.filesRead,
                visitor.filesSkipped,
                visitor.bytesRead,
                durationMs,
                !warnings.isEmpty(),
                List.copyOf(warnings));

        ProjectContext context = new ProjectContext(
                root.getFileName().toString(),
                root,
                detectBuildTool(root),
                detectModules(root, files),
                null,
                List.of(),
                files,
                List.of(),
                metricsOf(files),
                stats)
                // The seam built at Step 1: completes the context without mutating it, and
                // recomputes declaredDependencies so the metric cannot disagree with the list.
                .withBuildFacts(
                        build.javaVersion(),
                        frameworkCatalog.detect(build.dependencies()),
                        build.dependencies());

        log.info("Scanned {}: {} files read, {} skipped, {} dependencies, Java {}, {} ms{}",
                context.projectName(), stats.filesScanned(), stats.filesSkipped(),
                context.dependencies().size(),
                context.detectedJavaVersion() == null ? "undeclared" : context.detectedJavaVersion(),
                stats.durationMs(), stats.limitsHit() ? " (limits hit)" : "");
        return context;
    }

    // --- Aggregation --------------------------------------------------------------------------

    private static ProjectMetrics metricsOf(List<ScannedFile> files) {
        int javaFiles = 0;
        int testFiles = 0;
        long linesOfCode = 0;
        int xmlConfigFiles = 0;
        int wsdlFiles = 0;
        int jspFiles = 0;

        for (ScannedFile file : files) {
            switch (file.kind()) {
                case JAVA_SOURCE -> {
                    javaFiles++;
                    linesOfCode += file.codeLines();
                }
                case JAVA_TEST -> {
                    testFiles++;
                    linesOfCode += file.codeLines();
                }
                case WEB_XML, SPRING_XML -> xmlConfigFiles++;
                case WSDL -> wsdlFiles++;
                case JSP -> jspFiles++;
                default -> {
                    // Counted in the inventory, but not a headline metric.
                }
            }
        }
        // declaredDependencies is 0 here and filled in by withBuildFacts once the POMs are parsed:
        // the inventory alone cannot know it.
        return new ProjectMetrics(javaFiles, testFiles, linesOfCode, xmlConfigFiles,
                wsdlFiles, jspFiles, 0);
    }

    private static BuildTool detectBuildTool(Path root) {
        if (Files.exists(root.resolve("pom.xml"))) {
            return BuildTool.MAVEN;
        }
        if (Files.exists(root.resolve("build.gradle")) || Files.exists(root.resolve("build.gradle.kts"))) {
            return BuildTool.GRADLE;
        }
        if (Files.exists(root.resolve("build.xml"))) {
            return BuildTool.ANT;
        }
        return BuildTool.UNKNOWN;
    }

    /**
     * Derives module names from the directories that contain a build file.
     *
     * <p>The root module is reported as {@code "."} so a single-module project still has exactly
     * one entry and {@code isMultiModule()} stays meaningful.
     */
    private static List<String> detectModules(Path root, List<ScannedFile> files) {
        Set<String> modules = new LinkedHashSet<>();
        for (ScannedFile file : files) {
            if (file.kind() != FileKind.MAVEN_POM
                    && file.kind() != FileKind.GRADLE_BUILD
                    && file.kind() != FileKind.ANT_BUILD) {
                continue;
            }
            int slash = file.relativePath().lastIndexOf('/');
            modules.add(slash < 0 ? "." : file.relativePath().substring(0, slash));
        }
        // A project with no build file at all still has one module. Returning an empty list here
        // would make isMultiModule() and the report's module block quietly meaningless.
        return modules.isEmpty() ? List.of(".") : List.copyOf(modules);
    }

    // --- The walk ----------------------------------------------------------------------------

    /**
     * Collects the inventory, enforcing every configured limit and recording which ones bit.
     *
     * <p>Implemented as a {@link FileVisitor} rather than a stream so directories can be
     * <em>pruned</em> with {@link FileVisitResult#SKIP_SUBTREE} - we must never descend into a
     * 200 MB {@code target/} just to filter it out afterwards.
     */
    private final class InventoryVisitor implements FileVisitor<Path> {

        private final Path root;
        private final ModernizerProperties.Scan limits;
        private final long deadlineNanos;
        private final long maxFileSizeBytes;
        private final long maxTotalBytes;

        private final List<ScannedFile> files = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();

        private int filesRead;
        private int filesSkipped;
        private long bytesRead;
        private boolean stopped;
        private boolean depthCapReported;

        InventoryVisitor(Path root, ModernizerProperties.Scan limits, long startedAtNanos) {
            this.root = root;
            this.limits = limits;
            this.deadlineNanos = startedAtNanos + limits.timeout().toNanos();
            this.maxFileSizeBytes = limits.maxFileSize().toBytes();
            this.maxTotalBytes = limits.maxTotalBytes().toBytes();
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            if (dir.equals(root)) {
                return FileVisitResult.CONTINUE;
            }
            String name = dir.getFileName().toString();
            if (classifier.isExcludedDirectory(name, limits.excludedDirectories())) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (stopped) {
                return FileVisitResult.TERMINATE;
            }
            // walkFileTree hands us directories - not their contents - once maxDepth is reached.
            // Name the cap that bit, rather than attempting to read a directory as a file and
            // reporting the resulting I/O error as if the file itself were unreadable. Reported
            // once: one warning per deep directory would bury the other warnings.
            if (attrs.isDirectory()) {
                if (!depthCapReported) {
                    depthCapReported = true;
                    warnings.add("Directory depth limit of " + limits.maxDepth()
                            + " reached; deeper directories were not inventoried.");
                }
                return FileVisitResult.CONTINUE;
            }
            if (files.size() >= limits.maxFiles()) {
                return stop("File limit of " + limits.maxFiles()
                        + " reached; the remainder of the project was not inventoried.");
            }
            if (System.nanoTime() > deadlineNanos) {
                return stop("Scan timeout of " + limits.timeout()
                        + " reached; the remainder of the project was not inventoried.");
            }
            if (bytesRead >= maxTotalBytes) {
                return stop("Total read budget of " + limits.maxTotalBytes()
                        + " reached; the remainder of the project was not inventoried.");
            }

            String relativePath = relativize(file);
            FileKind kind = classifier.classify(relativePath);
            long size = attrs.size();

            if (kind == FileKind.NOT_READ) {
                inventoryWithoutReading(relativePath, size);
                return FileVisitResult.CONTINUE;
            }
            if (size > maxFileSizeBytes) {
                inventoryWithoutReading(relativePath, size);
                warnings.add(relativePath + " exceeds the " + limits.maxFileSize()
                        + " file size cap and was inventoried by name only.");
                return FileVisitResult.CONTINUE;
            }

            try {
                files.add(read(file, relativePath, kind, size));
                filesRead++;
                bytesRead += size;
            } catch (IOException e) {
                inventoryWithoutReading(relativePath, size);
                warnings.add("Could not read " + relativePath + ": " + e.getMessage());
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            // A single unreadable file must not abort the whole scan - record it and continue.
            warnings.add("Could not access " + relativize(file) + ": " + exc.getMessage());
            filesSkipped++;
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
            return stopped ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
        }

        private FileVisitResult stop(String warning) {
            warnings.add(warning);
            stopped = true;
            return FileVisitResult.TERMINATE;
        }

        private void inventoryWithoutReading(String relativePath, long size) {
            files.add(ScannedFile.notRead(relativePath, size));
            filesSkipped++;
        }

        /** Project-relative and always forward-slashed, so output is identical across platforms. */
        private String relativize(Path file) {
            return root.relativize(file).toString().replace('\\', '/');
        }

        private ScannedFile read(Path file, String relativePath, FileKind kind, long size)
                throws IOException {
            List<String> lines = readLines(file);

            int blank = 0;
            int comments = 0;
            List<String> imports = new ArrayList<>();
            Set<String> annotations = new LinkedHashSet<>();
            boolean insideBlockComment = false;
            boolean typeSeen = false;

            for (String line : lines) {
                String trimmed = line.trim();

                if (trimmed.isEmpty()) {
                    blank++;
                    continue;
                }

                boolean commentLine = insideBlockComment;
                if (insideBlockComment) {
                    if (trimmed.contains("*/")) {
                        insideBlockComment = false;
                    }
                } else if (trimmed.startsWith("//")) {
                    commentLine = true;
                } else if (trimmed.startsWith("/*")) {
                    commentLine = true;
                    insideBlockComment = !trimmed.contains("*/");
                }
                if (commentLine) {
                    comments++;
                    continue;
                }

                if (!kind.isJava()) {
                    continue;
                }

                Matcher importMatcher = IMPORT.matcher(line);
                if (importMatcher.find()) {
                    imports.add(importMatcher.group(1));
                    continue;
                }
                // Annotations are only collected above the first type declaration, so field and
                // method annotations do not masquerade as type-level ones. A heuristic, not a
                // parse - JavaParser replaces this if Phase 2 needs precision.
                if (!typeSeen) {
                    Matcher annotationMatcher = ANNOTATION.matcher(line);
                    if (annotationMatcher.find()) {
                        annotations.add(annotationMatcher.group(1));
                    } else if (TYPE_DECLARATION.matcher(line).find()) {
                        typeSeen = true;
                    }
                }
            }

            return new ScannedFile(relativePath, kind, size, lines.size(), blank, comments,
                    List.copyOf(imports), List.copyOf(annotations));
        }

        /**
         * Reads text tolerantly.
         *
         * <p>Decodes as UTF-8 but <em>replaces</em> malformed bytes instead of throwing. Legacy
         * codebases routinely contain ISO-8859-1 source files, and a strict decoder would abort the
         * scan on exactly the projects this tool exists to analyze.
         */
        private List<String> readLines(Path file) throws IOException {
            byte[] bytes = Files.readAllBytes(file);
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            return decoder.decode(ByteBuffer.wrap(bytes)).toString().lines().toList();
        }
    }
}

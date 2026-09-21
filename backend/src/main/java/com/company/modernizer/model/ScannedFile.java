package com.company.modernizer.model;

import java.util.List;

/**
 * One file the scanner inventoried.
 *
 * <p>Holds metrics and the two facts that drive most legacy detection - the import block and the
 * top-level annotations - but <strong>not</strong> file content. Keeping content out means a 20 000
 * file inventory stays small in memory; an analyzer that genuinely needs the text resolves the path
 * through {@link ProjectContext#resolve(ScannedFile)} and reads that one file.
 *
 * @param relativePath project-relative path, always forward-slashed for stable output across
 *                     platforms. Absolute paths never appear in the model (section 9.2).
 * @param kind         mechanical classification
 * @param sizeBytes    size on disk
 * @param lineCount    total lines, or {@code 0} if the file was not read
 * @param blankLines   blank line count
 * @param commentLines lines that look like comments; a rough ratio, not a parse
 * @param imports      fully-qualified imports, Java sources only, else empty
 * @param annotations  top-level annotations such as {@code WebService}, Java sources only, else
 *                     empty. Stored without the leading {@code @}.
 */
public record ScannedFile(
        String relativePath,
        FileKind kind,
        long sizeBytes,
        int lineCount,
        int blankLines,
        int commentLines,
        List<String> imports,
        List<String> annotations) {

    public ScannedFile {
        imports = imports == null ? List.of() : List.copyOf(imports);
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
    }

    /** Convenience for files inventoried by name only - binaries, oversized, credential-bearing. */
    public static ScannedFile notRead(String relativePath, long sizeBytes) {
        return new ScannedFile(relativePath, FileKind.NOT_READ, sizeBytes, 0, 0, 0, List.of(), List.of());
    }

    /** Lines that are neither blank nor comments. The figure aggregated into {@code linesOfCode}. */
    public int codeLines() {
        return Math.max(0, lineCount - blankLines - commentLines);
    }

    /** Whether any import starts with the given package prefix, e.g. {@code "javax.jws"}. */
    public boolean importsPackage(String packagePrefix) {
        return imports.stream().anyMatch(i -> i.startsWith(packagePrefix));
    }

    /** Whether the given annotation is present, compared without the leading {@code @}. */
    public boolean hasAnnotation(String simpleOrQualifiedName) {
        return annotations.stream().anyMatch(a -> a.equals(simpleOrQualifiedName)
                || a.endsWith("." + simpleOrQualifiedName));
    }
}

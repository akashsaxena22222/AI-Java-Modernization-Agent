package com.company.modernizer.analyzer.support;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import com.company.modernizer.model.Evidence;
import com.company.modernizer.model.ProjectContext;
import com.company.modernizer.model.ScannedFile;

/**
 * Reads one already-discovered file so a rule can point at a specific line.
 *
 * <p>The sanctioned escape hatch from ARCHITECTURE.md section 8: {@code ScannedFile} deliberately
 * carries metrics rather than content, because holding text for 20 000 files would be wasteful, but
 * an analyzer inspecting {@code web.xml} genuinely needs the text. Resolution goes through
 * {@link ProjectContext#resolve(ScannedFile)}, so this can only reach files the scan already found
 * and cannot escape the scanned root.
 *
 * <p>Failure is not propagated. A file that vanished or turned unreadable between the scan and the
 * analysis costs us one finding's evidence, not the whole report - the caller sees an empty line
 * list and simply detects nothing.
 */
public final class FileText {

    /**
     * Cap on a single evidence snippet.
     *
     * <p>Mirrors the {@code modernizer.ai.max-snippet-chars} default. The configurable cap is
     * enforced by the {@code Redactor} at Step 5, which is where it matters: that is the boundary
     * text actually crosses. This constant keeps report snippets readable in the meantime.
     */
    public static final int MAX_SNIPPET_CHARS = 200;

    private FileText() {
    }

    /** One matched line, with a 1-indexed number ready for {@link Evidence}. */
    public record Line(int number, String text) {

        /** Trimmed and capped, suitable for an evidence snippet. */
        public String snippet() {
            return FileText.snippet(text);
        }
    }

    /**
     * Reads the file as lines, decoding tolerantly.
     *
     * <p>Malformed bytes are replaced rather than thrown, for the same reason the scanner does it:
     * legacy codebases are full of ISO-8859-1 files, and a strict decoder would fail on exactly the
     * projects this tool exists to analyze.
     *
     * @return the lines, or empty if the file could not be read for any reason
     */
    public static List<String> lines(ProjectContext context, ScannedFile file) {
        try {
            byte[] bytes = Files.readAllBytes(context.resolve(file));
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            return decoder.decode(ByteBuffer.wrap(bytes)).toString().lines().toList();
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /** Every line containing {@code needle}, compared case-insensitively. */
    public static List<Line> containing(List<String> lines, String needle) {
        String lowered = needle.toLowerCase(Locale.ROOT);
        List<Line> found = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).toLowerCase(Locale.ROOT).contains(lowered)) {
                found.add(new Line(i + 1, lines.get(i)));
            }
        }
        return found;
    }

    /** Every line matching {@code pattern}. */
    public static List<Line> matching(List<String> lines, Pattern pattern) {
        List<Line> found = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (pattern.matcher(lines.get(i)).find()) {
                found.add(new Line(i + 1, lines.get(i)));
            }
        }
        return found;
    }

    public static Optional<Line> firstContaining(List<String> lines, String needle) {
        return containing(lines, needle).stream().findFirst();
    }

    public static Optional<Line> firstMatching(List<String> lines, Pattern pattern) {
        return matching(lines, pattern).stream().findFirst();
    }

    /**
     * Evidence for the first line containing {@code needle}, falling back to whole-file evidence
     * when the text could not be read or the needle is absent.
     *
     * <p>Falling back rather than returning nothing is deliberate: the finding was established from
     * the fact base, so losing the line number must not lose the finding.
     */
    public static Evidence evidenceFor(
            ProjectContext context, ScannedFile file, String needle) {
        return firstContaining(lines(context, file), needle)
                .map(line -> Evidence.of(file.relativePath(), line.number(), line.snippet()))
                .orElseGet(() -> Evidence.ofFile(file.relativePath()));
    }

    /** Trims surrounding whitespace and caps length, appending an ellipsis when truncated. */
    public static String snippet(String text) {
        String trimmed = text.strip();
        return trimmed.length() <= MAX_SNIPPET_CHARS
                ? trimmed
                : trimmed.substring(0, MAX_SNIPPET_CHARS - 1) + "…";
    }
}

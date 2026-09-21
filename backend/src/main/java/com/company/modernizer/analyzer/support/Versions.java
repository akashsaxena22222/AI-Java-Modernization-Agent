package com.company.modernizer.analyzer.support;

/**
 * Version string comparison, good enough for legacy-detection rules.
 *
 * <p>Not a Maven version implementation. Maven's own {@code ComparableVersion} handles qualifier
 * ranking, {@code sp} releases, and other subtleties that matter when resolving a build; none of
 * that changes the answer to "is this Spring 4 or Spring 6". What <em>does</em> matter, and is the
 * reason this exists rather than {@code String.compareTo}, is <strong>numeric</strong> segment
 * comparison: lexically {@code "2.9.4"} sorts after {@code "2.12"}, which would silently clear a
 * vulnerable Jackson.
 */
public final class Versions {

    /** Segment separators: {@code 4.3.9.RELEASE}, {@code 4.2.21.Final}, {@code 2.5.30-rc1}. */
    private static final String SEPARATORS = "[.\\-_]";

    private Versions() {
    }

    /**
     * The Java language level a version string denotes.
     *
     * <p>Handles both spellings that occur in a POM: the historic {@code 1.8} and the modern
     * {@code 17}. This is why Java versions cannot go through {@link #compare} - there,
     * {@code 1.8}'s major is 1.
     *
     * @return the major version, or {@code -1} if it cannot be determined
     */
    public static int javaMajor(String version) {
        if (version == null || version.isBlank()) {
            return -1;
        }
        String trimmed = version.strip();
        // "1.8" means 8, but "11" means 11 - only strip a literal "1." prefix.
        if (trimmed.startsWith("1.") && trimmed.length() > 2) {
            trimmed = trimmed.substring(2);
        }
        return leadingInt(trimmed);
    }

    /**
     * Compares two version strings segment by segment, numerically where both segments are numeric.
     *
     * <p>A non-numeric segment ({@code RELEASE}, {@code Final}, {@code rc1}) compares as lower than
     * any number, so {@code 5.0-rc1} precedes {@code 5.0.1}. Missing trailing segments count as
     * zero, so {@code 3.2} equals {@code 3.2.0}.
     *
     * @return negative if {@code left} is older, zero if equal, positive if newer
     */
    public static int compare(String left, String right) {
        String[] a = left.strip().split(SEPARATORS);
        String[] b = right.strip().split(SEPARATORS);

        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int result = compareSegment(
                    i < a.length ? a[i] : "0",
                    i < b.length ? b[i] : "0");
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    /** Whether {@code version} is strictly older than {@code threshold}. */
    public static boolean isBefore(String version, String threshold) {
        return version != null && compare(version, threshold) < 0;
    }

    /** The first segment as an integer, e.g. {@code 4} for {@code 4.3.9.RELEASE}; {@code -1} if none. */
    public static int major(String version) {
        if (version == null || version.isBlank()) {
            return -1;
        }
        return leadingInt(version.strip().split(SEPARATORS)[0]);
    }

    /** {@code 4.3.9.RELEASE} to {@code "4.3"}, for readable report prose. */
    public static String majorMinor(String version) {
        if (version == null || version.isBlank()) {
            return "unknown";
        }
        String[] segments = version.strip().split(SEPARATORS);
        return segments.length < 2 ? segments[0] : segments[0] + "." + segments[1];
    }

    private static int compareSegment(String left, String right) {
        Integer a = asInt(left);
        Integer b = asInt(right);

        if (a != null && b != null) {
            return Integer.compare(a, b);
        }
        if (a != null) {
            // A number outranks a qualifier: 5.0.1 is newer than 5.0.RELEASE.
            return 1;
        }
        if (b != null) {
            return -1;
        }
        return left.compareToIgnoreCase(right);
    }

    private static Integer asInt(String segment) {
        try {
            return Integer.valueOf(segment);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int leadingInt(String text) {
        int end = 0;
        while (end < text.length() && Character.isDigit(text.charAt(end))) {
            end++;
        }
        return end == 0 ? -1 : Integer.parseInt(text.substring(0, end));
    }
}

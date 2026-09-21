package com.company.modernizer.analyzer.support;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Masks credential values so a finding can report a secret's <em>presence</em> without carrying it.
 *
 * <p>This exists at Step 3 rather than waiting for the Step 5 {@code Redactor} because the need is
 * already real: {@code POST /api/v1/analysis} returns findings over HTTP, and evidence snippets are
 * read from the file that was flagged. A rule that detects {@code db.password=hunter2} and quotes
 * the line as proof has copied the credential into the report, into whatever logs the response, and
 * later into the LLM payload.
 *
 * <p>The design rule from ARCHITECTURE.md section 9.2 applies here unchanged: we transmit
 * {@code db.password=***REDACTED***}, because the presence of a plaintext credential is itself the
 * finding, and the value never is.
 *
 * <p>Step 5's {@code Redactor} is the general form of this - applied to every string leaving the
 * process, with entropy and known-prefix scrubbing and its own test suite. This class is the narrow
 * case needed to make static findings safe on their own, and that one should absorb it.
 */
public final class SecretMasking {

    public static final String MASK = "***REDACTED***";

    /**
     * Key fragments that mean the value is a credential.
     *
     * <p>Matches the pattern documented in section 9.2. Substring matching, so {@code db.password},
     * {@code smtp.pwd} and {@code partner.api.token} all hit.
     */
    private static final List<String> SECRET_KEY_FRAGMENTS = List.of(
            "pass", "pwd", "secret", "token", "credential", "dsn", "apikey", "api-key", "api_key");

    /**
     * {@code key} alone is deliberately handled separately from the list above.
     *
     * <p>As a bare substring it matches far too much - {@code cache.key.prefix},
     * {@code keyspace}, {@code monkey} - so it only counts as a secret when it is the whole final
     * segment of the key, or the key ends in {@code -key}/{@code _key}.
     */
    private static final Pattern KEY_SUFFIX = Pattern.compile("(^|[._\\-])keys?$");

    /** {@code jdbc:oracle:thin:user/password@//host} and {@code scheme://user:pass@host}. */
    private static final Pattern CREDENTIALS_IN_URL = Pattern.compile(
            "(?i)(jdbc:[^\\s]*?:|[a-z][a-z0-9+.\\-]*://)([^\\s:/@]+)[:/]([^\\s:/@]+)@");

    /** Well-known credential prefixes, masked wherever they appear. */
    private static final List<Pattern> KNOWN_SECRET_PATTERNS = List.of(
            Pattern.compile("AKIA[0-9A-Z]{16}"),
            Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._\\-]{16,}"),
            Pattern.compile("eyJ[A-Za-z0-9._\\-]{16,}"),
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"));

    private SecretMasking() {
    }

    /** Whether a property or YAML key names a credential. */
    public static boolean isSecretKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String lowered = key.toLowerCase(Locale.ROOT);
        if (SECRET_KEY_FRAGMENTS.stream().anyMatch(lowered::contains)) {
            return true;
        }
        return KEY_SUFFIX.matcher(lowered).find();
    }

    /**
     * Masks a {@code key=value} or {@code key: value} line, preserving the key.
     *
     * <p>The key is the finding - it says which credential is exposed and where. Comment lines are
     * returned untouched, since a commented-out setting is not a live credential and its text is
     * often the clearest explanation of what the setting is for.
     */
    public static String maskAssignment(String line) {
        String trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
            return trimmed;
        }

        int separator = indexOfSeparator(trimmed);
        if (separator < 0) {
            return maskKnownPatterns(trimmed);
        }

        String key = trimmed.substring(0, separator).strip();
        String delimiter = String.valueOf(trimmed.charAt(separator));
        String value = trimmed.substring(separator + 1).strip();

        if (value.isEmpty() || isPlaceholder(value)) {
            // An unresolved ${...} or an empty value is not an exposed credential, and showing it
            // is what tells a reader the setting is externalized properly.
            return trimmed;
        }
        if (isSecretKey(key)) {
            return key + delimiter + " " + MASK;
        }
        return key + delimiter + " " + maskKnownPatterns(value);
    }

    /**
     * Masks embedded URL credentials and known secret formats anywhere in a string.
     *
     * <p>Applied to values whose key looks innocent: a JDBC URL under {@code db.readonly.url}
     * carries a password even though {@code url} is not a secret-sounding key.
     */
    public static String maskKnownPatterns(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String masked = CREDENTIALS_IN_URL.matcher(text).replaceAll("$1$2:" + MASK + "@");
        for (Pattern pattern : KNOWN_SECRET_PATTERNS) {
            masked = pattern.matcher(masked).replaceAll(MASK);
        }
        return masked;
    }

    /** Whether a value carries a credential even though its key does not look like one. */
    public static boolean containsEmbeddedSecret(String value) {
        if (value == null || value.isBlank() || isPlaceholder(value)) {
            return false;
        }
        if (CREDENTIALS_IN_URL.matcher(value).find()) {
            return true;
        }
        return KNOWN_SECRET_PATTERNS.stream().anyMatch(p -> p.matcher(value).find());
    }

    /**
     * Whether the value is externalized rather than hardcoded - {@code ${DB_PASSWORD}},
     * {@code @db.password@}, or an obvious placeholder.
     */
    public static boolean isPlaceholder(String value) {
        String trimmed = value.strip();
        return trimmed.startsWith("${")
                || (trimmed.startsWith("@") && trimmed.endsWith("@"))
                || trimmed.equals("changeme")
                || trimmed.equals(MASK);
    }

    /** First {@code =} or {@code :} outside of a URL scheme, or {@code -1}. */
    private static int indexOfSeparator(String line) {
        int equals = line.indexOf('=');
        int colon = line.indexOf(':');
        if (equals >= 0 && (colon < 0 || equals < colon)) {
            return equals;
        }
        return colon;
    }
}

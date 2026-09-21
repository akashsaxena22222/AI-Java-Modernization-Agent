package com.company.modernizer.scanner;

/**
 * A scan could not be performed, or could not be performed safely.
 *
 * <p>Carries a {@link Reason} rather than only a message so the REST layer can map causes to status
 * codes without string matching, and so tests can assert on the cause rather than on wording.
 */
public class ScanException extends RuntimeException {

    private final Reason reason;

    public ScanException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ScanException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {

        /** No path supplied. */
        PATH_REQUIRED(true),

        /**
         * {@code modernizer.scan.allowed-roots} is empty or none of its entries resolve.
         *
         * <p>We fail closed: with no allowlist there is no scanning at all. Defaulting to
         * permissive would turn a path parameter into "read any directory on the host".
         */
        ALLOWLIST_EMPTY(false),

        /** The string is not a valid filesystem path on this platform. */
        INVALID_PATH(true),

        NOT_FOUND(true),
        NOT_A_DIRECTORY(true),
        NOT_READABLE(true),

        /**
         * The canonicalized target is not underneath any allowed root.
         *
         * <p>This is the check that defeats {@code ..} traversal and symlinks pointing outside the
         * allowlist, because it compares real paths, not the strings supplied by the caller.
         */
        OUTSIDE_ALLOWED_ROOTS(true),

        /** No {@code pom.xml}, Gradle script, Ant build, or {@code src/} directory present. */
        NOT_A_JAVA_PROJECT(true),

        /** An I/O failure during the walk. */
        SCAN_FAILED(false);

        private final boolean clientError;

        Reason(boolean clientError) {
            this.clientError = clientError;
        }

        /**
         * Whether the caller could fix this by sending a different request (HTTP 4xx) as opposed to
         * it being a server-side configuration or I/O problem (HTTP 5xx).
         */
        public boolean isClientError() {
            return clientError;
        }
    }
}

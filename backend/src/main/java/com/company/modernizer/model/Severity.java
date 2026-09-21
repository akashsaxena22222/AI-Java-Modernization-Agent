package com.company.modernizer.model;

/**
 * How badly a {@link Finding} matters.
 *
 * <p>Declaration order is significant: it is the report's sort order (most severe first) and the
 * drop order when the LLM payload must be reduced to fit the token budget
 * (ARCHITECTURE.md section 9.4).
 */
public enum Severity {

    CRITICAL,
    HIGH,
    MEDIUM,
    LOW,
    INFO;

    /**
     * Whether this severity is at least as serious as {@code other}.
     *
     * <p>Used by payload degradation, which drops the least serious findings first.
     */
    public boolean isAtLeast(Severity other) {
        return this.ordinal() <= other.ordinal();
    }
}

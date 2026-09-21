package com.company.modernizer.model;

/**
 * How risky the <em>remediation</em> is, as distinct from how serious the problem is.
 *
 * <p>A finding can be {@link Severity#CRITICAL} but {@link #LOW} risk to fix (bump a dependency
 * with a known CVE), or {@link Severity#MEDIUM} but {@link #HIGH} risk (rewrite the security
 * filter chain). Keeping the two separate is what lets the roadmap sequence cheap-and-safe work
 * ahead of expensive-and-dangerous work.
 *
 * <p>Deliberately has no {@code INFO} value - unlike {@link Severity}, "informational risk" is
 * not a meaningful statement.
 */
public enum RiskLevel {

    CRITICAL,
    HIGH,
    MEDIUM,
    LOW
}

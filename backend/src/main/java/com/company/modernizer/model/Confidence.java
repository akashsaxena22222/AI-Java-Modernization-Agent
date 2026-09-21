package com.company.modernizer.model;

/**
 * How much to trust a finding or an estimate.
 *
 * <p>Static findings that read a value straight out of {@code pom.xml} are {@link #HIGH}.
 * LLM-contributed judgement and effort estimates are usually {@link #MEDIUM} - reporting that
 * honestly is what keeps the report credible with developers.
 */
public enum Confidence {

    HIGH,
    MEDIUM,
    LOW
}

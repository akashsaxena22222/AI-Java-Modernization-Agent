package com.company.modernizer.model;

/**
 * Who produced a {@link Finding}.
 *
 * <p>Present on every finding so the AI's actual contribution is <em>demonstrable rather than
 * asserted</em> - during a demo you can say precisely which parts came from deterministic rules
 * and which from the model. It also lets tests assert that {@link #STATIC} findings survive
 * unchanged when AI assessment is disabled.
 */
public enum FindingSource {

    /** Produced by a deterministic {@code Analyzer} rule. Always present, even offline. */
    STATIC,

    /** Contributed entirely by the LLM - something no static rule detected. */
    AI,

    /** Detected statically, then enriched by the LLM with impact, recommendation, or effort. */
    STATIC_AI
}

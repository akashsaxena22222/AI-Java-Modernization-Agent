package com.company.modernizer.model;

/**
 * T-shirt sizing for remediation effort.
 *
 * <p>Deliberately coarse. A model asked for "37 person-days" will produce a precise-looking number
 * it cannot justify; a size plus a separate {@code personDays} figure carrying its own
 * {@link Confidence} is the honest shape.
 */
public enum EffortSize {

    XS,
    S,
    M,
    L,
    XL
}

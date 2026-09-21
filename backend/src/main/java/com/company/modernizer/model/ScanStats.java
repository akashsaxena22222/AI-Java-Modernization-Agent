package com.company.modernizer.model;

import java.util.List;

/**
 * What the scan itself did, surfaced as {@code meta.scan} in the report.
 *
 * <p>{@code limitsHit} and {@code warnings} exist because of a deliberate rule: a scan that
 * silently analyzed only part of a project is worse than one that says so
 * (ARCHITECTURE.md section 8, stage 2).
 *
 * @param filesScanned files read and classified
 * @param filesSkipped files inventoried by name only - pruned, oversized, binary, credential-bearing
 * @param bytesRead    cumulative bytes read, against the {@code max-total-bytes} budget
 * @param durationMs   wall-clock duration of the scan
 * @param limitsHit    whether any configured cap was reached
 * @param warnings     human-readable notes naming exactly which caps were hit and what was dropped
 */
public record ScanStats(
        int filesScanned,
        int filesSkipped,
        long bytesRead,
        long durationMs,
        boolean limitsHit,
        List<String> warnings) {

    public ScanStats {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static ScanStats empty() {
        return new ScanStats(0, 0, 0L, 0L, false, List.of());
    }
}

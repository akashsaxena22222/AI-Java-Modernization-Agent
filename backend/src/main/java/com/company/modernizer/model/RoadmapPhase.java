package com.company.modernizer.model;

import java.util.List;

/**
 * One stage of the proposed modernization sequence.
 *
 * <p>Derived from the {@link Finding#blockedBy()} / {@link Finding#blocks()} graph rather than
 * asked for freehand, so the ordering is defensible when someone challenges it in the room.
 *
 * <p>In a later phase this record becomes the autonomous agent's unit of work.
 *
 * @param phase     1-based sequence number
 * @param goal      the verifiable end state of this phase - what is true once it is done
 * @param findingIds findings this phase resolves
 * @param dependsOn phase numbers that must complete first
 */
public record RoadmapPhase(
        int phase,
        String name,
        String goal,
        List<String> findingIds,
        EffortSize estimatedEffort,
        int personDays,
        List<Integer> dependsOn) {

    public RoadmapPhase {
        findingIds = findingIds == null ? List.of() : List.copyOf(findingIds);
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }

    /** Whether this phase can begin immediately. */
    public boolean isStartable() {
        return dependsOn.isEmpty();
    }
}

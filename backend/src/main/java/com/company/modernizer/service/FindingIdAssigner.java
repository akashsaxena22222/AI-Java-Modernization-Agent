package com.company.modernizer.service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.company.modernizer.model.Finding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns each analyzer's stable rule keys into the report's sequential {@code F-001} ids.
 *
 * <p>Numbering cannot happen inside an analyzer, because an analyzer does not know what the others
 * found. It happens exactly once, here, over the combined list.
 *
 * <p>Two properties matter and are tested:
 *
 * <ul>
 *   <li><strong>Stable.</strong> Ordering is severity, then category, then rule key - all three
 *       total - so the same project produces the same numbering on every run. A demo where
 *       {@code F-003} means something different each time is worse than no numbering.</li>
 *   <li><strong>Referentially closed.</strong> {@code blockedBy} and {@code blocks} are rewritten
 *       from keys to ids, and any reference to a rule that did not fire is <em>dropped</em>. An
 *       analyzer can therefore declare "this is blocked by the Java upgrade" unconditionally, and
 *       the reference simply disappears on a project that is already on Java 21.</li>
 * </ul>
 */
@Component
public class FindingIdAssigner {

    private static final Logger log = LoggerFactory.getLogger(FindingIdAssigner.class);

    private static final String ID_FORMAT = "F-%03d";

    /**
     * Sorts, numbers, and rewrites the blocking graph.
     *
     * @param findings raw findings from every analyzer, carrying rule keys as ids
     * @return the same findings, ordered and renumbered, with a resolved blocking graph
     */
    public List<Finding> assign(List<Finding> findings) {
        List<Finding> ordered = findings.stream()
                .sorted(Comparator.comparing(Finding::severity)
                        .thenComparing(Finding::category)
                        .thenComparing(Finding::id))
                .toList();

        Map<String, String> idsByKey = new LinkedHashMap<>();
        for (Finding finding : ordered) {
            String assigned = ID_FORMAT.formatted(idsByKey.size() + 1);
            if (idsByKey.putIfAbsent(finding.id(), assigned) != null) {
                // Two analyzers produced the same rule key. That is a bug in a rule, not bad
                // input, but losing the whole report over it would be a poor trade.
                log.warn("Duplicate finding key '{}'; the later occurrence keeps the earlier id. "
                        + "Rule keys must be unique per detection instance.", finding.id());
            }
        }

        return ordered.stream().map(finding -> renumber(finding, idsByKey)).toList();
    }

    private Finding renumber(Finding finding, Map<String, String> idsByKey) {
        return new Finding(
                idsByKey.get(finding.id()),
                finding.category(),
                finding.title(),
                finding.severity(),
                finding.confidence(),
                finding.source(),
                finding.evidence(),
                finding.impact(),
                finding.recommendation(),
                finding.currentState(),
                finding.targetState(),
                finding.effort(),
                finding.risk(),
                resolve(finding.blockedBy(), idsByKey),
                resolve(finding.blocks(), idsByKey),
                finding.references());
    }

    /** Maps keys to ids, silently dropping references to rules that did not fire. */
    private static List<String> resolve(List<String> keys, Map<String, String> idsByKey) {
        return keys.stream()
                .map(idsByKey::get)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }
}

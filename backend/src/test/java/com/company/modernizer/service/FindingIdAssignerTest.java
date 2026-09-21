package com.company.modernizer.service;

import java.util.List;

import com.company.modernizer.analyzer.FindingBuilder;
import com.company.modernizer.model.Evidence;
import com.company.modernizer.model.Finding;
import com.company.modernizer.model.FindingCategory;
import com.company.modernizer.model.Severity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for rule-key to report-id assignment.
 *
 * <p>Two guarantees are worth pinning: numbering is stable across runs, and the blocking graph
 * never points at a finding that is not in the report.
 */
class FindingIdAssignerTest {

    private final FindingIdAssigner assigner = new FindingIdAssigner();

    private Finding finding(String key, FindingCategory category, Severity severity) {
        return FindingBuilder.of(key, category, "Title for " + key)
                .severity(severity)
                .evidence(Evidence.ofFile("pom.xml"))
                .build();
    }

    private Finding blocking(String key, Severity severity, String[] blockedBy, String[] blocks) {
        return FindingBuilder.of(key, FindingCategory.BUILD, "Title for " + key)
                .severity(severity)
                .evidence(Evidence.ofFile("pom.xml"))
                .blockedBy(blockedBy)
                .blocks(blocks)
                .build();
    }

    @Test
    @DisplayName("numbers findings most severe first, zero-padded")
    void numbersBySeverity() {
        List<Finding> assigned = assigner.assign(List.of(
                finding("low-thing", FindingCategory.TESTING, Severity.LOW),
                finding("critical-thing", FindingCategory.SECURITY, Severity.CRITICAL),
                finding("medium-thing", FindingCategory.BUILD, Severity.MEDIUM)));

        assertThat(assigned).extracting(Finding::id).containsExactly("F-001", "F-002", "F-003");
        assertThat(assigned).extracting(Finding::severity)
                .containsExactly(Severity.CRITICAL, Severity.MEDIUM, Severity.LOW);
    }

    @Test
    @DisplayName("produces identical numbering regardless of the order analyzers ran in")
    void numberingIsStable() {
        Finding a = finding("aaa", FindingCategory.SECURITY, Severity.HIGH);
        Finding b = finding("bbb", FindingCategory.SECURITY, Severity.HIGH);
        Finding c = finding("ccc", FindingCategory.BUILD, Severity.HIGH);

        // Same findings, different arrival order. A demo where F-003 means something different
        // on each run is worse than no numbering at all.
        List<String> first = assigner.assign(List.of(a, b, c)).stream()
                .map(f -> f.id() + "=" + f.title()).toList();
        List<String> second = assigner.assign(List.of(c, b, a)).stream()
                .map(f -> f.id() + "=" + f.title()).toList();

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("rewrites the blocking graph from rule keys to assigned ids")
    void rewritesGraph() {
        List<Finding> assigned = assigner.assign(List.of(
                blocking("java-version", Severity.HIGH, new String[] {}, new String[] {"spring"}),
                blocking("spring", Severity.MEDIUM, new String[] {"java-version"}, new String[] {})));

        Finding java = assigned.getFirst();
        Finding spring = assigned.get(1);

        assertThat(java.id()).isEqualTo("F-001");
        assertThat(spring.id()).isEqualTo("F-002");
        assertThat(java.blocks()).containsExactly("F-002");
        assertThat(spring.blockedBy()).containsExactly("F-001");
        assertThat(spring.isUnblocked()).isFalse();
    }

    @Test
    @DisplayName("drops references to rules that did not fire, leaving no dangling ids")
    void dropsDanglingReferences() {
        // An analyzer declares 'blocked by the Java upgrade' unconditionally. On a project
        // already at Java 21 that finding does not exist, and the reference must vanish rather
        // than point at nothing.
        List<Finding> assigned = assigner.assign(List.of(
                blocking("spring", Severity.HIGH,
                        new String[] {"java-version-outdated"}, new String[] {"never-emitted"})));

        assertThat(assigned).hasSize(1);
        assertThat(assigned.getFirst().blockedBy()).isEmpty();
        assertThat(assigned.getFirst().blocks()).isEmpty();
        assertThat(assigned.getFirst().isUnblocked()).isTrue();
    }

    @Test
    @DisplayName("every graph reference resolves to a finding present in the report")
    void graphIsReferentiallyClosed() {
        List<Finding> assigned = assigner.assign(List.of(
                blocking("a", Severity.HIGH, new String[] {"b"}, new String[] {"c", "ghost"}),
                blocking("b", Severity.MEDIUM, new String[] {}, new String[] {"a"}),
                blocking("c", Severity.LOW, new String[] {"a"}, new String[] {})));

        List<String> ids = assigned.stream().map(Finding::id).toList();
        assertThat(assigned).allSatisfy(finding -> {
            assertThat(ids).containsAll(finding.blockedBy());
            assertThat(ids).containsAll(finding.blocks());
        });
    }

    @Test
    @DisplayName("handles an empty analysis without inventing anything")
    void empty() {
        assertThat(assigner.assign(List.of())).isEmpty();
    }
}

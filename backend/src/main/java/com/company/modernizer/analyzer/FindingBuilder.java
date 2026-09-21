package com.company.modernizer.analyzer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.company.modernizer.model.Confidence;
import com.company.modernizer.model.EffortSize;
import com.company.modernizer.model.Evidence;
import com.company.modernizer.model.Finding;
import com.company.modernizer.model.FindingCategory;
import com.company.modernizer.model.FindingSource;
import com.company.modernizer.model.RiskLevel;
import com.company.modernizer.model.Severity;

/**
 * Fluent construction of a {@link Finding} from an analyzer rule.
 *
 * <p>{@code Finding} has sixteen components. Calling its constructor positionally inside a detection
 * rule buries the rule's logic in argument lists and makes a transposed pair of strings a silent
 * bug, so every analyzer builds findings through here.
 *
 * <p><strong>On the id.</strong> {@link #build()} sets {@code Finding.id} to the stable rule
 * {@code key} given at construction - {@code "dependency-eol:log4j:log4j"}, not {@code "F-001"}.
 * Sequential report ids are assigned once across all analyzers by {@code FindingIdAssigner}, which
 * also rewrites {@link #blocks}/{@link #blockedBy} from keys to ids. An analyzer cannot number its
 * own findings, because it does not know what the other analyzers found.
 *
 * <p>Keys must therefore be unique per detection <em>instance</em>, not per rule: a rule firing
 * once per outdated dependency includes the coordinates in its key. Uniqueness is what makes the
 * assigner's ordering total, and so makes report numbering stable across runs.
 */
public final class FindingBuilder {

    private final String key;
    private final FindingCategory category;
    private final String title;

    private Severity severity = Severity.MEDIUM;
    private Confidence confidence = Confidence.HIGH;
    private final List<Evidence> evidence = new ArrayList<>();
    private String impact;
    private String recommendation;
    private String currentState;
    private String targetState;
    private EffortSize effort;
    private RiskLevel risk;
    private final List<String> blockedBy = new ArrayList<>();
    private final List<String> blocks = new ArrayList<>();
    private final List<String> references = new ArrayList<>();

    private FindingBuilder(String key, FindingCategory category, String title) {
        this.key = key;
        this.category = category;
        this.title = title;
    }

    /**
     * @param key      stable, unique identity for this detection - see the class note
     * @param category one of the twelve documented detection areas
     * @param title    one line, specific enough to be actionable on its own
     */
    public static FindingBuilder of(String key, FindingCategory category, String title) {
        return new FindingBuilder(key, category, title);
    }

    public FindingBuilder severity(Severity severity) {
        this.severity = severity;
        return this;
    }

    /**
     * How sure the rule is.
     *
     * <p>Defaults to {@link Confidence#HIGH} because a static rule reading a declared value is
     * certain of it. Rules that infer - name-based classification, a heuristic line scan - should
     * lower this rather than overstate.
     */
    public FindingBuilder confidence(Confidence confidence) {
        this.confidence = confidence;
        return this;
    }

    public FindingBuilder evidence(Evidence... entries) {
        this.evidence.addAll(Arrays.asList(entries));
        return this;
    }

    public FindingBuilder evidence(List<Evidence> entries) {
        this.evidence.addAll(entries);
        return this;
    }

    /** Why it matters, in terms a developer or a manager can act on. */
    public FindingBuilder impact(String impact) {
        this.impact = impact;
        return this;
    }

    /** What to actually do. Concrete enough to start from. */
    public FindingBuilder recommendation(String recommendation) {
        this.recommendation = recommendation;
        return this;
    }

    public FindingBuilder states(String currentState, String targetState) {
        this.currentState = currentState;
        this.targetState = targetState;
        return this;
    }

    /**
     * Cost to remediate and danger of remediating.
     *
     * <p>Two axes on purpose: bumping a dependency with a known CVE is {@code CRITICAL} severity but
     * {@code LOW} risk, while rewriting a security filter chain is the reverse. The roadmap needs
     * both to sequence cheap-and-safe work ahead of expensive-and-dangerous work.
     */
    public FindingBuilder cost(EffortSize effort, RiskLevel risk) {
        this.effort = effort;
        this.risk = risk;
        return this;
    }

    /** Keys of findings that must be resolved before this one can start. */
    public FindingBuilder blockedBy(String... keys) {
        this.blockedBy.addAll(Arrays.asList(keys));
        return this;
    }

    /** Keys of findings this one prevents progress on. */
    public FindingBuilder blocks(String... keys) {
        this.blocks.addAll(Arrays.asList(keys));
        return this;
    }

    public FindingBuilder references(String... urls) {
        this.references.addAll(Arrays.asList(urls));
        return this;
    }

    public Finding build() {
        if (evidence.isEmpty()) {
            // Enforced rather than documented: rule 2 of the Analyzer contract. A finding with no
            // checkable location is one a developer dismisses, and it would also give the LLM
            // nothing to reason about at Step 5.
            throw new IllegalStateException(
                    "Finding '" + key + "' has no evidence; every static finding needs at least one.");
        }
        return new Finding(
                key, category, title, severity, confidence, FindingSource.STATIC,
                List.copyOf(evidence), impact, recommendation, currentState, targetState,
                effort, risk, List.copyOf(blockedBy), List.copyOf(blocks), List.copyOf(references));
    }
}

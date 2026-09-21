package com.company.modernizer.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * All tunable configuration for the modernizer, bound from the {@code modernizer.*} block of
 * application.yml.
 *
 * <p>This record exists from the first commit on purpose: it makes the two contracts that matter
 * most visible up front rather than letting them appear later as an afterthought - the scanner's
 * safety limits ({@link Scan}) and the data-egress gate ({@link Ai#enabled()}).
 *
 * <p>Nothing reads the {@code scan} or most {@code ai} values yet; they are consumed at Step 2
 * (scanner) and Step 5 (Anthropic provider). See ARCHITECTURE.md sections 8 and 9.
 *
 * <p>The Anthropic API key is deliberately <strong>absent</strong> from this record. It is read
 * only from the {@code ANTHROPIC_API_KEY} environment variable, so it can never be committed in a
 * yaml file or logged as part of a bound properties object.
 *
 * @param analyzerVersion reported as {@code meta.analyzerVersion} in every generated report
 * @param scan            filesystem scanning limits and the path allowlist
 * @param ai              LLM provider selection, egress consent, and payload budgets
 */
@ConfigurationProperties(prefix = "modernizer")
public record ModernizerProperties(

        @DefaultValue("0.1.0") String analyzerVersion,
        @DefaultValue Scan scan,
        @DefaultValue Ai ai) {

    /**
     * Environment variable the Anthropic SDK reads the credential from. Referenced here only so
     * that {@code /api/v1/health} can report whether a key is present, never its value.
     */
    public static final String API_KEY_ENV_VAR = "ANTHROPIC_API_KEY";

    /**
     * Scanner safety boundary and resource limits (ARCHITECTURE.md section 8).
     *
     * @param allowedRoots        canonicalized prefixes a scan target must sit under. An
     *                            <strong>empty list means refuse every scan</strong> - we fail
     *                            closed rather than defaulting to permissive, because the scan
     *                            path arrives over HTTP.
     * @param excludedDirectories pruned at directory level so we never descend into them
     * @param maxDepth            maximum directory nesting to walk
     * @param maxFiles            maximum number of files to inventory
     * @param maxFileSize         files larger than this are inventoried by name only, not read
     * @param maxTotalBytes       cumulative read budget across the whole scan
     * @param timeout             wall-clock ceiling for a single scan
     */
    public record Scan(
            @DefaultValue("./demo") List<String> allowedRoots,
            @DefaultValue({"target", "build", "out", "bin", ".git", ".idea", "node_modules", ".mvn"})
            List<String> excludedDirectories,
            @DefaultValue("25") int maxDepth,
            @DefaultValue("20000") int maxFiles,
            @DefaultValue("1MB") DataSize maxFileSize,
            @DefaultValue("200MB") DataSize maxTotalBytes,
            @DefaultValue("60s") Duration timeout) {

        /** Fail-closed check used by the scanner's guard: no allowlist, no scanning. */
        public boolean hasAllowedRoots() {
            return allowedRoots != null && !allowedRoots.isEmpty();
        }
    }

    /**
     * LLM provider configuration and the outbound-data budget (ARCHITECTURE.md sections 9 and 10).
     *
     * @param enabled             egress consent gate. Defaults to {@code false}: project-derived
     *                            data does not leave the machine unless this is switched on, or the
     *                            request explicitly opts in. Static analysis works air-gapped.
     * @param provider            selects the {@code LlmClient} implementation; {@code mock} needs
     *                            no API key and is what makes Step 4 demoable offline
     * @param model               provider-specific model id
     * @param reasoningLevel      portable hint (LOW/MEDIUM/HIGH) each adapter maps to its own
     *                            mechanism - adaptive thinking, thinking budget, reasoning effort
     * @param cacheSystemPrompt   portable hint; the Anthropic adapter turns this into an explicit
     *                            cache breakpoint so repeated demo runs are cheaper and faster
     * @param maxInputTokens      measured ceiling; exceeding it degrades the payload visibly and
     *                            sets {@code meta.truncated}, never truncates silently
     * @param maxEvidenceSnippets hard cap on code snippets included in the payload
     * @param maxSnippetChars     hard cap on the size of each snippet
     */
    public record Ai(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("anthropic") String provider,
            @DefaultValue("claude-opus-5") String model,
            @DefaultValue("HIGH") String reasoningLevel,
            @DefaultValue("true") boolean cacheSystemPrompt,
            @DefaultValue("150000") int maxInputTokens,
            @DefaultValue("16000") int maxOutputTokens,
            @DefaultValue("40") int maxEvidenceSnippets,
            @DefaultValue("200") int maxSnippetChars,
            @DefaultValue("120s") Duration timeout) {

        /**
         * Whether a credential is present in the environment. Reports presence only - the value is
         * never read into the application, logged, or serialized.
         */
        public boolean apiKeyPresent() {
            String key = System.getenv(API_KEY_ENV_VAR);
            return key != null && !key.isBlank();
        }
    }
}

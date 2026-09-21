package com.company.modernizer.analyzer.properties;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import com.company.modernizer.analyzer.Analyzer;
import com.company.modernizer.analyzer.FindingBuilder;
import com.company.modernizer.analyzer.support.FileText;
import com.company.modernizer.analyzer.support.SecretMasking;
import com.company.modernizer.model.EffortSize;
import com.company.modernizer.model.Evidence;
import com.company.modernizer.model.FileKind;
import com.company.modernizer.model.Finding;
import com.company.modernizer.model.FindingCategory;
import com.company.modernizer.model.ProjectContext;
import com.company.modernizer.model.RiskLevel;
import com.company.modernizer.model.ScannedFile;
import com.company.modernizer.model.Severity;

import org.springframework.stereotype.Component;

/**
 * Detects credentials and environment coupling in {@code .properties} and YAML configuration.
 *
 * <p>The fourth analyzer, and the one that exists to demonstrate the SPI claim in
 * ARCHITECTURE.md section 5a: it was added after the other three, is registered only by
 * {@code @Component}, and required no change to the orchestrator, the controller, or any other
 * analyzer. Adding SOAP-to-REST mapping or a CVE feed later costs exactly this much.
 *
 * <p><strong>Every snippet is masked before it becomes evidence.</strong> These rules read files
 * chosen precisely because they contain secrets, and the finding is returned over HTTP. Quoting the
 * matched line verbatim would copy the credential into the report, the access log, and later the
 * LLM payload. The presence is the finding; the value never is.
 */
@Component
public class PropertiesConfigAnalyzer implements Analyzer {

    /** Unix absolute path with at least one segment, or a Windows drive-letter path. */
    private static final Pattern ABSOLUTE_PATH =
            Pattern.compile("^(/[^\\s/][^\\s]*|[A-Za-z]:[\\\\/][^\\s]*)$");

    /** Keys whose value is meant to be a filesystem location. */
    private static final Pattern PATH_KEY =
            Pattern.compile("(?i)(dir|directory|path|folder|location|file|home)$");

    /**
     * Keys describing state the application keeps on local disk.
     *
     * <p>Distinct from a merely absolute path: a read-only template directory is awkward to
     * containerise, whereas a session or upload directory is what actually stops a second instance
     * from working.
     */
    private static final Pattern LOCAL_STATE_KEY =
            Pattern.compile("(?i)(session|upload|tmp|temp|cache|queue|spool|lock|archive|outbox)");

    private static final int MAX_EVIDENCE = 15;

    @Override
    public boolean supports(ProjectContext context) {
        return !configFiles(context).isEmpty();
    }

    @Override
    public List<Finding> analyze(ProjectContext context) {
        List<Finding> findings = new ArrayList<>();

        plaintextCredentials(context).ifPresent(findings::add);
        hardcodedPaths(context).ifPresent(findings::add);
        localFilesystemState(context).ifPresent(findings::add);

        return findings;
    }

    // --- Credentials --------------------------------------------------------------------------

    private Optional<Finding> plaintextCredentials(ProjectContext context) {
        List<Evidence> evidence = new ArrayList<>();
        int total = 0;
        boolean embeddedInUrl = false;

        for (ScannedFile file : configFiles(context)) {
            List<String> lines = FileText.lines(context, file);
            for (int i = 0; i < lines.size(); i++) {
                Setting setting = Setting.parse(lines.get(i));
                if (setting == null || setting.isExternalized()) {
                    continue;
                }

                boolean secretKey = SecretMasking.isSecretKey(setting.key());
                boolean secretValue = SecretMasking.containsEmbeddedSecret(setting.value());
                if (!secretKey && !secretValue) {
                    continue;
                }

                total++;
                embeddedInUrl |= secretValue && !secretKey;
                if (evidence.size() < MAX_EVIDENCE) {
                    // Masked, not quoted. See the class note.
                    evidence.add(Evidence.of(file.relativePath(), i + 1,
                            SecretMasking.maskAssignment(lines.get(i))));
                }
            }
        }

        if (evidence.isEmpty()) {
            return Optional.empty();
        }
        if (total > evidence.size()) {
            evidence.add(Evidence.ofFile("... and " + (total - evidence.size()) + " more settings"));
        }

        return Optional.of(FindingBuilder
                .of("plaintext-credentials", FindingCategory.SECURITY,
                        total + " plaintext " + (total == 1 ? "credential is" : "credentials are")
                                + " committed in configuration files")
                .severity(Severity.CRITICAL)
                .evidence(evidence)
                .impact("These values are in version control, so they are on every developer "
                        + "machine, in every clone, in every CI cache, and in the history even "
                        + "after the file is changed. Anyone who has ever had read access to the "
                        + "repository has them, including people who have since left. Rotating "
                        + "them is the only remedy, and the fact that they are here means nobody "
                        + "can say when they were last rotated."
                        + (embeddedInUrl
                        ? " At least one is embedded in a connection URL, where it also "
                        + "reaches logs and stack traces that print the URL."
                        : ""))
                .recommendation("Treat every value here as compromised and rotate it first - "
                        + "moving a leaked secret to a vault leaves it leaked. Then externalise: "
                        + "read them from environment variables or a secrets manager, and keep the "
                        + "key names in the file with empty or placeholder values so the required "
                        + "configuration is still self-documenting. Purging the git history is "
                        + "worth doing but is not a substitute for rotation.")
                .states("plaintext in version control", "injected from a secrets manager")
                .cost(EffortSize.S, RiskLevel.MEDIUM)
                .references("https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html")
                .build());
    }

    // --- Environment coupling -------------------------------------------------------------------

    private Optional<Finding> hardcodedPaths(ProjectContext context) {
        List<Evidence> evidence = new ArrayList<>();
        int total = 0;

        for (ScannedFile file : configFiles(context)) {
            List<String> lines = FileText.lines(context, file);
            for (int i = 0; i < lines.size(); i++) {
                Setting setting = Setting.parse(lines.get(i));
                if (setting == null || setting.isExternalized() || !setting.isAbsolutePath()) {
                    continue;
                }
                total++;
                if (evidence.size() < MAX_EVIDENCE) {
                    evidence.add(Evidence.of(file.relativePath(), i + 1,
                            SecretMasking.maskAssignment(lines.get(i))));
                }
            }
        }

        if (evidence.isEmpty()) {
            return Optional.empty();
        }
        if (total > evidence.size()) {
            evidence.add(Evidence.ofFile("... and " + (total - evidence.size()) + " more settings"));
        }

        return Optional.of(FindingBuilder
                .of("hardcoded-absolute-paths", FindingCategory.CLOUD_READINESS,
                        total + " hardcoded absolute filesystem "
                                + (total == 1 ? "path" : "paths") + " in configuration")
                .severity(Severity.MEDIUM)
                .evidence(evidence)
                .impact("Each of these assumes a specific machine with a specific directory layout "
                        + "already created and writable. In a container the path does not exist, "
                        + "and on a second instance it is a different disk. Mixed Windows and Unix "
                        + "paths in the same file also mean the configuration is only ever correct "
                        + "on one of the two platforms it is deployed to.")
                .recommendation("Make every one of them a configurable property with a sane "
                        + "relative default, supplied per environment. Where the path points at "
                        + "shared state rather than a local scratch area, the fix is to move the "
                        + "state to object storage or a database instead of parameterising the "
                        + "path.")
                .cost(EffortSize.S, RiskLevel.LOW)
                .build());
    }

    private Optional<Finding> localFilesystemState(ProjectContext context) {
        List<Evidence> evidence = new ArrayList<>();

        for (ScannedFile file : configFiles(context)) {
            List<String> lines = FileText.lines(context, file);
            for (int i = 0; i < lines.size() && evidence.size() < MAX_EVIDENCE; i++) {
                Setting setting = Setting.parse(lines.get(i));
                if (setting == null || setting.isExternalized()) {
                    continue;
                }
                boolean stateKey = LOCAL_STATE_KEY.matcher(setting.key()).find();
                boolean pathValued = setting.isAbsolutePath()
                        || PATH_KEY.matcher(setting.key()).find();
                if (stateKey && pathValued) {
                    evidence.add(Evidence.of(file.relativePath(), i + 1,
                            SecretMasking.maskAssignment(lines.get(i))));
                }
            }
        }

        if (evidence.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(FindingBuilder
                .of("local-filesystem-state", FindingCategory.CLOUD_READINESS,
                        "Application state is written to the local filesystem")
                .severity(Severity.HIGH)
                .evidence(evidence)
                .impact("Sessions, uploads and spooled work held on local disk are lost when the "
                        + "instance is replaced, and are invisible to any other instance. This is "
                        + "the concrete reason the application cannot be scaled horizontally or "
                        + "deployed with a rolling restart: a second copy does not see the first "
                        + "copy's files, and a redeployed copy has lost its own.")
                .recommendation("Classify each location before moving it. Scratch space that is "
                        + "safe to lose can stay on an ephemeral volume. Anything a user would "
                        + "notice losing - uploads, generated documents, queued work - belongs in "
                        + "object storage or a database. Session state belongs in a shared session "
                        + "store. This is normally the last blocker to clear before the "
                        + "application can run as more than one instance.")
                .states("local disk", "object storage or a shared store")
                .cost(EffortSize.L, RiskLevel.MEDIUM)
                .build());
    }

    // --- Parsing -----------------------------------------------------------------------------------

    /**
     * One {@code key=value} or {@code key: value} setting.
     *
     * <p>Line-oriented on purpose. A real YAML parse would resolve nesting into dotted keys and be
     * more accurate, but these rules only ever ask "does this key name a secret" and "is this value
     * an absolute path", and both survive reading a leaf line in isolation. The cost is that a
     * nested YAML key is judged on its last segment alone, which is why {@code SecretMasking}
     * matches key fragments rather than whole keys.
     */
    private record Setting(String key, String value) {

        static Setting parse(String rawLine) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")
                    || line.startsWith("-")) {
                return null;
            }

            int equals = line.indexOf('=');
            int colon = line.indexOf(':');
            int separator = (equals >= 0 && (colon < 0 || equals < colon)) ? equals : colon;
            if (separator <= 0 || separator == line.length() - 1) {
                return null;
            }

            String key = line.substring(0, separator).strip();
            String value = line.substring(separator + 1).strip();
            if (key.isEmpty() || value.isEmpty()) {
                return null;
            }
            // Strip surrounding quotes, which YAML and some properties files use.
            if (value.length() > 1
                    && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1);
            }
            return new Setting(key.toLowerCase(Locale.ROOT), value);
        }

        boolean isExternalized() {
            return SecretMasking.isPlaceholder(value);
        }

        boolean isAbsolutePath() {
            if (!ABSOLUTE_PATH.matcher(value).matches()) {
                return false;
            }
            // A URL path component is not a filesystem path, and neither is a JNDI name.
            if (value.contains("://") || value.startsWith("java:")) {
                return false;
            }
            // Windows drive-letter paths are unambiguous. Unix paths need key corroboration,
            // or '/orders' from a URL mapping would be reported as a filesystem location.
            return value.length() > 2 && value.charAt(1) == ':'
                    || PATH_KEY.matcher(key).find()
                    || LOCAL_STATE_KEY.matcher(key).find();
        }
    }

    private List<ScannedFile> configFiles(ProjectContext context) {
        return context.filesOfKind(FileKind.PROPERTIES, FileKind.YAML);
    }
}

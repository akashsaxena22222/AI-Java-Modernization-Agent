package com.company.modernizer.analyzer.xml;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import com.company.modernizer.analyzer.Analyzer;
import com.company.modernizer.analyzer.FindingBuilder;
import com.company.modernizer.analyzer.support.FileText;
import com.company.modernizer.model.Confidence;
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
 * Detects legacy patterns in the XML-configured web and persistence tiers.
 *
 * <p>Covers the JSP view layer as well as XML proper, because the two are the same subsystem here:
 * JSPs are wired by {@code web.xml} and a view resolver declared in XML, and a report that assessed
 * one without the other would be describing half a web tier.
 *
 * <p>Every rule reads file text, since XML structure is not something {@code ScannedFile} carries.
 * Detections are string-level rather than DOM-level, which is honest for the patterns here - a
 * DOCTYPE line and a {@code disabled="true"} attribute are unambiguous in text - and the rules that
 * infer more than they read say so through {@link Confidence}.
 */
@Component
public class XmlConfigAnalyzer implements Analyzer {

    /** A DTD-era descriptor, i.e. Servlet 2.3 or earlier. */
    private static final Pattern WEB_APP_DTD =
            Pattern.compile("(?i)<!DOCTYPE\\s+web-app", Pattern.DOTALL);

    /** {@code version="2.4"} on the web-app element. 3.0 is the first that allows annotations. */
    private static final Pattern WEB_APP_VERSION =
            Pattern.compile("(?i)<web-app[^>]*version\\s*=\\s*\"([0-9.]+)\"", Pattern.DOTALL);

    private static final Pattern CSRF_DISABLED =
            Pattern.compile("(?i)<csrf[^>]*disabled\\s*=\\s*\"true\"");

    private static final Pattern SECURITY_NONE =
            Pattern.compile("(?i)security\\s*=\\s*\"none\"");

    private static final Pattern HBM2DDL_MUTATING =
            Pattern.compile("(?i)hbm2ddl\\.auto.{0,40}?(update|create|create-drop)");

    private static final Pattern JSP_SCRIPTLET = Pattern.compile("<%[^@\\-=]|<%=");

    private static final int MAX_EVIDENCE = 12;

    @Override
    public boolean supports(ProjectContext context) {
        return context.metrics().xmlConfigFiles() > 0 || context.metrics().jspFiles() > 0;
    }

    @Override
    public List<Finding> analyze(ProjectContext context) {
        List<Finding> findings = new ArrayList<>();

        legacyWebDescriptor(context).ifPresent(findings::add);
        xmlBeanConfiguration(context).ifPresent(findings::add);
        csrfDisabled(context).ifPresent(findings::add);
        unsecuredPatterns(context).ifPresent(findings::add);
        mutatingSchema(context).ifPresent(findings::add);
        jspViewTier(context).ifPresent(findings::add);

        return findings;
    }

    // --- Web descriptor ---------------------------------------------------------------------------

    private Optional<Finding> legacyWebDescriptor(ProjectContext context) {
        for (ScannedFile descriptor : context.filesOfKind(FileKind.WEB_XML)) {
            List<String> lines = FileText.lines(context, descriptor);
            if (lines.isEmpty()) {
                continue;
            }

            Optional<FileText.Line> dtd = FileText.firstMatching(lines, WEB_APP_DTD);
            Optional<FileText.Line> versioned = FileText.firstMatching(lines, WEB_APP_VERSION);

            boolean legacy = dtd.isPresent();
            String declared = "DTD (Servlet 2.3 or earlier)";
            if (!legacy && versioned.isPresent()) {
                String version = versioned.get().text().replaceAll("(?s).*version\\s*=\\s*\"([0-9.]+)\".*", "$1");
                legacy = version.compareTo("3.0") < 0;
                declared = "Servlet " + version;
            }
            if (!legacy) {
                continue;
            }

            FileText.Line evidenceLine = dtd.or(() -> versioned).orElse(null);
            return Optional.of(FindingBuilder
                    .of("web-xml-legacy-descriptor", FindingCategory.XML_CONFIGURATION,
                            "Deployment descriptor declares " + declared)
                    .severity(Severity.MEDIUM)
                    .evidence(evidenceLine == null
                            ? Evidence.ofFile(descriptor.relativePath())
                            : Evidence.of(descriptor.relativePath(), evidenceLine.number(),
                            evidenceLine.snippet()))
                    .impact("A pre-3.0 descriptor means the container ignores annotations and web "
                            + "fragments entirely, so every servlet, filter and listener has to be "
                            + "declared here by hand. It also pins the application to a servlet "
                            + "container generation that modern embedded containers no longer "
                            + "emulate, and the DOCTYPE makes the parser fetch a DTD from "
                            + "java.sun.com - a host that no longer serves it, which is a startup "
                            + "delay and an outbound dependency nobody intended.")
                    .recommendation("Raise the descriptor to the Servlet 6.0 schema and delete the "
                            + "DOCTYPE. Do this as a mechanical step during the framework upgrade; "
                            + "most of the declarations here can then be deleted outright once "
                            + "component scanning is available.")
                    .states(declared, "Servlet 6.0 schema, or no descriptor at all")
                    .cost(EffortSize.S, RiskLevel.LOW)
                    .blockedBy("spring-framework-outdated")
                    .build());
        }
        return Optional.empty();
    }

    // --- Spring XML -------------------------------------------------------------------------------

    private Optional<Finding> xmlBeanConfiguration(ProjectContext context) {
        List<ScannedFile> springXml = context.filesOfKind(FileKind.SPRING_XML);
        if (springXml.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(FindingBuilder
                .of("xml-bean-configuration", FindingCategory.XML_CONFIGURATION,
                        springXml.size() + " XML bean definition "
                                + (springXml.size() == 1 ? "file" : "files")
                                + " instead of annotation or Java configuration")
                .severity(Severity.MEDIUM)
                .evidence(evidenceForFiles(springXml))
                .impact("Wiring declared in XML is invisible to the compiler and to the IDE: a "
                        + "renamed class or a mistyped property name becomes a startup failure "
                        + "rather than a build failure, and nothing can find the usages of a bean. "
                        + "It is also the configuration style with the least documentation and the "
                        + "fewest people left who are fluent in it.")
                .recommendation("Convert progressively rather than in one sweep. Import the "
                        + "existing XML from an @Configuration class with @ImportResource, then "
                        + "move definitions across a file at a time, deleting each XML file as it "
                        + "empties. Infrastructure beans first, since those change least.")
                .states(springXml.size() + " XML context files", "Java @Configuration")
                .cost(EffortSize.M, RiskLevel.MEDIUM)
                .build());
    }

    private Optional<Finding> csrfDisabled(ProjectContext context) {
        List<Evidence> evidence = matchesAcross(context, configFiles(context), CSRF_DISABLED);
        if (evidence.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(FindingBuilder
                .of("csrf-disabled", FindingCategory.SECURITY,
                        "CSRF protection is explicitly disabled")
                .severity(Severity.HIGH)
                .evidence(evidence)
                .impact("Without a CSRF token, any site a logged-in user visits can make their "
                        + "browser submit a state-changing request to this application, with their "
                        + "session cookie attached. For an application that processes orders or "
                        + "payroll, that is an attacker-triggered transaction that looks entirely "
                        + "legitimate in the audit log.")
                .recommendation("Re-enable it and fix what broke. It is almost always disabled "
                        + "because a form or an AJAX call started failing; the fix is to include "
                        + "the token in those requests, not to turn the protection off. If some "
                        + "endpoints are genuinely stateless APIs authenticated by a bearer token, "
                        + "exempt those specific paths rather than disabling globally.")
                .states("disabled", "enabled, with per-path exemptions where justified")
                .cost(EffortSize.S, RiskLevel.MEDIUM)
                .references("https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html")
                .build());
    }

    private Optional<Finding> unsecuredPatterns(ProjectContext context) {
        List<Evidence> evidence = matchesAcross(context, configFiles(context), SECURITY_NONE);
        if (evidence.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(FindingBuilder
                .of("url-pattern-unsecured", FindingCategory.SECURITY,
                        "URL patterns are configured to bypass the security filter chain")
                .severity(Severity.HIGH)
                .confidence(Confidence.MEDIUM)
                .evidence(evidence)
                .impact("A path declared with security=\"none\" skips authentication and "
                        + "authorization completely. This is frequently applied to a whole subtree "
                        + "in order to exempt static assets, and quietly exposes the service "
                        + "endpoints that happen to live beneath it.")
                .recommendation("Check what each exempted pattern actually matches today, not what "
                        + "it was intended to match when it was written. Narrow each one to the "
                        + "specific static paths involved, and move service endpoints out from "
                        + "under them.")
                .cost(EffortSize.S, RiskLevel.MEDIUM)
                .build());
    }

    private Optional<Finding> mutatingSchema(ProjectContext context) {
        List<Evidence> evidence = matchesAcross(context, configFiles(context), HBM2DDL_MUTATING);
        if (evidence.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(FindingBuilder
                .of("schema-auto-mutation", FindingCategory.SECURITY,
                        "Hibernate is configured to alter the database schema at startup")
                .severity(Severity.HIGH)
                .evidence(evidence)
                .impact("hbm2ddl.auto with update or create lets the application rewrite the "
                        + "schema on boot, driven by whatever the entity classes happen to say. "
                        + "There is no review, no migration history and no rollback; create-drop "
                        + "destroys data outright. It also means the production schema is whatever "
                        + "sequence of deployments produced it, which nobody can reconstruct.")
                .recommendation("Set this to validate everywhere, and move schema changes into "
                        + "versioned migrations with Flyway or Liquibase. Baseline the migration "
                        + "tool against the current production schema first - the schema is the "
                        + "source of truth here, not the entities.")
                .states("hbm2ddl.auto=update", "hbm2ddl.auto=validate plus versioned migrations")
                .cost(EffortSize.M, RiskLevel.MEDIUM)
                .build());
    }

    // --- View tier ---------------------------------------------------------------------------------

    private Optional<Finding> jspViewTier(ProjectContext context) {
        List<ScannedFile> jsps = context.filesOfKind(FileKind.JSP);
        if (jsps.isEmpty()) {
            return Optional.empty();
        }

        List<Evidence> scriptlets = matchesAcross(context, jsps, JSP_SCRIPTLET);
        boolean hasScriptlets = !scriptlets.isEmpty();

        return Optional.of(FindingBuilder
                .of("jsp-view-tier", FindingCategory.CODE_QUALITY,
                        jsps.size() + " server-rendered JSP "
                                + (jsps.size() == 1 ? "view" : "views")
                                + (hasScriptlets ? " containing Java scriptlets" : ""))
                .severity(hasScriptlets ? Severity.MEDIUM : Severity.LOW)
                .confidence(Confidence.MEDIUM)
                .evidence(hasScriptlets ? scriptlets : evidenceForFiles(jsps))
                .impact(hasScriptlets
                        ? "Java embedded in the view is compiled at runtime, cannot be unit "
                        + "tested, and is invisible to static analysis and refactoring tools. "
                        + "Business rules written here get duplicated from the service layer "
                        + "and then drift, so the screen and the API disagree about the same "
                        + "number. Scriptlet output is also unescaped by default, which makes "
                        + "every value written this way a cross-site scripting candidate."
                        : "A server-rendered JSP tier ties the presentation layer to the "
                        + "servlet container and rules out serving the same data to a "
                        + "single-page or mobile client without a second interface.")
                .recommendation("Move logic out of the pages before attempting to replace them: "
                        + "push calculations into the service layer and replace scriptlet output "
                        + "with JSTL c:out so escaping is on by default. That alone removes the "
                        + "XSS exposure and makes the behaviour testable, and it can be done "
                        + "page by page without committing to a front-end rewrite.")
                .cost(EffortSize.L, RiskLevel.MEDIUM)
                .build());
    }

    // --- Helpers ------------------------------------------------------------------------------------

    /** XML files a security or persistence setting could plausibly live in. */
    private List<ScannedFile> configFiles(ProjectContext context) {
        return context.filesOfKind(FileKind.SPRING_XML, FileKind.WEB_XML);
    }

    /** First match per file, capped, so one finding does not carry a hundred evidence entries. */
    private List<Evidence> matchesAcross(
            ProjectContext context, List<ScannedFile> files, Pattern pattern) {
        List<Evidence> evidence = new ArrayList<>();
        for (ScannedFile file : files) {
            if (evidence.size() >= MAX_EVIDENCE) {
                break;
            }
            FileText.matching(FileText.lines(context, file), pattern).stream()
                    .findFirst()
                    .ifPresent(line -> evidence.add(
                            Evidence.of(file.relativePath(), line.number(), line.snippet())));
        }
        return evidence;
    }

    private static List<Evidence> evidenceForFiles(List<ScannedFile> files) {
        List<Evidence> evidence = new ArrayList<>();
        files.stream().limit(MAX_EVIDENCE)
                .forEach(file -> evidence.add(Evidence.ofFile(file.relativePath())));
        if (files.size() > MAX_EVIDENCE) {
            evidence.add(Evidence.ofFile(
                    "... and " + (files.size() - MAX_EVIDENCE) + " more files"));
        }
        return evidence;
    }
}

package com.company.modernizer.analyzer.source;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * Detects legacy patterns in Java sources.
 *
 * <p>Almost everything here reads the import block and top-level annotations the scanner already
 * extracted, so no file is opened twice and most rules cost nothing. Only the two rules that need
 * a statement rather than a declaration - weak hashing and session state - read file text, and
 * those are marked {@link Confidence#MEDIUM} because a line scan is not a parse.
 *
 * <p>ARCHITECTURE.md section 7 is explicit that JavaParser is not a Phase 1 dependency: the
 * detections here are reliably line-scannable, and an AST earns its place in Phase 2 when real
 * complexity metrics and AST-based transformation arrive.
 */
@Component
public class JavaSourceAnalyzer implements Analyzer {

    /**
     * {@code javax} packages that moved to {@code jakarta}, mapped to what they became.
     *
     * <p>Only the ones that actually moved. {@code javax.sql}, {@code javax.naming} and
     * {@code javax.crypto} are JDK packages that did <em>not</em> move, and listing them would
     * produce a confidently wrong finding.
     */
    private static final Map<String, String> JAVAX_TO_JAKARTA = new LinkedHashMap<>();

    static {
        JAVAX_TO_JAKARTA.put("javax.servlet", "jakarta.servlet");
        JAVAX_TO_JAKARTA.put("javax.persistence", "jakarta.persistence");
        JAVAX_TO_JAKARTA.put("javax.validation", "jakarta.validation");
        JAVAX_TO_JAKARTA.put("javax.annotation", "jakarta.annotation");
        JAVAX_TO_JAKARTA.put("javax.ejb", "jakarta.ejb");
        JAVAX_TO_JAKARTA.put("javax.transaction", "jakarta.transaction");
        JAVAX_TO_JAKARTA.put("javax.jws", "jakarta.jws");
        JAVAX_TO_JAKARTA.put("javax.xml.ws", "jakarta.xml.ws");
        JAVAX_TO_JAKARTA.put("javax.xml.bind", "jakarta.xml.bind");
        JAVAX_TO_JAKARTA.put("javax.mail", "jakarta.mail");
        JAVAX_TO_JAKARTA.put("javax.faces", "jakarta.faces");
        JAVAX_TO_JAKARTA.put("javax.enterprise", "jakarta.enterprise");
        JAVAX_TO_JAKARTA.put("javax.inject", "jakarta.inject");
    }

    /** Weak digests, matched inside a MessageDigest lookup rather than anywhere in the file. */
    private static final Pattern WEAK_DIGEST = Pattern.compile(
            "(?i)MessageDigest\\s*\\.\\s*getInstance\\s*\\(\\s*\"?\\s*(MD5|MD2|SHA-?1)\\b");

    private static final Pattern HTTP_SESSION_WRITE =
            Pattern.compile("setAttribute\\s*\\(|getSession\\s*\\(");

    /** Below this, the project cannot be refactored safely - the tests will not catch the damage. */
    private static final double THIN_COVERAGE_RATIO = 0.20;

    /** Evidence lists are capped: forty identical file references help nobody. */
    private static final int MAX_EVIDENCE = 12;

    @Override
    public boolean supports(ProjectContext context) {
        return context.metrics().javaFiles() > 0 || context.metrics().testFiles() > 0;
    }

    @Override
    public List<Finding> analyze(ProjectContext context) {
        List<Finding> findings = new ArrayList<>();

        javaxNamespace(context).ifPresent(findings::add);
        soapEndpoints(context).ifPresent(findings::add);
        soapToRest(context).ifPresent(findings::add);
        removedInternalApi(context).ifPresent(findings::add);
        junit3(context).ifPresent(findings::add);
        thinTestCoverage(context).ifPresent(findings::add);
        weakHashing(context).ifPresent(findings::add);
        sessionState(context).ifPresent(findings::add);

        return findings;
    }

    // --- Namespace and API migration ------------------------------------------------------------

    private Optional<Finding> javaxNamespace(ProjectContext context) {
        Map<String, List<ScannedFile>> byPackage = new LinkedHashMap<>();
        for (Map.Entry<String, String> moved : JAVAX_TO_JAKARTA.entrySet()) {
            List<ScannedFile> users = context.filesImporting(moved.getKey() + ".");
            if (!users.isEmpty()) {
                byPackage.put(moved.getKey(), users);
            }
        }
        if (byPackage.isEmpty()) {
            return Optional.empty();
        }

        long affectedFiles = byPackage.values().stream()
                .flatMap(List::stream)
                .map(ScannedFile::relativePath)
                .distinct()
                .count();

        String packages = String.join(", ", byPackage.keySet());

        return Optional.of(FindingBuilder
                .of("javax-namespace", FindingCategory.DEPRECATED_API,
                        affectedFiles + " source "
                                + (affectedFiles == 1 ? "file uses" : "files use")
                                + " javax.* packages that moved to jakarta.*")
                .severity(Severity.HIGH)
                .evidence(evidenceForFiles(byPackage.values().stream()
                        .flatMap(List::stream)
                        .distinct()
                        .toList()))
                .impact("Jakarta EE 9 renamed these packages outright. Spring Boot 3, Tomcat 10 and "
                        + "Hibernate 6 accept only the jakarta.* names, so every one of these "
                        + "imports has to change before any of those upgrades can complete. The "
                        + "rename cannot be done incrementally inside one deployable: the two "
                        + "namespaces are different types and do not interoperate. Packages in "
                        + "use here: " + packages + ".")
                .recommendation("Do this as one mechanical commit, separate from any behavioural "
                        + "change, using the OpenRewrite jakarta migration recipe rather than a "
                        + "find-and-replace - some artifacts changed coordinates as well as "
                        + "packages. Verify that every third-party library on the classpath has a "
                        + "jakarta-compatible release first; that check is what determines whether "
                        + "this is feasible at all.")
                .states("javax.* (Java EE)", "jakarta.* (Jakarta EE 9+)")
                .cost(EffortSize.M, RiskLevel.MEDIUM)
                .blockedBy("java-version-outdated")
                .references("https://jakarta.ee/blogs/javax-jakartaee-namespace-ecosystem-progress/")
                .build());
    }

    private Optional<Finding> removedInternalApi(ProjectContext context) {
        // 'sun.' only. com.sun.* covers plenty of legitimately shipped RI classes, and flagging
        // those would be noise rather than a finding.
        List<ScannedFile> users = context.filesImporting("sun.");
        if (users.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(FindingBuilder
                .of("jdk-internal-api", FindingCategory.DEPRECATED_API,
                        users.size() + " source "
                                + (users.size() == 1 ? "file imports" : "files import")
                                + " internal sun.* JDK classes")
                .severity(Severity.HIGH)
                .evidence(evidenceForFiles(users))
                .impact("These classes were never public API. Most were made inaccessible by the "
                        + "module system in Java 9 and several were deleted outright, so this code "
                        + "will not compile on a modern JDK at all. It is a hard blocker on the "
                        + "language upgrade rather than a warning.")
                .recommendation("Replace each one with its supported equivalent before attempting "
                        + "the JDK upgrade - sun.misc.BASE64Encoder becomes java.util.Base64, "
                        + "sun.misc.Unsafe usually becomes VarHandle. Run jdeps --jdk-internals "
                        + "over the built classes to find the ones that are called reflectively "
                        + "and so are invisible to an import scan.")
                .cost(EffortSize.S, RiskLevel.LOW)
                .blocks("java-version-outdated")
                .references("https://docs.oracle.com/en/java/javase/21/migrate/")
                .build());
    }

    // --- SOAP ------------------------------------------------------------------------------------

    private Optional<Finding> soapEndpoints(ProjectContext context) {
        List<ScannedFile> annotated = context.filesAnnotatedWith("WebService");
        List<ScannedFile> importing = context.filesImporting("javax.jws");
        List<ScannedFile> endpoints = distinct(annotated, importing);

        if (endpoints.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(FindingBuilder
                .of("soap-jaxws-endpoints", FindingCategory.SOAP_WEBSERVICE,
                        endpoints.size() + " JAX-WS SOAP "
                                + (endpoints.size() == 1 ? "endpoint" : "endpoints") + " in use")
                .severity(Severity.MEDIUM)
                .evidence(evidenceForFiles(endpoints))
                .impact("JAX-WS was removed from the JDK in Java 11 and has to be added back as an "
                        + "explicit dependency, then migrated to the jakarta namespace. The "
                        + "toolchain around it - wsimport, generated stubs, the WSDL contract - is "
                        + "the least maintained part of most builds of this age.")
                .recommendation("Keep SOAP working through the platform upgrade first: add the "
                        + "jakarta.xml.ws dependencies explicitly and get it compiling on Java 21 "
                        + "before considering a protocol change. Replacing SOAP and upgrading the "
                        + "platform at the same time makes a failure impossible to attribute.")
                .cost(EffortSize.M, RiskLevel.MEDIUM)
                .build());
    }

    private Optional<Finding> soapToRest(ProjectContext context) {
        List<ScannedFile> contracts = context.filesOfKind(FileKind.WSDL);
        boolean hasEndpoints = !distinct(
                context.filesAnnotatedWith("WebService"),
                context.filesImporting("javax.jws")).isEmpty();

        if (contracts.isEmpty() || !hasEndpoints) {
            return Optional.empty();
        }

        return Optional.of(FindingBuilder
                .of("soap-to-rest", FindingCategory.SOAP_TO_REST,
                        contracts.size() + " WSDL "
                                + (contracts.size() == 1 ? "contract is" : "contracts are")
                                + " a candidate for a REST interface")
                .severity(Severity.LOW)
                .confidence(Confidence.MEDIUM)
                .evidence(evidenceForFiles(contracts))
                .impact("Every consumer needs a SOAP stack and generated stubs to call this. A REST "
                        + "and JSON interface would widen the set of clients that can integrate and "
                        + "remove the code generation step from their builds.")
                .recommendation("Do not rewrite in place. Add REST endpoints alongside the SOAP "
                        + "ones over the same service layer, move consumers across one at a time, "
                        + "and retire the SOAP endpoint only when its last caller is gone. Each "
                        + "WSDL operation maps to a resource and verb - the operation names here "
                        + "suggest a straightforward mapping.")
                .states("SOAP / WSDL", "REST + JSON, SOAP retained during transition")
                .cost(EffortSize.L, RiskLevel.MEDIUM)
                .blockedBy("soap-jaxws-endpoints")
                .build());
    }

    // --- Testing ----------------------------------------------------------------------------------

    private Optional<Finding> junit3(ProjectContext context) {
        List<ScannedFile> users = context.filesImporting("junit.framework");
        if (users.isEmpty()) {
            return Optional.empty();
        }

        String declared = context.dependency("junit", "junit")
                .map(d -> d.version() == null ? "an unresolved version" : d.version())
                .orElse("an undeclared version");

        return Optional.of(FindingBuilder
                .of("junit3-api", FindingCategory.TESTING,
                        users.size() + " test "
                                + (users.size() == 1 ? "class uses" : "classes use")
                                + " the JUnit 3 API")
                .severity(Severity.MEDIUM)
                .evidence(evidenceForFiles(users))
                .impact("JUnit 3 (declared here as " + declared + ") predates annotations, so tests "
                        + "are discovered by the testXxx naming convention and a typo silently "
                        + "means the test never runs. There are no parameterised tests, no "
                        + "assumptions, and no assertThrows, which is why exception tests here are "
                        + "written as try/fail/catch blocks that pass when the call does nothing.")
                .recommendation("Move to JUnit 5 with the vintage engine first, so the existing "
                        + "tests keep running unchanged while new ones are written against the "
                        + "modern API. Migrate the old classes opportunistically rather than in a "
                        + "single sweep. Do this before the framework upgrade: these tests are the "
                        + "only evidence the upgrade did not break anything.")
                .states("JUnit 3 (" + declared + ")", "JUnit 5 with the vintage engine")
                .cost(EffortSize.S, RiskLevel.LOW)
                .blocks("java-version-outdated")
                .references("https://junit.org/junit5/docs/current/user-guide/#migrating-from-junit4")
                .build());
    }

    private Optional<Finding> thinTestCoverage(ProjectContext context) {
        double ratio = context.metrics().testToSourceRatio();
        int testFiles = context.metrics().testFiles();
        int sourceFiles = context.metrics().javaFiles();

        if (sourceFiles == 0 || ratio >= THIN_COVERAGE_RATIO) {
            return Optional.empty();
        }

        return Optional.of(FindingBuilder
                .of("test-coverage-thin", FindingCategory.TESTING,
                        testFiles == 0
                                ? "No test classes at all for " + sourceFiles + " source files"
                                : testFiles + " test " + (testFiles == 1 ? "class" : "classes")
                                + " for " + sourceFiles + " source files")
                .severity(testFiles == 0 ? Severity.HIGH : Severity.MEDIUM)
                // A file-count ratio is a proxy for coverage, not a measurement of it.
                .confidence(Confidence.MEDIUM)
                .evidence(testFiles == 0
                        ? List.of(Evidence.ofFile("."))
                        : evidenceForFiles(context.filesOfKind(FileKind.JAVA_TEST)))
                .impact("This is the finding that gates every other item on this list. Each "
                        + "upgrade below is a large, mechanical change across the codebase, and "
                        + "the only thing that makes such a change safe is a test suite that fails "
                        + "when it goes wrong. At a test-to-source ratio of "
                        + String.format("%.2f", ratio) + " there is no such safety net, so every "
                        + "migration has to be verified by hand.")
                .recommendation("Do not chase a coverage percentage. Identify the handful of "
                        + "business paths whose failure would actually matter - the money paths - "
                        + "and put characterisation tests around those first, asserting current "
                        + "behaviour rather than intended behaviour. That is enough to make the "
                        + "upgrades verifiable, and it is the cheapest risk reduction available.")
                .cost(EffortSize.M, RiskLevel.LOW)
                .blocks("java-version-outdated", "spring-framework-outdated", "javax-namespace")
                .build());
    }

    // --- Security and cloud readiness ---------------------------------------------------------------

    private Optional<Finding> weakHashing(ProjectContext context) {
        List<Evidence> evidence = new ArrayList<>();
        for (ScannedFile file : javaFiles(context)) {
            FileText.matching(FileText.lines(context, file), WEAK_DIGEST).stream()
                    .findFirst()
                    .ifPresent(line -> evidence.add(
                            Evidence.of(file.relativePath(), line.number(), line.snippet())));
            if (evidence.size() >= MAX_EVIDENCE) {
                break;
            }
        }
        if (evidence.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(FindingBuilder
                .of("weak-password-hashing", FindingCategory.SECURITY,
                        "Passwords hashed with a broken digest (MD5 or SHA-1)")
                .severity(Severity.HIGH)
                .confidence(Confidence.MEDIUM)
                .evidence(evidence)
                .impact("MD5 and SHA-1 are fast general-purpose digests, which is exactly the wrong "
                        + "property for a password: commodity hardware tries billions of candidates "
                        + "per second, and unsalted hashes fall to a precomputed table immediately. "
                        + "If this database is ever disclosed, treat every password as known.")
                .recommendation("Move to bcrypt or Argon2id. Do it without a mass reset: store the "
                        + "new format alongside the old, and on each successful login re-hash the "
                        + "password the user just proved they know. Spring Security's "
                        + "DelegatingPasswordEncoder handles the prefixed multi-format storage "
                        + "this needs. Also replace the equals() comparison with a constant-time "
                        + "check.")
                .states("MD5/SHA-1, unsalted", "bcrypt or Argon2id, per-password salt")
                .cost(EffortSize.M, RiskLevel.MEDIUM)
                .references("https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html")
                .build());
    }

    private Optional<Finding> sessionState(ProjectContext context) {
        List<ScannedFile> candidates = context.filesImporting("javax.servlet.http.HttpSession");
        if (candidates.isEmpty()) {
            candidates = context.filesImporting("jakarta.servlet.http.HttpSession");
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        List<Evidence> evidence = new ArrayList<>();
        for (ScannedFile file : candidates) {
            FileText.matching(FileText.lines(context, file), HTTP_SESSION_WRITE).stream()
                    .findFirst()
                    .ifPresent(line -> evidence.add(
                            Evidence.of(file.relativePath(), line.number(), line.snippet())));
            if (evidence.size() >= MAX_EVIDENCE) {
                break;
            }
        }
        if (evidence.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(FindingBuilder
                .of("http-session-state", FindingCategory.CLOUD_READINESS,
                        "Application state is held in the in-memory HTTP session")
                .severity(Severity.MEDIUM)
                .confidence(Confidence.MEDIUM)
                .evidence(evidence)
                .impact("Session state in process memory ties each user to one instance. That "
                        + "forces sticky sessions in front of any second instance, makes a rolling "
                        + "deployment lose whatever users were part-way through, and rules out "
                        + "scaling to zero or scaling out on demand. It is usually the single "
                        + "biggest obstacle to running more than one copy of an application.")
                .recommendation("Externalise it. Spring Session backed by Redis moves this out of "
                        + "process with no change to the servlet API calls. Where the session is "
                        + "only caching something re-derivable, delete it instead - that is cheaper "
                        + "than moving it.")
                .states("in-memory session, sticky routing", "externalised session store")
                .cost(EffortSize.M, RiskLevel.MEDIUM)
                .build());
    }

    // --- Helpers -----------------------------------------------------------------------------------

    private List<ScannedFile> javaFiles(ProjectContext context) {
        return context.filesOfKind(FileKind.JAVA_SOURCE, FileKind.JAVA_TEST);
    }

    @SafeVarargs
    private static List<ScannedFile> distinct(List<ScannedFile>... groups) {
        Map<String, ScannedFile> byPath = new LinkedHashMap<>();
        for (List<ScannedFile> group : groups) {
            group.forEach(file -> byPath.putIfAbsent(file.relativePath(), file));
        }
        return List.copyOf(byPath.values());
    }

    /**
     * Whole-file evidence for up to {@link #MAX_EVIDENCE} files, with a final entry naming how many
     * were omitted so the count in the title always reconciles with the list.
     */
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

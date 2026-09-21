package com.company.modernizer.model;

/**
 * Classification assigned to every scanned file by the scanner's {@code FileClassifier}
 * (ARCHITECTURE.md section 8, stage 3).
 *
 * <p>The classification is purely mechanical - by filename and path. Analyzers then request the
 * kinds they care about, so no analyzer ever re-walks the directory tree.
 */
public enum FileKind {

    MAVEN_POM,
    GRADLE_BUILD,
    /**
     * An Ant {@code build.xml}.
     *
     * <p>Added once a real Ant fixture showed the cost of its absence: the build file classified
     * as {@code OTHER}, so no analyzer could ever be handed it, and the project reported zero
     * modules. A build tool we decline to analyze still has to be <em>visible</em>.
     */
    ANT_BUILD,
    JAVA_SOURCE,
    /** A {@code .java} file under {@code src/test}. */
    JAVA_TEST,
    WEB_XML,
    /** A Spring bean definition XML, e.g. {@code applicationContext.xml}. */
    SPRING_XML,
    PROPERTIES,
    YAML,
    WSDL,
    XSD,
    JSP,
    DOCKERFILE,
    CI_CONFIG,
    /** Inventoried by name only - binary, oversized, or credential-bearing. Never read. */
    NOT_READ,
    OTHER;

    public boolean isJava() {
        return this == JAVA_SOURCE || this == JAVA_TEST;
    }

    public boolean isXml() {
        return this == WEB_XML || this == SPRING_XML || this == WSDL || this == XSD;
    }
}

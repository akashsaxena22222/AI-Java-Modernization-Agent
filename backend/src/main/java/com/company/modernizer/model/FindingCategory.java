package com.company.modernizer.model;

/**
 * The kind of modernization opportunity a {@link Finding} represents.
 *
 * <p>These twelve values map one-to-one onto the detection areas the analyzer is required to cover
 * (ARCHITECTURE.md section 11). Serialized by {@code name()}, e.g. {@code "JAVA_VERSION"}.
 */
public enum FindingCategory {

    JAVA_VERSION("Java version"),
    SPRING_MODERNIZATION("Spring / Spring Boot"),
    DEPENDENCY_HEALTH("Dependency health"),
    DEPRECATED_API("Deprecated API usage"),
    SOAP_WEBSERVICE("SOAP / web services"),
    SOAP_TO_REST("SOAP-to-REST opportunity"),
    XML_CONFIGURATION("XML configuration"),
    SECURITY("Security"),
    TESTING("Testing"),
    BUILD("Build and packaging"),
    CODE_QUALITY("Code quality and maintainability"),
    CLOUD_READINESS("Cloud / AWS readiness");

    private final String displayName;

    FindingCategory(String displayName) {
        this.displayName = displayName;
    }

    /** Human-readable label for grouping headers in the Phase 2 UI and in report rendering. */
    public String displayName() {
        return displayName;
    }
}

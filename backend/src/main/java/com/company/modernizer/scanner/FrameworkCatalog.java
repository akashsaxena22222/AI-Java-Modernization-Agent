package com.company.modernizer.scanner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.company.modernizer.model.DependencyRef;
import com.company.modernizer.model.FrameworkRef;

import org.springframework.stereotype.Component;

/**
 * Maps Maven coordinates onto framework names.
 *
 * <p>A table lookup, not judgement, which is why it sits with the scanner's mechanical facts rather
 * than in an analyzer: "this project uses Hibernate 4.2.21" is a fact derivable from a declared
 * dependency. "Hibernate 4 blocks the jakarta migration" is the interpretation, and that belongs to
 * an {@code Analyzer}.
 *
 * <p>Keyed by {@code groupId:artifactId} with a prefix fallback, so
 * {@code org.springframework:spring-anything} still resolves to Spring Framework without
 * enumerating every module Spring ships.
 */
@Component
public class FrameworkCatalog {

    /**
     * Exact coordinate matches, checked first.
     *
     * <p>Ordered, and the first artifact of a framework to appear in the dependency list supplies
     * the reported version. That is why the canonical, version-bearing artifact of each framework
     * is listed rather than any incidental one.
     */
    private static final Map<String, String> BY_COORDINATES = new LinkedHashMap<>();

    /** Group-prefix fallbacks, checked in insertion order when no exact match applies. */
    private static final Map<String, String> BY_GROUP_PREFIX = new LinkedHashMap<>();

    static {
        BY_COORDINATES.put("org.springframework.boot:spring-boot", "Spring Boot");
        BY_COORDINATES.put("org.springframework.boot:spring-boot-starter", "Spring Boot");
        BY_COORDINATES.put("org.springframework:spring-core", "Spring Framework");
        BY_COORDINATES.put("org.springframework:spring-context", "Spring Framework");
        BY_COORDINATES.put("org.springframework:spring-webmvc", "Spring Framework");
        BY_COORDINATES.put("org.hibernate:hibernate-core", "Hibernate");
        BY_COORDINATES.put("org.hibernate.orm:hibernate-core", "Hibernate");
        BY_COORDINATES.put("junit:junit", "JUnit");
        BY_COORDINATES.put("org.junit.jupiter:junit-jupiter", "JUnit");
        BY_COORDINATES.put("org.junit.jupiter:junit-jupiter-api", "JUnit");
        BY_COORDINATES.put("log4j:log4j", "Log4j");
        BY_COORDINATES.put("org.apache.logging.log4j:log4j-core", "Log4j");
        BY_COORDINATES.put("org.slf4j:slf4j-api", "SLF4J");
        BY_COORDINATES.put("com.fasterxml.jackson.core:jackson-databind", "Jackson");
        BY_COORDINATES.put("javax.servlet:javax.servlet-api", "Servlet API");
        BY_COORDINATES.put("javax.servlet:servlet-api", "Servlet API");
        BY_COORDINATES.put("jakarta.servlet:jakarta.servlet-api", "Servlet API");
        BY_COORDINATES.put("javax.xml.ws:jaxws-api", "JAX-WS");
        BY_COORDINATES.put("com.sun.xml.ws:jaxws-rt", "JAX-WS");
        BY_COORDINATES.put("javax.xml.bind:jaxb-api", "JAXB");
        BY_COORDINATES.put("javax.persistence:persistence-api", "JPA");
        BY_COORDINATES.put("javax.persistence:javax.persistence-api", "JPA");
        BY_COORDINATES.put("org.apache.struts:struts2-core", "Struts");
        BY_COORDINATES.put("struts:struts", "Struts");
        BY_COORDINATES.put("commons-collections:commons-collections", "Commons Collections");
        BY_COORDINATES.put("commons-httpclient:commons-httpclient", "Commons HttpClient");
        BY_COORDINATES.put("xerces:xercesImpl", "Xerces");
        BY_COORDINATES.put("org.apache.axis:axis", "Apache Axis");
        BY_COORDINATES.put("org.apache.cxf:cxf-rt-frontend-jaxws", "Apache CXF");

        BY_GROUP_PREFIX.put("org.springframework.security", "Spring Security");
        BY_GROUP_PREFIX.put("org.springframework.boot", "Spring Boot");
        BY_GROUP_PREFIX.put("org.springframework", "Spring Framework");
        BY_GROUP_PREFIX.put("org.hibernate", "Hibernate");
        BY_GROUP_PREFIX.put("org.apache.logging.log4j", "Log4j");
        BY_GROUP_PREFIX.put("com.fasterxml.jackson", "Jackson");
        BY_GROUP_PREFIX.put("org.apache.cxf", "Apache CXF");
        BY_GROUP_PREFIX.put("org.apache.struts", "Struts");
    }

    /**
     * Resolves the dependency list into distinct detected frameworks.
     *
     * <p>Deduplicated by framework name, keeping the first declaration encountered - a project
     * declaring six Spring modules has used Spring once, not six times. Dependencies with no
     * resolvable version are skipped here rather than reported as {@code "Spring Framework ?"};
     * the missing version is itself reported by {@code MavenPomAnalyzer}.
     */
    public List<FrameworkRef> detect(List<DependencyRef> dependencies) {
        Map<String, FrameworkRef> byName = new LinkedHashMap<>();
        for (DependencyRef dependency : dependencies) {
            String framework = nameFor(dependency);
            if (framework == null || dependency.version() == null) {
                continue;
            }
            byName.putIfAbsent(framework, new FrameworkRef(
                    framework, dependency.version(), dependency.declaredIn()));
        }
        return List.copyOf(new ArrayList<>(byName.values()));
    }

    /** The framework a single dependency belongs to, or {@code null} if it is not in the catalog. */
    public String nameFor(DependencyRef dependency) {
        String exact = BY_COORDINATES.get(dependency.groupArtifact());
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, String> candidate : BY_GROUP_PREFIX.entrySet()) {
            if (dependency.groupId().equals(candidate.getKey())
                    || dependency.groupId().startsWith(candidate.getKey() + ".")) {
                return candidate.getValue();
            }
        }
        return null;
    }
}

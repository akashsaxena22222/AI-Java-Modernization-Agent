package com.company.modernizer.model;

/**
 * A declared build dependency.
 *
 * <p>Extracted mechanically from {@code pom.xml} by the scanner - interpreting whether a version is
 * <em>outdated</em> is an analyzer's job, not this record's.
 *
 * @param version    resolved version, or {@code null} when it comes from {@code dependencyManagement}
 *                   or a parent POM and could not be interpolated. A null version is itself worth
 *                   reporting, so it is preserved rather than defaulted.
 * @param scope      Maven scope; {@code "compile"} when unspecified
 * @param managed    whether the version came from {@code dependencyManagement} rather than the
 *                   declaration itself
 * @param declaredIn project-relative path of the POM that declared it, so multi-module projects
 *                   remain attributable
 */
public record DependencyRef(
        String groupId,
        String artifactId,
        String version,
        String scope,
        boolean managed,
        String declaredIn) {

    /** Maven coordinates as {@code groupId:artifactId:version}, with {@code ?} for an unknown version. */
    public String coordinates() {
        return groupId + ":" + artifactId + ":" + (version == null ? "?" : version);
    }

    /** Group and artifact only - the stable identity used for lookups against known-legacy tables. */
    public String groupArtifact() {
        return groupId + ":" + artifactId;
    }

    public boolean matches(String groupId, String artifactId) {
        return this.groupId.equals(groupId) && this.artifactId.equals(artifactId);
    }

    public boolean isTestScoped() {
        return "test".equals(scope);
    }
}

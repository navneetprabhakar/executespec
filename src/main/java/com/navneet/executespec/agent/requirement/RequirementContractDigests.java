package com.navneet.executespec.agent.requirement;

/** Domain-separated digest helpers shared by the codec and immutable persistence boundary. */
public final class RequirementContractDigests {
  private static final String BINDING_DOMAIN = "executespec.requirement-ir.binding.v1";

  private RequirementContractDigests() {}

  public static String artifactDigest(String canonicalArtifactJson) {
    if (canonicalArtifactJson == null) return null;
    return RequirementIrValidator.sha256(canonicalArtifactJson);
  }

  public static String boundDigest(
      String exactPlanFingerprint, String sourceSha256, String artifactDigest) {
    if (exactPlanFingerprint == null || sourceSha256 == null || artifactDigest == null) return null;
    String envelope =
        BINDING_DOMAIN
            + "\nplanSha256="
            + exactPlanFingerprint
            + "\nsourceSha256="
            + sourceSha256
            + "\nartifactSha256="
            + artifactDigest
            + "\n";
    return RequirementIrValidator.sha256(envelope);
  }
}

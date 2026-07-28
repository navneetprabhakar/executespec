package com.navneet.executespec.repositoryintel;

/** One compact, source-linked claim returned by a repository-intelligence adapter. */
public record RepositoryEvidence(
    String evidenceId,
    RepositoryEvidenceScope scope,
    RepositoryCapability capability,
    RepositoryEvidenceKind kind,
    String path,
    Integer startLine,
    Integer endLine,
    String symbol,
    String relationship,
    String excerpt,
    String contentDigest,
    RepositoryEvidenceProvenance provenance,
    double confidence,
    RepositoryEvidenceAmbiguity ambiguity,
    RepositorySnapshotId snapshot,
    String queryFingerprint) {

  public RepositoryEvidence {
    if (scope == null) throw new IllegalArgumentException("scope is required");
    if (capability == null) throw new IllegalArgumentException("capability is required");
    if (kind == null) throw new IllegalArgumentException("kind is required");
    path = normalizePath(path);
    validateLines(startLine, endLine);
    symbol = optional(symbol);
    relationship = optional(relationship);
    excerpt = optional(excerpt);
    if (excerpt == null) throw new IllegalArgumentException("excerpt is required");
    contentDigest = optional(contentDigest);
    if (contentDigest == null) contentDigest = RepositoryIntelligenceIds.sha256(excerpt);
    if (provenance == null) throw new IllegalArgumentException("provenance is required");
    if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
      throw new IllegalArgumentException("confidence must be finite and between 0 and 1");
    }
    if (ambiguity == null) throw new IllegalArgumentException("ambiguity is required");
    if (snapshot == null) throw new IllegalArgumentException("snapshot is required");
    if (!snapshot.acceptsEvidence()) {
      throw new IllegalArgumentException("only CURRENT snapshots can produce evidence");
    }
    queryFingerprint = required("queryFingerprint", queryFingerprint);
    String stableEvidenceId =
        RepositoryIntelligenceIds.sha256(
            scope.name(),
            capability.name(),
            kind.name(),
            path,
            startLine == null ? null : startLine.toString(),
            endLine == null ? null : endLine.toString(),
            symbol,
            relationship,
            contentDigest,
            provenance.name(),
            snapshot.stableId(),
            queryFingerprint);
    evidenceId = optional(evidenceId);
    if (evidenceId == null) {
      evidenceId = stableEvidenceId;
    } else if (!evidenceId.equals(stableEvidenceId)) {
      throw new IllegalArgumentException("evidenceId does not match evidence content");
    }
  }

  public int characterCount() {
    return RepositoryJsonBudget.evidenceCharacters(this);
  }

  public int estimatedTokens() {
    return Math.max(1, (characterCount() + 3) / 4);
  }

  private static void validateLines(Integer start, Integer end) {
    if ((start == null) != (end == null)) {
      throw new IllegalArgumentException("startLine and endLine must both be present or absent");
    }
    if (start != null && (start <= 0 || end < start)) {
      throw new IllegalArgumentException("line range must be positive and ordered");
    }
  }

  private static String normalizePath(String value) {
    String cleaned = optional(value);
    if (cleaned == null) return null;
    String normalized = cleaned.replace('\\', '/');
    if (normalized.startsWith("/")
        || normalized.matches("^[A-Za-z]:/.*")
        || normalized.equals("..")
        || normalized.startsWith("../")
        || normalized.contains("/../")
        || normalized.endsWith("/..")) {
      throw new IllegalArgumentException("path must remain within the evidence scope");
    }
    return normalized;
  }

  private static String required(String name, String value) {
    String cleaned = optional(value);
    if (cleaned == null) throw new IllegalArgumentException(name + " is required");
    return cleaned;
  }

  private static String optional(String value) {
    String cleaned = RepositoryIntelligenceIds.clean(value);
    return cleaned == null || cleaned.isEmpty() ? null : cleaned;
  }
}

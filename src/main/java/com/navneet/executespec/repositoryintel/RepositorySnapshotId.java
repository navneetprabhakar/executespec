package com.navneet.executespec.repositoryintel;

import java.time.Instant;
import java.util.Locale;

/** Immutable identity and freshness assertion for the repository state being queried. */
public record RepositorySnapshotId(
    String projectId,
    String repositoryId,
    String commitSha,
    String dirtyFingerprint,
    String inventoryFingerprint,
    String engine,
    int schemaVersion,
    Instant capturedAt,
    RepositoryFreshness freshness) {

  public RepositorySnapshotId {
    projectId = optional(projectId);
    repositoryId = required("repositoryId", repositoryId);
    commitSha = digest(commitSha);
    dirtyFingerprint = digest(dirtyFingerprint);
    inventoryFingerprint = digest(inventoryFingerprint);
    engine = required("engine", engine).toLowerCase(Locale.ROOT);
    if (schemaVersion <= 0) throw new IllegalArgumentException("schemaVersion must be positive");
    if (capturedAt == null) throw new IllegalArgumentException("capturedAt is required");
    if (freshness == null) throw new IllegalArgumentException("freshness is required");
    if (freshness == RepositoryFreshness.CURRENT
        && commitSha == null
        && inventoryFingerprint == null) {
      throw new IllegalArgumentException(
          "CURRENT snapshots require a commitSha or inventoryFingerprint");
    }
  }

  /** Stable source identity deliberately excludes observation time and asserted freshness. */
  public String stableId() {
    return RepositoryIntelligenceIds.sha256(
        projectId,
        repositoryId,
        commitSha,
        dirtyFingerprint,
        inventoryFingerprint,
        engine,
        Integer.toString(schemaVersion));
  }

  public boolean acceptsEvidence() {
    return freshness == RepositoryFreshness.CURRENT;
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

  private static String digest(String value) {
    String cleaned = optional(value);
    return cleaned == null ? null : cleaned.toLowerCase(Locale.ROOT);
  }
}

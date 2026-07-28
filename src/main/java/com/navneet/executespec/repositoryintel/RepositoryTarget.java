package com.navneet.executespec.repositoryintel;

import java.util.Objects;

/**
 * Server-owned authority target for resolving one linked repository before a snapshot exists.
 *
 * <p>This contract deliberately contains no filesystem path, source revision, or caller-supplied
 * snapshot claim. Production callers must derive the access context from the authenticated
 * principal and host tenant; request or model payloads are never authority for these fields. Its
 * constructor and query conversion are package-private so callers outside this authority package
 * cannot mint a target from untrusted identifiers.
 */
public final class RepositoryTarget {

  private final String projectId;
  private final String repositoryId;
  private final RepositoryAccessContext accessContext;

  RepositoryTarget(String projectId, String repositoryId, RepositoryAccessContext accessContext) {
    this.projectId = required("projectId", projectId);
    this.repositoryId = required("repositoryId", repositoryId);
    if (accessContext == null) {
      throw new IllegalArgumentException("accessContext is required");
    }
    this.accessContext = accessContext;
  }

  static RepositoryTarget from(RepositoryQuery query) {
    if (query == null) throw new IllegalArgumentException("query is required");
    return new RepositoryTarget(query.projectId(), query.repositoryId(), query.accessContext());
  }

  public String projectId() {
    return projectId;
  }

  public String repositoryId() {
    return repositoryId;
  }

  public RepositoryAccessContext accessContext() {
    return accessContext;
  }

  private static String required(String name, String value) {
    String cleaned = RepositoryIntelligenceIds.clean(value);
    if (cleaned == null || cleaned.isEmpty()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return cleaned;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof RepositoryTarget that)) return false;
    return projectId.equals(that.projectId)
        && repositoryId.equals(that.repositoryId)
        && accessContext.equals(that.accessContext);
  }

  @Override
  public int hashCode() {
    return Objects.hash(projectId, repositoryId, accessContext);
  }

  @Override
  public String toString() {
    return "RepositoryTarget[projectId="
        + projectId
        + ", repositoryId="
        + repositoryId
        + ", accessContext="
        + accessContext
        + "]";
  }
}

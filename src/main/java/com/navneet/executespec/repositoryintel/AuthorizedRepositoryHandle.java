package com.navneet.executespec.repositoryintel;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/**
 * Access-checked, immutable binding between an actor, host tenant, project, repository, and a
 * server-owned materialization. Query resolution additionally binds the expected snapshot; target
 * resolution intentionally precedes snapshot capture. It is never constructible from a path alone.
 */
public final class AuthorizedRepositoryHandle {

  private final String actorUserId;
  private final String tenantOrgId;
  private final String projectId;
  private final String repositoryId;
  private final String snapshotStableId;
  private final String sourceRevision;
  private final Path canonicalRoot;
  private final Object rootFileKey;

  /** Package-private: only repository-intelligence authority code can mint a handle. */
  AuthorizedRepositoryHandle(
      String actorUserId,
      String tenantOrgId,
      String projectId,
      String repositoryId,
      String sourceRevision,
      Path canonicalRoot) {
    this(actorUserId, tenantOrgId, projectId, repositoryId, null, sourceRevision, canonicalRoot);
  }

  /** Package-private: only repository-intelligence authority code can mint a handle. */
  AuthorizedRepositoryHandle(
      String actorUserId,
      String tenantOrgId,
      String projectId,
      String repositoryId,
      String snapshotStableId,
      String sourceRevision,
      Path canonicalRoot) {
    this.actorUserId = required("actorUserId", actorUserId);
    this.tenantOrgId = required("tenantOrgId", tenantOrgId);
    this.projectId = required("projectId", projectId);
    this.repositoryId = required("repositoryId", repositoryId);
    this.snapshotStableId = optional(snapshotStableId);
    this.sourceRevision = required("sourceRevision", sourceRevision);
    if (canonicalRoot == null || !canonicalRoot.isAbsolute()) {
      throw new IllegalArgumentException("canonicalRoot must be absolute");
    }
    this.canonicalRoot = canonicalRoot.normalize();
    this.rootFileKey = readFileKey(this.canonicalRoot);
  }

  public String actorUserId() {
    return actorUserId;
  }

  public String tenantOrgId() {
    return tenantOrgId;
  }

  public String projectId() {
    return projectId;
  }

  public String repositoryId() {
    return repositoryId;
  }

  public String snapshotStableId() {
    return snapshotStableId;
  }

  public String sourceRevision() {
    return sourceRevision;
  }

  public Path canonicalRoot() {
    return verifiedCanonicalRoot();
  }

  public Path requireMatches(RepositoryQuery query) {
    if (query == null
        || snapshotStableId == null
        || !snapshotStableId.equals(query.expectedSnapshot().stableId())) {
      throw new RepositoryAuthorityException(
          RepositoryAuthorityException.Reason.HANDLE_IDENTITY_MISMATCH);
    }
    requireMatches(RepositoryTarget.from(query));
    return verifiedCanonicalRoot();
  }

  /** Revalidate the materialization at the use boundary so an authorized path cannot be swapped. */
  private Path verifiedCanonicalRoot() {
    try {
      if (Files.isSymbolicLink(canonicalRoot)
          || !Files.isDirectory(canonicalRoot, NOFOLLOW_LINKS)
          || !canonicalRoot.toRealPath().equals(canonicalRoot)
          || !Objects.equals(rootFileKey, readFileKey(canonicalRoot))) {
        throw new RepositoryAuthorityException(
            RepositoryAuthorityException.Reason.MATERIALIZATION_SYMLINK_REJECTED);
      }
      return canonicalRoot;
    } catch (IOException unavailable) {
      throw new RepositoryAuthorityException(
          RepositoryAuthorityException.Reason.MATERIALIZATION_UNAVAILABLE, unavailable);
    }
  }

  private static Object readFileKey(Path root) {
    try {
      return Files.readAttributes(root, BasicFileAttributes.class, NOFOLLOW_LINKS).fileKey();
    } catch (IOException unavailable) {
      throw new RepositoryAuthorityException(
          RepositoryAuthorityException.Reason.MATERIALIZATION_UNAVAILABLE, unavailable);
    }
  }

  public Path requireMatches(RepositoryTarget target) {
    if (target == null
        || !actorUserId.equals(target.accessContext().actorUserId())
        || !tenantOrgId.equals(target.accessContext().tenantOrgId())
        || !projectId.equals(target.projectId())
        || !repositoryId.equals(target.repositoryId())) {
      throw new RepositoryAuthorityException(
          RepositoryAuthorityException.Reason.HANDLE_IDENTITY_MISMATCH);
    }
    return canonicalRoot;
  }

  private static String required(String name, String value) {
    String cleaned = optional(value);
    if (cleaned == null || cleaned.isEmpty()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return cleaned;
  }

  private static String optional(String value) {
    String cleaned = RepositoryIntelligenceIds.clean(value);
    return cleaned == null || cleaned.isEmpty() ? null : cleaned;
  }
}

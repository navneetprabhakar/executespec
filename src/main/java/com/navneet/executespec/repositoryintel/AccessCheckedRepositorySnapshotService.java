package com.navneet.executespec.repositoryintel;

import java.nio.file.Path;
import java.time.Instant;

import com.navneet.executespec.tool.RepositorySearchService;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/** Captures a native schema-v1 snapshot only after resolving a server-owned authority target. */
@Service
@RequiredArgsConstructor
public final class AccessCheckedRepositorySnapshotService {

  private static final String SNAPSHOT_ENGINE = "native";
  private static final int SNAPSHOT_SCHEMA_VERSION = 1;

  private final RepositoryRootResolver rootResolver;
  private final RepositorySearchService searchService;

  public RepositorySnapshotId capture(RepositoryTarget target) {
    if (target == null) throw new IllegalArgumentException("target is required");
    AuthorizedRepositoryHandle handle = rootResolver.resolve(target);
    if (handle == null) throw new IllegalStateException("repository handle is unavailable");
    Path root = handle.requireMatches(target);
    RepositorySearchService.Snapshot observed = searchService.snapshot(root);
    if (observed == null) throw new IllegalStateException("repository snapshot is unavailable");
    if (!"java-deterministic".equals(observed.engine())) {
      throw new IllegalStateException("repository snapshot engine is unsupported");
    }
    if (observed.repositorySha() == null
        || observed.repositorySha().isBlank()
        || "WORKTREE_ONLY".equalsIgnoreCase(observed.repositorySha())
        || !observed.repositorySha().equalsIgnoreCase(handle.sourceRevision())) {
      throw new RepositoryAuthorityException(
          RepositoryAuthorityException.Reason.SNAPSHOT_SOURCE_MISMATCH);
    }
    if (observed.inventoryFingerprint() == null || observed.inventoryFingerprint().isBlank()) {
      throw new IllegalStateException("repository inventory fingerprint is unavailable");
    }
    if (observed.dirtyFingerprint() != null) {
      throw new RepositoryAuthorityException(
          RepositoryAuthorityException.Reason.SNAPSHOT_DIRTY_WORKTREE);
    }
    return new RepositorySnapshotId(
        target.projectId(),
        target.repositoryId(),
        observed.repositorySha(),
        null,
        observed.inventoryFingerprint(),
        SNAPSHOT_ENGINE,
        SNAPSHOT_SCHEMA_VERSION,
        Instant.now(),
        RepositoryFreshness.CURRENT);
  }
}

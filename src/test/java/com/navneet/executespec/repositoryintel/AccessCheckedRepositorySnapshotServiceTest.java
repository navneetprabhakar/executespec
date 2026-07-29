package com.navneet.executespec.repositoryintel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;

import com.navneet.executespec.tool.RepositorySearchService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccessCheckedRepositorySnapshotServiceTest {

  @TempDir Path temporary;

  private static final String REVISION = "abc123";
  private static final String INVENTORY = "0123456789abcdef";

  private RepositoryRootResolver rootResolver;
  private RepositorySearchService searchService;
  private AccessCheckedRepositorySnapshotService service;
  private RepositoryTarget target;
  private Path root;

  @BeforeEach
  void setUp() throws Exception {
    rootResolver = mock(RepositoryRootResolver.class);
    searchService = mock(RepositorySearchService.class);
    service = new AccessCheckedRepositorySnapshotService(rootResolver, searchService);
    target =
        new RepositoryTarget(
            "project-1",
            "00000000-0000-0000-0000-000000000001",
            new RepositoryAccessContext("actor-1", "tenant-1"));
    root = temporary.resolve("managed/repository");
    java.nio.file.Files.createDirectories(root.resolve(".git"));
    root = root.toRealPath();
  }

  @Test
  void returnsCurrentNativeSchemaV1SnapshotFromAccessCheckedMaterialization() {
    when(rootResolver.resolve(target)).thenReturn(handle(target, REVISION, root));
    when(searchService.snapshot(root))
        .thenReturn(
            new RepositorySearchService.Snapshot(REVISION, INVENTORY, 12, "java-deterministic"));

    RepositorySnapshotId snapshot = service.capture(target);

    assertThat(snapshot.projectId()).isEqualTo(target.projectId());
    assertThat(snapshot.repositoryId()).isEqualTo(target.repositoryId());
    assertThat(snapshot.commitSha()).isEqualTo(REVISION);
    assertThat(snapshot.inventoryFingerprint()).isEqualTo(INVENTORY);
    assertThat(snapshot.dirtyFingerprint()).isNull();
    assertThat(snapshot.engine()).isEqualTo("native");
    assertThat(snapshot.schemaVersion()).isEqualTo(1);
    assertThat(snapshot.freshness()).isEqualTo(RepositoryFreshness.CURRENT);
    assertThat(snapshot.capturedAt()).isNotNull();
    verify(rootResolver).resolve(target);
    verify(searchService).snapshot(root);
  }

  @Test
  void refusesMaterializationWhoseObservedCommitDoesNotMatchDurableRevision() {
    when(rootResolver.resolve(target)).thenReturn(handle(target, REVISION, root));
    when(searchService.snapshot(root))
        .thenReturn(
            new RepositorySearchService.Snapshot("different", INVENTORY, 12, "java-deterministic"));

    assertThatThrownBy(() -> service.capture(target))
        .isInstanceOf(RepositoryAuthorityException.class)
        .extracting(error -> ((RepositoryAuthorityException) error).reason())
        .isEqualTo(RepositoryAuthorityException.Reason.SNAPSHOT_SOURCE_MISMATCH);
  }

  @Test
  void refusesWorktreeOnlyIdentityAndUnsupportedSnapshotEngine() {
    when(rootResolver.resolve(target)).thenReturn(handle(target, REVISION, root));
    when(searchService.snapshot(root))
        .thenReturn(
            new RepositorySearchService.Snapshot(
                "WORKTREE_ONLY", INVENTORY, 12, "java-deterministic"));

    assertThatThrownBy(() -> service.capture(target))
        .isInstanceOf(RepositoryAuthorityException.class)
        .extracting(error -> ((RepositoryAuthorityException) error).reason())
        .isEqualTo(RepositoryAuthorityException.Reason.SNAPSHOT_SOURCE_MISMATCH);

    when(searchService.snapshot(root))
        .thenReturn(new RepositorySearchService.Snapshot(REVISION, INVENTORY, 12, "external"));
    assertThatThrownBy(() -> service.capture(target))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("engine");
  }

  @Test
  void refusesDirtyOrUntrackedMaterializationAsCurrentCommitEvidence() {
    when(rootResolver.resolve(target)).thenReturn(handle(target, REVISION, root));
    when(searchService.snapshot(root))
        .thenReturn(
            new RepositorySearchService.Snapshot(
                REVISION, INVENTORY, "dirty-state", 13, "java-deterministic"));

    assertThatThrownBy(() -> service.capture(target))
        .isInstanceOf(RepositoryAuthorityException.class)
        .extracting(error -> ((RepositoryAuthorityException) error).reason())
        .isEqualTo(RepositoryAuthorityException.Reason.SNAPSHOT_DIRTY_WORKTREE);
  }

  @Test
  void doesNotReadFilesystemWhenAuthorityResolutionFails() {
    when(rootResolver.resolve(target))
        .thenThrow(
            new RepositoryAuthorityException(
                RepositoryAuthorityException.Reason.PROJECT_ACCESS_DENIED));

    assertThatThrownBy(() -> service.capture(target))
        .isInstanceOf(RepositoryAuthorityException.class)
        .extracting(error -> ((RepositoryAuthorityException) error).reason())
        .isEqualTo(RepositoryAuthorityException.Reason.PROJECT_ACCESS_DENIED);
    verify(searchService, never()).snapshot(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void rejectsForgedHandleBeforeSnapshotRead() {
    RepositoryTarget foreign =
        new RepositoryTarget(
            target.projectId(),
            target.repositoryId(),
            new RepositoryAccessContext("different-actor", "tenant-1"));
    when(rootResolver.resolve(target)).thenReturn(handle(foreign, REVISION, root));

    assertThatThrownBy(() -> service.capture(target))
        .isInstanceOf(RepositoryAuthorityException.class)
        .extracting(error -> ((RepositoryAuthorityException) error).reason())
        .isEqualTo(RepositoryAuthorityException.Reason.HANDLE_IDENTITY_MISMATCH);
    verify(searchService, never()).snapshot(org.mockito.ArgumentMatchers.any());
  }

  private static AuthorizedRepositoryHandle handle(
      RepositoryTarget target, String revision, Path root) {
    try {
      return new AuthorizedRepositoryHandle(
          target.accessContext().actorUserId(),
          target.accessContext().tenantOrgId(),
          target.projectId(),
          target.repositoryId(),
          revision,
          root.toRealPath());
    } catch (java.io.IOException unavailable) {
      throw new IllegalStateException("test repository is unavailable", unavailable);
    }
  }
}

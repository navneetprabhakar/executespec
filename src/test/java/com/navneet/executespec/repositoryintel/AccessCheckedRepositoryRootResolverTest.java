package com.navneet.executespec.repositoryintel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.OrgMember;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgMemberRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.harness.WorkspaceInitService;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.project.security.ProjectAccessGuard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AccessCheckedRepositoryRootResolverTest {

  private static final String ACTOR_ID = "actor-1";
  private static final String PROJECT_ID = "project-1";
  private static final String TENANT_ID = "tenant-1";
  private static final String SOURCE_REVISION = "abc123";

  @TempDir Path temporary;

  private AppUserRepository userRepository;
  private OrgRepository orgRepository;
  private OrgMemberRepository orgMemberRepository;
  private ProjectAccessGuard accessGuard;
  private ProjectRepoRepository repository;
  private ProjectRepoLinkRepository linkRepository;
  private WorkspaceInitService workspaceInitService;
  private AccessCheckedRepositoryRootResolver resolver;
  private UUID repositoryId;
  private Path workspace;
  private Path materialization;
  private Project project;
  private ProjectRepo source;
  private AppUser actor;
  private Org tenant;

  @BeforeEach
  void setUp() throws Exception {
    userRepository = mock(AppUserRepository.class);
    orgRepository = mock(OrgRepository.class);
    orgMemberRepository = mock(OrgMemberRepository.class);
    accessGuard = mock(ProjectAccessGuard.class);
    repository = mock(ProjectRepoRepository.class);
    linkRepository = mock(ProjectRepoLinkRepository.class);
    workspaceInitService = mock(WorkspaceInitService.class);
    AgentProperties properties = new AgentProperties();
    workspace = temporary.resolve("managed");
    materialization = workspace.resolve("materialized");
    Files.createDirectories(materialization.resolve(".git"));
    properties.setWorkspace(workspace.toString());
    repositoryId = UUID.randomUUID();
    project =
        Project.builder()
            .id(PROJECT_ID)
            .orgId(TENANT_ID)
            .status("ACTIVE")
            .createdBy(ACTOR_ID)
            .build();
    source =
        ProjectRepo.builder()
            .id(repositoryId)
            .scope(ProjectRepo.Scope.ORG)
            .ownerOrgId(TENANT_ID)
            .sourceType(ProjectRepo.SourceType.SCM.name())
            .sourceReadinessStatus(ProjectRepo.SourceReadiness.READY.name())
            .lastSyncedSha(SOURCE_REVISION)
            .build();
    actor =
        AppUser.builder()
            .id(ACTOR_ID)
            .email("actor@example.test")
            .firstName("Actor")
            .status("ACTIVE")
            .orgId(TENANT_ID)
            .build();
    tenant =
        Org.builder()
            .id(TENANT_ID)
            .name("Tenant")
            .status("ACTIVE")
            .createdByUserId(ACTOR_ID)
            .build();
    ProjectRepoLink link =
        ProjectRepoLink.builder()
            .projectId(PROJECT_ID)
            .repoId(repositoryId)
            .linkedBy(ACTOR_ID)
            .build();
    when(userRepository.findById(ACTOR_ID)).thenReturn(Optional.of(actor));
    when(orgMemberRepository.findByOrgIdAndUserId(TENANT_ID, ACTOR_ID))
        .thenReturn(
            Optional.of(
                OrgMember.builder()
                    .id("membership-1")
                    .orgId(TENANT_ID)
                    .userId(ACTOR_ID)
                    .orgRole("DEVELOPER")
                    .build()));
    when(orgRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenant));
    when(accessGuard.assertCanRead(eq(PROJECT_ID), any())).thenReturn(project);
    when(repository.findById(repositoryId)).thenReturn(Optional.of(source));
    when(linkRepository.findByProjectIdAndRepoId(PROJECT_ID, repositoryId))
        .thenReturn(Optional.of(link));
    when(workspaceInitService.runWorkspacePath(any())).thenReturn(materialization);
    resolver =
        new AccessCheckedRepositoryRootResolver(
            userRepository,
            orgRepository,
            orgMemberRepository,
            accessGuard,
            repository,
            linkRepository,
            workspaceInitService,
            properties);
  }

  @Test
  void returnsActorTenantProjectRepositoryAndSnapshotBoundCanonicalHandle() throws Exception {
    RepositoryQuery query = query(PROJECT_ID, repositoryId, TENANT_ID, SOURCE_REVISION);

    AuthorizedRepositoryHandle handle = resolver.resolve(query);

    assertThat(handle.actorUserId()).isEqualTo(ACTOR_ID);
    assertThat(handle.tenantOrgId()).isEqualTo(TENANT_ID);
    assertThat(handle.projectId()).isEqualTo(PROJECT_ID);
    assertThat(handle.repositoryId()).isEqualTo(repositoryId.toString());
    assertThat(handle.snapshotStableId()).isEqualTo(query.expectedSnapshot().stableId());
    assertThat(handle.sourceRevision()).isEqualTo(SOURCE_REVISION);
    assertThat(handle.canonicalRoot()).isEqualTo(materialization.toRealPath());
    assertThat(handle.requireMatches(query)).isEqualTo(materialization.toRealPath());
  }

  @Test
  void resolvesServerOwnedTargetBeforeSnapshotCaptureWithoutWeakeningQueryBinding()
      throws Exception {
    RepositoryTarget target =
        new RepositoryTarget(
            PROJECT_ID, repositoryId.toString(), new RepositoryAccessContext(ACTOR_ID, TENANT_ID));

    AuthorizedRepositoryHandle handle = resolver.resolve(target);

    assertThat(handle.actorUserId()).isEqualTo(ACTOR_ID);
    assertThat(handle.tenantOrgId()).isEqualTo(TENANT_ID);
    assertThat(handle.projectId()).isEqualTo(PROJECT_ID);
    assertThat(handle.repositoryId()).isEqualTo(repositoryId.toString());
    assertThat(handle.snapshotStableId()).isNull();
    assertThat(handle.sourceRevision()).isEqualTo(SOURCE_REVISION);
    assertThat(handle.requireMatches(target)).isEqualTo(materialization.toRealPath());
    assertThatThrownBy(
            () ->
                handle.requireMatches(query(PROJECT_ID, repositoryId, TENANT_ID, SOURCE_REVISION)))
        .isInstanceOf(RepositoryAuthorityException.class)
        .extracting(error -> ((RepositoryAuthorityException) error).reason())
        .isEqualTo(RepositoryAuthorityException.Reason.HANDLE_IDENTITY_MISMATCH);
  }

  @Test
  void authorizedExternalCollaboratorKeepsHostProjectTenantBinding() {
    actor.setOrgId("actor-home-org");
    when(orgMemberRepository.findByOrgIdAndUserId("actor-home-org", ACTOR_ID))
        .thenReturn(
            Optional.of(
                OrgMember.builder()
                    .id("external-home-membership")
                    .orgId("actor-home-org")
                    .userId(ACTOR_ID)
                    .orgRole("DEVELOPER")
                    .build()));

    AuthorizedRepositoryHandle handle =
        resolver.resolve(query(PROJECT_ID, repositoryId, TENANT_ID, SOURCE_REVISION));

    assertThat(handle.actorUserId()).isEqualTo(ACTOR_ID);
    assertThat(handle.tenantOrgId()).isEqualTo(TENANT_ID);
    assertThat(handle.projectId()).isEqualTo(PROJECT_ID);
  }

  @Test
  void rejectsCrossTenantAndCrossProjectAuthority() {
    assertReason(
        query(PROJECT_ID, repositoryId, "foreign-tenant", SOURCE_REVISION),
        RepositoryAuthorityException.Reason.TENANT_MISMATCH);

    when(accessGuard.assertCanRead(eq(PROJECT_ID), any()))
        .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
    assertReason(
        query(PROJECT_ID, repositoryId, TENANT_ID, SOURCE_REVISION),
        RepositoryAuthorityException.Reason.PROJECT_ACCESS_DENIED);

    doReturn(
            Project.builder()
                .id("different-project")
                .orgId(TENANT_ID)
                .status("ACTIVE")
                .createdBy(ACTOR_ID)
                .build())
        .when(accessGuard)
        .assertCanRead(eq(PROJECT_ID), any());
    assertReason(
        query(PROJECT_ID, repositoryId, TENANT_ID, SOURCE_REVISION),
        RepositoryAuthorityException.Reason.PROJECT_IDENTITY_MISMATCH);
  }

  @Test
  void rejectsDenormalizedActorOrgPointerWithoutAuthoritativeMembership() {
    when(orgMemberRepository.findByOrgIdAndUserId(TENANT_ID, ACTOR_ID))
        .thenReturn(Optional.empty());

    assertReason(
        query(PROJECT_ID, repositoryId, TENANT_ID, SOURCE_REVISION),
        RepositoryAuthorityException.Reason.ACTOR_MEMBERSHIP_UNAVAILABLE);
  }

  @Test
  void rejectsInactiveActorAndHostTenant() {
    actor.setStatus("SUSPENDED");
    assertReason(
        query(PROJECT_ID, repositoryId, TENANT_ID, SOURCE_REVISION),
        RepositoryAuthorityException.Reason.ACTOR_INACTIVE);

    actor.setStatus("ACTIVE");
    tenant.setStatus("DELETED");
    assertReason(
        query(PROJECT_ID, repositoryId, TENANT_ID, SOURCE_REVISION),
        RepositoryAuthorityException.Reason.TENANT_INACTIVE);
  }

  @Test
  void rejectsRepositoryMismatchMissingLinkAndDeletedRepository() {
    UUID other = UUID.randomUUID();
    assertReason(
        query(PROJECT_ID, other, TENANT_ID, SOURCE_REVISION),
        RepositoryAuthorityException.Reason.REPOSITORY_UNAVAILABLE);

    when(linkRepository.findByProjectIdAndRepoId(PROJECT_ID, repositoryId))
        .thenReturn(Optional.empty());
    assertReason(
        query(PROJECT_ID, repositoryId, TENANT_ID, SOURCE_REVISION),
        RepositoryAuthorityException.Reason.REPOSITORY_NOT_LINKED);

    when(repository.findById(repositoryId)).thenReturn(Optional.empty());
    assertReason(
        query(PROJECT_ID, repositoryId, TENANT_ID, SOURCE_REVISION),
        RepositoryAuthorityException.Reason.REPOSITORY_UNAVAILABLE);
  }

  @Test
  void rejectsRepositoryAndLinkRowsWhoseReturnedIdentityDoesNotMatchLookup() {
    UUID other = UUID.randomUUID();
    source.setId(other);
    assertReason(
        query(PROJECT_ID, repositoryId, TENANT_ID, SOURCE_REVISION),
        RepositoryAuthorityException.Reason.REPOSITORY_IDENTITY_MISMATCH);

    source.setId(repositoryId);
    when(linkRepository.findByProjectIdAndRepoId(PROJECT_ID, repositoryId))
        .thenReturn(
            Optional.of(
                ProjectRepoLink.builder()
                    .projectId("different-project")
                    .repoId(repositoryId)
                    .linkedBy(ACTOR_ID)
                    .build()));
    assertReason(
        query(PROJECT_ID, repositoryId, TENANT_ID, SOURCE_REVISION),
        RepositoryAuthorityException.Reason.REPOSITORY_NOT_LINKED);
  }

  @Test
  void rejectsInactiveProjectAndRepositoryReadiness() {
    project.setStatus("ARCHIVED");
    assertReason(
        query(PROJECT_ID, repositoryId, TENANT_ID, SOURCE_REVISION),
        RepositoryAuthorityException.Reason.PROJECT_INACTIVE);

    project.setStatus("ACTIVE");
    project.setDeletedAt(Instant.EPOCH);
    assertReason(
        query(PROJECT_ID, repositoryId, TENANT_ID, SOURCE_REVISION),
        RepositoryAuthorityException.Reason.PROJECT_INACTIVE);

    project.setDeletedAt(null);
    source.setSourceReadinessStatus(ProjectRepo.SourceReadiness.BLOCKED.name());
    assertReason(
        query(PROJECT_ID, repositoryId, TENANT_ID, SOURCE_REVISION),
        RepositoryAuthorityException.Reason.REPOSITORY_NOT_READY);
  }

  @Test
  void rejectsRepositoryWhoseDurableOwnerDoesNotMatchHostTenant() {
    source.setOwnerOrgId("foreign-tenant");

    assertReason(
        query(PROJECT_ID, repositoryId, TENANT_ID, SOURCE_REVISION),
        RepositoryAuthorityException.Reason.REPOSITORY_OWNER_MISMATCH);
  }

  @Test
  void rejectsUserScopedRepositoryWhenCallerIsNotItsPrivateOwner() {
    String privateOwner = "owner-2";
    source.setScope(ProjectRepo.Scope.USER);
    source.setOwnerOrgId(null);
    source.setOwnerUserId(privateOwner);
    when(linkRepository.findByProjectIdAndRepoId(PROJECT_ID, repositoryId))
        .thenReturn(
            Optional.of(
                ProjectRepoLink.builder()
                    .projectId(PROJECT_ID)
                    .repoId(repositoryId)
                    .linkedBy(privateOwner)
                    .build()));

    assertReason(
        query(PROJECT_ID, repositoryId, TENANT_ID, SOURCE_REVISION),
        RepositoryAuthorityException.Reason.REPOSITORY_OWNER_MISMATCH);
  }

  @Test
  void rejectsSnapshotThatDoesNotMatchPersistedSourceRevision() {
    assertReason(
        query(PROJECT_ID, repositoryId, TENANT_ID, "different-sha"),
        RepositoryAuthorityException.Reason.SNAPSHOT_SOURCE_MISMATCH);
  }

  @Test
  void rejectsMaterializationOutsideManagedWorkspaceAndSymlinkRoot() throws Exception {
    Path outside = temporary.resolve("outside");
    Files.createDirectories(outside.resolve(".git"));
    when(workspaceInitService.runWorkspacePath(any())).thenReturn(outside);
    assertReason(
        query(PROJECT_ID, repositoryId, TENANT_ID, SOURCE_REVISION),
        RepositoryAuthorityException.Reason.MATERIALIZATION_OUTSIDE_WORKSPACE);

    Path target = temporary.resolve("target");
    Files.createDirectories(target.resolve(".git"));
    Path link = workspace.resolve("linked");
    Files.createSymbolicLink(link, target);
    when(workspaceInitService.runWorkspacePath(any())).thenReturn(link);
    assertReason(
        query(PROJECT_ID, repositoryId, TENANT_ID, SOURCE_REVISION),
        RepositoryAuthorityException.Reason.MATERIALIZATION_SYMLINK_REJECTED);
  }

  @Test
  void rejectsMissingGitBoundaryAndMismatchedTypedHandle() throws Exception {
    Files.delete(materialization.resolve(".git"));
    assertReason(
        query(PROJECT_ID, repositoryId, TENANT_ID, SOURCE_REVISION),
        RepositoryAuthorityException.Reason.MATERIALIZATION_NOT_REPOSITORY);

    RepositoryQuery query = query(PROJECT_ID, repositoryId, TENANT_ID, SOURCE_REVISION);
    AuthorizedRepositoryHandle forged =
        new AuthorizedRepositoryHandle(
            "different-actor",
            TENANT_ID,
            PROJECT_ID,
            repositoryId.toString(),
            query.expectedSnapshot().stableId(),
            SOURCE_REVISION,
            materialization.toAbsolutePath());
    assertThatThrownBy(() -> forged.requireMatches(query))
        .isInstanceOf(RepositoryAuthorityException.class)
        .extracting(error -> ((RepositoryAuthorityException) error).reason())
        .isEqualTo(RepositoryAuthorityException.Reason.HANDLE_IDENTITY_MISMATCH);
  }

  @Test
  void rejectsMaterializationReplacedBySymlinkAfterAuthorization() throws Exception {
    RepositoryQuery query = query(PROJECT_ID, repositoryId, TENANT_ID, SOURCE_REVISION);
    AuthorizedRepositoryHandle handle = resolver.resolve(query);
    Path original = workspace.resolve("original-materialization");
    Files.move(materialization, original);
    Path outside = temporary.resolve("post-authority-outside");
    Files.createDirectories(outside.resolve(".git"));
    try {
      Files.createSymbolicLink(materialization, outside);
    } catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
      return;
    }

    assertThatThrownBy(() -> handle.requireMatches(query))
        .isInstanceOf(RepositoryAuthorityException.class)
        .extracting(error -> ((RepositoryAuthorityException) error).reason())
        .isEqualTo(RepositoryAuthorityException.Reason.MATERIALIZATION_SYMLINK_REJECTED);
  }

  private void assertReason(
      RepositoryQuery query, RepositoryAuthorityException.Reason expectedReason) {
    assertThatThrownBy(() -> resolver.resolve(query))
        .isInstanceOf(RepositoryAuthorityException.class)
        .extracting(error -> ((RepositoryAuthorityException) error).reason())
        .isEqualTo(expectedReason);
  }

  private static RepositoryQuery query(
      String projectId, UUID repoId, String tenantId, String commitSha) {
    RepositorySnapshotId snapshot =
        new RepositorySnapshotId(
            projectId,
            repoId.toString(),
            commitSha,
            "inventory",
            "inventory",
            "native",
            1,
            Instant.EPOCH,
            RepositoryFreshness.CURRENT);
    return new RepositoryQuery(
        projectId,
        repoId.toString(),
        "Widget",
        Set.of(RepositoryCapability.EXACT_TEXT),
        null,
        "*.java",
        snapshot,
        QueryBudget.DEFAULT,
        false,
        new RepositoryAccessContext(ACTOR_ID, tenantId));
  }
}

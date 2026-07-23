package com.navneet.executespec.project.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.entity.ProjectMember;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.service.TierEnforcementService;
import com.navneet.executespec.project.dto.RegisterLocalSourceRequest;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class LocalProjectSourceServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-17T08:00:00Z");
  private static final String PROJECT_ID = "project-1";
  private static final String USER_ID = "user-1";
  private static final String ORG_ID = "org-1";

  @Mock ProjectRepoRepository repoRepository;
  @Mock ProjectRepoLinkRepository linkRepository;
  @Mock ProjectRepository projectRepository;
  @Mock ProjectMemberRepository projectMemberRepository;
  @Mock TierEnforcementService tierEnforcementService;

  private LocalProjectSourceService service;

  @BeforeEach
  void setUp() {
    service =
        new LocalProjectSourceService(
            repoRepository,
            linkRepository,
            projectRepository,
            projectMemberRepository,
            tierEnforcementService,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void createsPathFreeUserOwnedSourceAndLinksItWithAnExpiringLease() {
    allowProject();
    saveEntities();
    UUID folder = UUID.randomUUID();
    UUID installation = UUID.randomUUID();
    when(repoRepository.findByOwnerUserIdAndLocalFolderId(USER_ID, folder))
        .thenReturn(Optional.empty());
    when(linkRepository.findByProjectIdAndRepoId(any(), any())).thenReturn(Optional.empty());

    var result =
        service.register(
            PROJECT_ID,
            request(folder, installation, Map.of("language", "java")),
            USER_ID,
            ORG_ID,
            false);

    assertThat(result.created()).isTrue();
    assertThat(result.link().repo().sourceType()).isEqualTo("LOCAL");
    assertThat(result.link().repo().githubRepoFullName()).isNull();
    assertThat(result.link().repo().defaultBranch()).isNull();
    assertThat(result.link().repo().sourceKey()).isEqualTo(folder.toString());
    assertThat(result.link().repo().sourceReadinessStatus()).isEqualTo("LINKED");
    assertThat(result.link().repo().sourceReadinessReason())
        .isEqualTo("LOCAL_CONNECTOR_REGISTERED_UNVERIFIED");
    assertThat(result.link().repo().localConnectionStatus()).isEqualTo("ONLINE");
    assertThat(result.link().repo().sourceLeaseExpiresAt()).isEqualTo(NOW.plusSeconds(90));
    assertThat(result.link().repo().sourceMetadata()).containsEntry("language", "java");
    verify(tierEnforcementService).assertCanLinkRepoToProject(USER_ID, PROJECT_ID);
  }

  @Test
  void sameFolderAndInstallationIsIdempotentAndRenewsWithoutAnotherLink() {
    allowProject();
    when(repoRepository.save(any(ProjectRepo.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    UUID folder = UUID.randomUUID();
    UUID installation = UUID.randomUUID();
    ProjectRepo existing = localSource(folder, installation);
    ProjectRepoLink link =
        ProjectRepoLink.builder()
            .projectId(PROJECT_ID)
            .repoId(existing.getId())
            .linkedBy(USER_ID)
            .build();
    when(repoRepository.findByOwnerUserIdAndLocalFolderId(USER_ID, folder))
        .thenReturn(Optional.of(existing));
    when(linkRepository.findByProjectIdAndRepoId(PROJECT_ID, existing.getId()))
        .thenReturn(Optional.of(link));

    var result =
        service.register(
            PROJECT_ID, request(folder, installation, Map.of()), USER_ID, ORG_ID, false);

    assertThat(result.created()).isFalse();
    assertThat(result.link().repo().sourceLeaseExpiresAt()).isEqualTo(NOW.plusSeconds(90));
    verify(linkRepository, never()).save(any());
    verify(tierEnforcementService, never()).assertCanLinkRepoToProject(any(), any());
  }

  @Test
  void existingOwnedSourceLinkedToAnotherProjectCreatesThisProjectLinkOnce() {
    allowProject();
    saveEntities();
    UUID folder = UUID.randomUUID();
    UUID installation = UUID.randomUUID();
    ProjectRepo existing = localSource(folder, installation);
    when(repoRepository.findByOwnerUserIdAndLocalFolderId(USER_ID, folder))
        .thenReturn(Optional.of(existing));
    when(linkRepository.findByProjectIdAndRepoId(PROJECT_ID, existing.getId()))
        .thenReturn(Optional.empty());

    var result =
        service.register(
            PROJECT_ID, request(folder, installation, Map.of()), USER_ID, ORG_ID, false);

    assertThat(result.created()).isTrue();
    verify(linkRepository).save(any(ProjectRepoLink.class));
    verify(tierEnforcementService).assertCanLinkRepoToProject(USER_ID, PROJECT_ID);
  }

  @Test
  void sameFolderFromAnotherInstallationFailsClosed() {
    allowProject();
    UUID folder = UUID.randomUUID();
    when(repoRepository.findByOwnerUserIdAndLocalFolderId(USER_ID, folder))
        .thenReturn(Optional.of(localSource(folder, UUID.randomUUID())));

    assertThatThrownBy(
            () ->
                service.register(
                    PROJECT_ID,
                    request(folder, UUID.randomUUID(), Map.of()),
                    USER_ID,
                    ORG_ID,
                    false))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("another client installation");
  }

  @Test
  void absolutePathMetadataIsRejectedBeforePersistence() {
    allowProject();
    var request =
        request(UUID.randomUUID(), UUID.randomUUID(), Map.of("location", "/Users/a/repo"));

    assertThatThrownBy(() -> service.register(PROJECT_ID, request, USER_ID, ORG_ID, false))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("absolute filesystem path");
    verify(repoRepository, never()).save(any());
  }

  @Test
  void pathNamedMetadataIsRejectedEvenWhenValueIsRelative() {
    allowProject();
    var request = request(UUID.randomUUID(), UUID.randomUUID(), Map.of("workspacePath", "repo"));

    assertThatThrownBy(() -> service.register(PROJECT_ID, request, USER_ID, ORG_ID, false))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("path metadata");
  }

  @Test
  void nonRandomIdentifiersAreRejected() {
    allowProject();
    UUID nameBased =
        UUID.nameUUIDFromBytes("folder".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    assertThatThrownBy(
            () ->
                service.register(
                    PROJECT_ID,
                    request(nameBased, UUID.randomUUID(), Map.of()),
                    USER_ID,
                    ORG_ID,
                    false))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("random UUIDv4");
  }

  @Test
  void unrelatedTenantCannotRegisterAgainstProject() {
    when(projectRepository.findById(PROJECT_ID))
        .thenReturn(Optional.of(Project.builder().id(PROJECT_ID).orgId(ORG_ID).name("P").build()));
    when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, USER_ID))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.register(
                    PROJECT_ID,
                    request(UUID.randomUUID(), UUID.randomUUID(), Map.of()),
                    USER_ID,
                    "other-org",
                    false))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("membership with write access");
    verify(repoRepository, never()).save(any());
  }

  @Test
  void expiredLeaseRendersOfflineWithoutMutatingTheRow() {
    ProjectRepo source = localSource(UUID.randomUUID(), UUID.randomUUID());
    source.setSourceLeaseExpiresAt(NOW.minusSeconds(1));

    assertThat(source.localConnectionStatus(NOW)).isEqualTo("OFFLINE");
  }

  @Test
  void crossOrgViewerCannotRegisterLocalSource() {
    when(projectRepository.findById(PROJECT_ID))
        .thenReturn(Optional.of(Project.builder().id(PROJECT_ID).orgId(ORG_ID).name("P").build()));
    when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, USER_ID))
        .thenReturn(
            Optional.of(
                ProjectMember.builder()
                    .projectId(PROJECT_ID)
                    .userId(USER_ID)
                    .projectRole("PROJECT_VIEWER")
                    .build()));

    assertThatThrownBy(
            () ->
                service.register(
                    PROJECT_ID,
                    request(UUID.randomUUID(), UUID.randomUUID(), Map.of()),
                    USER_ID,
                    "external-org",
                    false))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("membership with write access");
  }

  @Test
  void sameOrgUnassignedUserCannotBypassProjectMembershipAtServiceBoundary() {
    when(projectRepository.findById(PROJECT_ID))
        .thenReturn(Optional.of(Project.builder().id(PROJECT_ID).orgId(ORG_ID).name("P").build()));
    when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, USER_ID))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.register(
                    PROJECT_ID,
                    request(UUID.randomUUID(), UUID.randomUUID(), Map.of()),
                    USER_ID,
                    ORG_ID,
                    false))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("membership with write access");
    verify(repoRepository, never()).save(any());
  }

  /* ── heartbeat ── */

  @Test
  void heartbeatRenewsLeaseAndUpdatesRevisionForTheHoldingInstallation() {
    allowProject();
    when(repoRepository.save(any(ProjectRepo.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    UUID folder = UUID.randomUUID();
    UUID installation = UUID.randomUUID();
    ProjectRepo source = localSource(folder, installation);
    source.setSourceLeaseExpiresAt(NOW.minusSeconds(10)); // currently OFFLINE
    when(repoRepository.findByOwnerUserIdAndLocalFolderId(USER_ID, folder))
        .thenReturn(Optional.of(source));
    when(linkRepository.findByProjectIdAndRepoId(PROJECT_ID, source.getId()))
        .thenReturn(
            Optional.of(
                ProjectRepoLink.builder().projectId(PROJECT_ID).repoId(source.getId()).build()));

    var response =
        service.heartbeat(
            PROJECT_ID,
            folder,
            new com.navneet.executespec.project.dto.LocalSourceHeartbeatRequest(
                installation, "sha256:def", 120),
            USER_ID,
            ORG_ID,
            false);

    assertThat(response.localConnectionStatus()).isEqualTo("ONLINE");
    assertThat(response.sourceLeaseExpiresAt()).isEqualTo(NOW.plusSeconds(120));
    assertThat(response.sourceLastSeenAt()).isEqualTo(NOW);
    assertThat(response.sourceRevision()).isEqualTo("sha256:def");
  }

  @Test
  void heartbeatFromForeignInstallationFailsClosedWithoutRenewal() {
    allowProject();
    UUID folder = UUID.randomUUID();
    ProjectRepo source = localSource(folder, UUID.randomUUID());
    when(repoRepository.findByOwnerUserIdAndLocalFolderId(USER_ID, folder))
        .thenReturn(Optional.of(source));

    assertThatThrownBy(
            () ->
                service.heartbeat(
                    PROJECT_ID,
                    folder,
                    new com.navneet.executespec.project.dto.LocalSourceHeartbeatRequest(
                        UUID.randomUUID(), null, null),
                    USER_ID,
                    ORG_ID,
                    false))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("another client installation");
    verify(repoRepository, never()).save(any());
  }

  @Test
  void heartbeatForUnknownFolderIsNotFoundAndNeverCreates() {
    allowProject();
    UUID folder = UUID.randomUUID();
    when(repoRepository.findByOwnerUserIdAndLocalFolderId(USER_ID, folder))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.heartbeat(
                    PROJECT_ID,
                    folder,
                    new com.navneet.executespec.project.dto.LocalSourceHeartbeatRequest(
                        UUID.randomUUID(), null, null),
                    USER_ID,
                    ORG_ID,
                    false))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("not found");
    verify(repoRepository, never()).save(any());
    verify(linkRepository, never()).save(any());
  }

  @Test
  void heartbeatForSourceNotLinkedToProjectIsNotFound() {
    allowProject();
    UUID folder = UUID.randomUUID();
    UUID installation = UUID.randomUUID();
    ProjectRepo source = localSource(folder, installation);
    when(repoRepository.findByOwnerUserIdAndLocalFolderId(USER_ID, folder))
        .thenReturn(Optional.of(source));
    when(linkRepository.findByProjectIdAndRepoId(PROJECT_ID, source.getId()))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.heartbeat(
                    PROJECT_ID,
                    folder,
                    new com.navneet.executespec.project.dto.LocalSourceHeartbeatRequest(
                        installation, null, null),
                    USER_ID,
                    ORG_ID,
                    false))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("not linked");
    verify(repoRepository, never()).save(any());
  }

  @Test
  void heartbeatRejectsAbsolutePathRevision() {
    allowProject();
    UUID folder = UUID.randomUUID();
    UUID installation = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                service.heartbeat(
                    PROJECT_ID,
                    folder,
                    new com.navneet.executespec.project.dto.LocalSourceHeartbeatRequest(
                        installation, "/Users/a/repo", null),
                    USER_ID,
                    ORG_ID,
                    false))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("absolute filesystem path");
    verify(repoRepository, never()).save(any());
  }

  private static RegisterLocalSourceRequest request(
      UUID folder, UUID installation, Map<String, String> metadata) {
    return new RegisterLocalSourceRequest(
        folder,
        installation,
        "incident-service",
        "sha256:abc",
        List.of("READ_FILES", "SEARCH_FILES"),
        metadata,
        null);
  }

  private static ProjectRepo localSource(UUID folder, UUID installation) {
    return ProjectRepo.builder()
        .id(UUID.randomUUID())
        .scope(ProjectRepo.Scope.USER)
        .ownerUserId(USER_ID)
        .sourceType("LOCAL")
        .scmProvider(null)
        .sourceKey(folder.toString())
        .sourceDisplayName("incident-service")
        .localFolderId(folder)
        .localInstallationId(installation)
        .sourceReadinessStatus("LINKED")
        .createdAt(NOW.minusSeconds(30))
        .updatedAt(NOW.minusSeconds(30))
        .build();
  }

  private void allowProject() {
    when(projectRepository.findById(PROJECT_ID))
        .thenReturn(Optional.of(Project.builder().id(PROJECT_ID).orgId(ORG_ID).name("P").build()));
    when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, USER_ID))
        .thenReturn(
            Optional.of(
                ProjectMember.builder()
                    .projectId(PROJECT_ID)
                    .userId(USER_ID)
                    .projectRole("PROJECT_DEVELOPER")
                    .build()));
  }

  private void saveEntities() {
    when(repoRepository.save(any(ProjectRepo.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(linkRepository.save(any(ProjectRepoLink.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }
}

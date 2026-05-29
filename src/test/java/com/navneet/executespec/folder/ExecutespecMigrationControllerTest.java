package com.navneet.executespec.folder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

/**
 * Unit tests for {@link ExecutespecMigrationController} (E1.3). Verifies the per-repo skip /
 * bootstrap / failure routing without spinning up Spring or hitting the GitHub API.
 */
@ExtendWith(MockitoExtension.class)
class ExecutespecMigrationControllerTest {

  @Mock ProjectRepository projectRepository;
  @Mock ProjectRepoLinkRepository linkRepo;
  @Mock ProjectRepoRepository repoRepo;
  @Mock AppUserRepository userRepo;
  @Mock BootstrapService bootstrap;

  @InjectMocks ExecutespecMigrationController controller;

  private String projectId;
  private Project project;

  @BeforeEach
  void setUp() {
    projectId = UUID.randomUUID().toString();
    project = new Project();
    project.setId(projectId);
    project.setName("Acme Web");
    when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
  }

  @Test
  void migrate_skipsAlreadyBootstrappedRepos() {
    ProjectRepo done = repo("acme/already-done", ProjectRepo.FolderStatus.BOOTSTRAPPED);
    when(linkRepo.findByProjectId(projectId)).thenReturn(List.of(linkFor(done)));
    when(repoRepo.findById(done.getId())).thenReturn(Optional.of(done));

    ResponseEntity<ExecutespecMigrationController.MigrateResponse> res =
        controller.migrate(projectId, fakeAuth());

    assertThat(res.getBody().results())
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.status()).isEqualTo("SKIPPED_ALREADY_BOOTSTRAPPED");
              assertThat(r.githubRepoFullName()).isEqualTo("acme/already-done");
            });
    verifyNoInteractions(bootstrap);
  }

  @Test
  void migrate_skipsRepoWithBootstrapPrAlreadyOpen() {
    ProjectRepo inFlight = repo("acme/pr-open", ProjectRepo.FolderStatus.BOOTSTRAP_PR_OPEN);
    inFlight.setExecutespecBootstrapPrUrl("https://github.com/acme/pr-open/pull/1");
    when(linkRepo.findByProjectId(projectId)).thenReturn(List.of(linkFor(inFlight)));
    when(repoRepo.findById(inFlight.getId())).thenReturn(Optional.of(inFlight));

    ResponseEntity<ExecutespecMigrationController.MigrateResponse> res =
        controller.migrate(projectId, fakeAuth());

    assertThat(res.getBody().results())
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.status()).isEqualTo("SKIPPED_BOOTSTRAP_IN_PROGRESS");
              assertThat(r.detail()).contains("/pr-open/pull/1");
            });
    verifyNoInteractions(bootstrap);
  }

  @Test
  void migrate_runsBootstrapForNotBootstrappedRepos() {
    ProjectRepo target = repo("acme/needs-bootstrap", ProjectRepo.FolderStatus.NOT_BOOTSTRAPPED);
    when(linkRepo.findByProjectId(projectId)).thenReturn(List.of(linkFor(target)));
    // First lookup → entity to check pre-status; second lookup → entity after bootstrap mutated it
    ProjectRepo afterBootstrap =
        repo("acme/needs-bootstrap", ProjectRepo.FolderStatus.BOOTSTRAP_PR_OPEN);
    afterBootstrap.setId(target.getId());
    afterBootstrap.setExecutespecBootstrapPrUrl("https://github.com/acme/needs-bootstrap/pull/2");
    when(repoRepo.findById(target.getId()))
        .thenReturn(Optional.of(target))
        .thenReturn(Optional.of(afterBootstrap));

    ResponseEntity<ExecutespecMigrationController.MigrateResponse> res =
        controller.migrate(projectId, fakeAuth());

    verify(bootstrap, times(1))
        .bootstrap(
            eq(target.getId()), eq(UUID.fromString(projectId)), eq("Acme Web"), any(), any());
    assertThat(res.getBody().results())
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.status()).isEqualTo("BOOTSTRAP_PR_OPEN");
              assertThat(r.detail()).endsWith("/pull/2");
            });
  }

  @Test
  void migrate_handlesBootstrapException_asFailedResult() {
    ProjectRepo target = repo("acme/will-explode", ProjectRepo.FolderStatus.NOT_BOOTSTRAPPED);
    when(linkRepo.findByProjectId(projectId)).thenReturn(List.of(linkFor(target)));
    when(repoRepo.findById(target.getId())).thenReturn(Optional.of(target));
    doThrow(new RuntimeException("GitHub API down"))
        .when(bootstrap)
        .bootstrap(any(), any(), any(), any(), any());

    ResponseEntity<ExecutespecMigrationController.MigrateResponse> res =
        controller.migrate(projectId, fakeAuth());

    assertThat(res.getBody().results())
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.status()).isEqualTo("BOOTSTRAP_FAILED");
              assertThat(r.detail()).isEqualTo("GitHub API down");
            });
  }

  @Test
  void migrate_perRepoIndependent_oneFailureDoesNotAbortBatch() {
    ProjectRepo good = repo("acme/good", ProjectRepo.FolderStatus.NOT_BOOTSTRAPPED);
    ProjectRepo bad = repo("acme/bad", ProjectRepo.FolderStatus.NOT_BOOTSTRAPPED);
    when(linkRepo.findByProjectId(projectId)).thenReturn(List.of(linkFor(good), linkFor(bad)));

    ProjectRepo goodAfter = repo("acme/good", ProjectRepo.FolderStatus.BOOTSTRAP_PR_OPEN);
    goodAfter.setId(good.getId());
    when(repoRepo.findById(good.getId()))
        .thenReturn(Optional.of(good))
        .thenReturn(Optional.of(goodAfter));
    when(repoRepo.findById(bad.getId())).thenReturn(Optional.of(bad));

    // bad throws; good completes normally
    doNothing().when(bootstrap).bootstrap(eq(good.getId()), any(), any(), any(), any());
    doThrow(new RuntimeException("scope check failed"))
        .when(bootstrap)
        .bootstrap(eq(bad.getId()), any(), any(), any(), any());

    ResponseEntity<ExecutespecMigrationController.MigrateResponse> res =
        controller.migrate(projectId, fakeAuth());

    assertThat(res.getBody().results()).hasSize(2);
    assertThat(res.getBody().results().get(0).status()).isEqualTo("BOOTSTRAP_PR_OPEN");
    assertThat(res.getBody().results().get(1).status()).isEqualTo("BOOTSTRAP_FAILED");
  }

  private ProjectRepo repo(String fullName, ProjectRepo.FolderStatus status) {
    ProjectRepo r = new ProjectRepo();
    r.setId(UUID.randomUUID());
    r.setGithubRepoFullName(fullName);
    if (status != ProjectRepo.FolderStatus.NOT_BOOTSTRAPPED) {
      r.setExecutespecFolderStatus(status.name());
    }
    return r;
  }

  private ProjectRepoLink linkFor(ProjectRepo repo) {
    return ProjectRepoLink.builder()
        .id(UUID.randomUUID())
        .projectId(projectId)
        .repoId(repo.getId())
        .build();
  }

  private UsernamePasswordAuthenticationToken fakeAuth() {
    // Auth name is the user UUID — controller tries to look up PAT, mock returns empty.
    return new UsernamePasswordAuthenticationToken(UUID.randomUUID().toString(), "n/a");
  }
}

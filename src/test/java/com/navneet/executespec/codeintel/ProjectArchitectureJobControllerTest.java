package com.navneet.executespec.codeintel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.project.service.ProjectRepoService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ProjectArchitectureJobControllerTest {

  private ProjectAccessGuard guard;
  private ProjectRepoService repos;
  private ProjectArchitectureJobService jobs;
  private ProjectArchitectureController controller;
  private AppUserDetails principal;

  @BeforeEach
  void setUp() {
    guard = mock(ProjectAccessGuard.class);
    repos = mock(ProjectRepoService.class);
    jobs = mock(ProjectArchitectureJobService.class);
    controller =
        new ProjectArchitectureController(
            mock(ProjectArchitectureService.class),
            jobs,
            mock(StalenessService.class),
            mock(com.navneet.executespec.project.workspace.ProjectWorkspaceService.class),
            mock(ArchitectureDiffService.class),
            guard,
            repos,
            mock(com.navneet.executespec.project.repository.ProjectRepoRepository.class),
            mock(com.navneet.executespec.project.repository.ProjectRepoLinkRepository.class),
            mock(com.navneet.executespec.repo.ScmRepositoryClientRegistry.class),
            mock(com.navneet.executespec.repo.ScmCredentialResolver.class));
    principal =
        new AppUserDetails(
            AppUser.builder()
                .id("actor-1")
                .email("actor@example.com")
                .status("ACTIVE")
                .globalRole("DEVELOPER")
                .build(),
            null);
  }

  @Test
  void postIsWriteGuardedAndReturnsStableAcceptedJobWithoutCredentialFields() {
    var job = job(ProjectArchitectureJobStore.Status.QUEUED);
    when(jobs.request("project-1", "actor-1")).thenReturn(job);

    var response = controller.generateAsync(principal, "project-1");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(response.getHeaders().getLocation())
        .hasPath("/api/projects/project-1/architecture/jobs/" + job.id());
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().id()).isEqualTo(job.id());
    assertThat(response.getBody().attemptCount()).isEqualTo(job.attemptCount());
    assertThat(response.getBody().deadlineAt()).isEqualTo(job.deadlineAt());
    assertThat(
            List.of(ProjectArchitectureController.ArchitectureJobDto.class.getRecordComponents())
                .stream()
                .map(component -> component.getName().toLowerCase()))
        .noneMatch(
            name ->
                name.contains("credential")
                    || name.contains("secret")
                    || name.equals("pat")
                    || name.contains("api_key"));
    verify(guard).assertCanWrite("project-1", principal);
    verify(repos)
        .requireProjectCapability(
            "project-1",
            com.navneet.executespec.repo.ScmCapability.ARCHITECTURE,
            "generate project architecture");
  }

  @Test
  void pollingIsReadGuarded() {
    var job = job(ProjectArchitectureJobStore.Status.RUNNING);
    when(jobs.get("project-1", job.id())).thenReturn(job);

    var response = controller.architectureJob(principal, "project-1", job.id());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(guard).assertCanRead("project-1", principal);
  }

  private static ProjectArchitectureJobStore.Job job(ProjectArchitectureJobStore.Status status) {
    return ProjectArchitectureJobFixture.job(
        status, status == ProjectArchitectureJobStore.Status.QUEUED ? "PREPARING" : "EXECUTING");
  }
}

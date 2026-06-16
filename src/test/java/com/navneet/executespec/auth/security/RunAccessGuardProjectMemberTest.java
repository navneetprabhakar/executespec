package com.navneet.executespec.auth.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class RunAccessGuardProjectMemberTest {

  private final StateStore stateStore = mock(StateStore.class);
  private final ProjectMemberRepository projectMemberRepo = mock(ProjectMemberRepository.class);
  private final RunAccessGuard guard = new RunAccessGuard(stateStore, projectMemberRepo);

  @Test
  void allowsExternalProjectMemberToReadRunInHostOrgProject() {
    PipelineRun run = projectRun();
    AppUser invitedUser =
        AppUser.builder()
            .id("invitee")
            .email("invitee@example.com")
            .firstName("Invitee")
            .globalRole("USER")
            .status("ACTIVE")
            .orgId("invitee-org")
            .build();

    when(projectMemberRepo.existsByProjectIdAndUserId("project-1", "invitee")).thenReturn(true);

    assertDoesNotThrow(() -> guard.assertAccess(run, new AppUserDetails(invitedUser, null)));
  }

  @Test
  void rejectsDifferentOrgUserWithoutProjectMembership() {
    PipelineRun run = projectRun();
    AppUser outsider =
        AppUser.builder()
            .id("outsider")
            .email("outsider@example.com")
            .firstName("Outsider")
            .globalRole("USER")
            .status("ACTIVE")
            .orgId("other-org")
            .build();

    when(projectMemberRepo.existsByProjectIdAndUserId("project-1", "outsider")).thenReturn(false);

    assertThrows(
        ResponseStatusException.class,
        () -> guard.assertAccess(run, new AppUserDetails(outsider, null)));
  }

  private static PipelineRun projectRun() {
    PipelineRun run = new PipelineRun();
    run.setRunId("run-1");
    run.setOrgId("host-org");
    run.setProjectId("project-1");
    run.setCreatedByUserId("host-user");
    return run;
  }
}

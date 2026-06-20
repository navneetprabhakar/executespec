package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AgentGateReadServiceTest {

  @Mock PipelineRunRepository pipelineRunRepo;
  @Mock RunAccessGuard runAccessGuard;
  @Mock ProjectRepository projectRepo;

  private AgentGateReadService service;

  @BeforeEach
  void setUp() {
    service =
        new AgentGateReadService(
            pipelineRunRepo,
            runAccessGuard,
            new AgentGateDecisionCardFactory(projectRepo, new ObjectMapper()));
  }

  @Test
  void listsAwaitingGatesWithCappedLimit() {
    PipelineRun run = run("run-1", "AWAITING_APPROVAL");
    run.setProjectId("p1");
    when(pipelineRunRepo.findByCreatedByUserIdAndStatusOrderByStartedAtDesc(
            eq("u1"), eq("AWAITING_APPROVAL"), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(run)));
    when(projectRepo.findById("p1")).thenReturn(Optional.of(project("Project One")));

    var cards = service.list(user(), 500);

    assertThat(cards).hasSize(1);
    assertThat(cards.get(0).runId()).isEqualTo("run-1");
    assertThat(cards.get(0).projectName()).isEqualTo("Project One");

    var pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(pipelineRunRepo)
        .findByCreatedByUserIdAndStatusOrderByStartedAtDesc(
            eq("u1"), eq("AWAITING_APPROVAL"), pageable.capture());
    assertThat(pageable.getValue().getPageSize()).isEqualTo(50);
  }

  @Test
  void getsSingleAwaitingGateThroughRunAccessGuard() {
    PipelineRun run = run("run-2", "AWAITING_APPROVAL");
    when(runAccessGuard.requireRunAccess(eq("run-2"), any(AppUserDetails.class))).thenReturn(run);

    var card = service.get("run-2", user());

    assertThat(card.runId()).isEqualTo("run-2");
    assertThat(card.status()).isEqualTo("AWAITING_APPROVAL");
    verify(runAccessGuard).requireRunAccess(eq("run-2"), any(AppUserDetails.class));
  }

  @Test
  void rejectsSingleGateWhenRunIsNotAwaitingApproval() {
    when(runAccessGuard.requireRunAccess(eq("run-3"), any(AppUserDetails.class)))
        .thenReturn(run("run-3", "COMPLETED"));

    assertThatThrownBy(() -> service.get("run-3", user()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("run is not awaiting approval");
  }

  private static PipelineRun run(String runId, String status) {
    PipelineRun run = PipelineRun.builder().runId(runId).status(status).build();
    run.setUserRequest("Implement OAuth login");
    run.setPlanJson("{\"tasks\":[{\"title\":\"Auth\"},{\"title\":\"Session\"}]}");
    run.setAiProvider("DEEPSEEK");
    run.setPlannerModel("deepseek-v4-flash-native");
    return run;
  }

  private static Project project(String name) {
    Project project = new Project();
    project.setId("p1");
    project.setName(name);
    return project;
  }

  private static AppUserDetails user() {
    AppUser user = new AppUser();
    user.setId("u1");
    user.setOrgId("org-1");
    user.setGlobalRole("USER");
    user.setTier("PRO");
    user.setEmail("u1@example.com");
    return new AppUserDetails(user, null);
  }
}

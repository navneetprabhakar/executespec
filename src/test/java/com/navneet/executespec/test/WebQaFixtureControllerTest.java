package com.navneet.executespec.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.repository.AgentActionEventRepository;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.agentsession.repository.AgentTurnRepository;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.testgen.repository.TestPlanRepository;
import com.navneet.executespec.testgen.repository.TestResultRepository;
import com.navneet.executespec.testgen.repository.TestTaskRepository;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

class WebQaFixtureControllerTest {

  @Test
  void cleanupDeletesSeededAgentGraphForTheAuthenticatedUser() throws Exception {
    AgentSession session = AgentSession.builder().id("web-qa-session").build();
    AgentSessionRepository sessions = mock(AgentSessionRepository.class);
    PipelineRunRepository runs = mock(PipelineRunRepository.class);
    PipelineRun run =
        PipelineRun.builder()
            .runId("web-qa-run")
            .createdByUserId("user-1")
            .status("AWAITING_APPROVAL")
            .build();
    AgentTaskRepository tasks = mock(AgentTaskRepository.class);
    when(tasks.findByRunId("web-qa-run")).thenReturn(List.of());
    ValidationReportRepository reports = mock(ValidationReportRepository.class);
    TestPlanRepository plans = mock(TestPlanRepository.class);
    when(reports.findByRunId("web-qa-run")).thenReturn(java.util.Optional.empty());
    when(plans.findByRunId("web-qa-run")).thenReturn(java.util.Optional.empty());
    when(runs.findByCreatedByUserIdOrderByStartedAtDesc(eq("user-1"), any()))
        .thenReturn(new PageImpl<>(List.of(run)));
    when(sessions.findByUserIdAndIdStartingWith("user-1", "web-qa-")).thenReturn(List.of(session));
    AgentActionEventRepository events = mock(AgentActionEventRepository.class);
    AgentTurnRepository turns = mock(AgentTurnRepository.class);
    AppUser user =
        AppUser.builder()
            .id("user-1")
            .email("admin@example.com")
            .firstName("Admin")
            .globalRole("SUPER_ADMIN")
            .orgId("org-1")
            .status("ACTIVE")
            .build();
    WebQaFixtureController controller =
        new WebQaFixtureController(
            runs,
            tasks,
            reports,
            plans,
            mock(TestTaskRepository.class),
            mock(TestResultRepository.class),
            sessions,
            turns,
            events,
            mock(SseBroadcaster.class));

    Map<String, Object> result = controller.cleanup(new AppUserDetails(user, null));

    assertThat(result).containsEntry("deletedAgentSessions", 1);
    assertThat(result).containsEntry("deletedRuns", 1);
    verify(runs).delete(run);
    verify(events).deleteBySessionId("web-qa-session");
    verify(turns).deleteBySessionId("web-qa-session");
    verify(sessions).delete(session);
  }

  @Test
  void advanceFixtureTransitionsGateToExecutingThenCompleteWithoutProviderWork() {
    PipelineRun run =
        PipelineRun.builder()
            .runId("web-qa-gate")
            .createdByUserId("user-1")
            .status("AWAITING_APPROVAL")
            .build();
    PipelineRunRepository runs = mock(PipelineRunRepository.class);
    when(runs.findById("web-qa-gate")).thenReturn(java.util.Optional.of(run));
    AgentTask task = AgentTask.builder().taskId("task-1").runId("web-qa-gate").build();
    AgentTaskRepository tasks = mock(AgentTaskRepository.class);
    when(tasks.findByRunId("web-qa-gate")).thenReturn(List.of(task));
    WebQaFixtureController controller = controller(runs, mock(AgentSessionRepository.class), tasks);
    AppUserDetails user = user();

    assertThat(
            controller.advance(
                "web-qa-gate", new WebQaFixtureController.AdvanceRequest("EXECUTING"), user))
        .extracting(WebQaFixtureController.AdvanceResponse::persistedStatus)
        .isEqualTo("WORKING");
    assertThat(task.getStatus()).isEqualTo("WORKING");
    assertThat(
            controller.advance(
                "web-qa-gate", new WebQaFixtureController.AdvanceRequest("COMPLETE"), user))
        .extracting(WebQaFixtureController.AdvanceResponse::persistedStatus)
        .isEqualTo("COMPLETE");
    assertThat(task.getStatus()).isEqualTo("COMPLETE");
    verify(runs, times(2)).save(run);
    verify(tasks, times(2)).save(task);
  }

  @Test
  void advanceRejectsNonFixtureAndInvalidTransition() {
    PipelineRun run =
        PipelineRun.builder()
            .runId("web-qa-gate")
            .createdByUserId("user-1")
            .status("AWAITING_APPROVAL")
            .build();
    PipelineRunRepository runs = mock(PipelineRunRepository.class);
    when(runs.findById("web-qa-gate")).thenReturn(java.util.Optional.of(run));
    WebQaFixtureController controller = controller(runs, mock(AgentSessionRepository.class));
    AppUserDetails user = user();
    assertThatThrownBy(
            () ->
                controller.advance(
                    "real-run", new WebQaFixtureController.AdvanceRequest("EXECUTING"), user))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThatThrownBy(
            () ->
                controller.advance(
                    "web-qa-gate", new WebQaFixtureController.AdvanceRequest("COMPLETE"), user))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }

  private static AppUserDetails user() {
    return new AppUserDetails(
        AppUser.builder()
            .id("user-1")
            .email("admin@example.com")
            .globalRole("SUPER_ADMIN")
            .orgId("org-1")
            .status("ACTIVE")
            .build(),
        null);
  }

  private static WebQaFixtureController controller(
      PipelineRunRepository runs, AgentSessionRepository sessions) {
    return controller(runs, sessions, mock(AgentTaskRepository.class));
  }

  private static WebQaFixtureController controller(
      PipelineRunRepository runs, AgentSessionRepository sessions, AgentTaskRepository tasks) {
    return new WebQaFixtureController(
        runs,
        tasks,
        mock(ValidationReportRepository.class),
        mock(TestPlanRepository.class),
        mock(TestTaskRepository.class),
        mock(TestResultRepository.class),
        sessions,
        mock(AgentTurnRepository.class),
        mock(AgentActionEventRepository.class),
        mock(SseBroadcaster.class));
  }
}

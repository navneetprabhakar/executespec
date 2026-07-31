package com.navneet.executespec.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.repository.AgentActionEventRepository;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.agentsession.repository.AgentTurnRepository;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.harness.entity.GateRecord;
import com.navneet.executespec.harness.repository.GateRepository;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.testgen.model.TestPlan;
import com.navneet.executespec.testgen.model.TestResult;
import com.navneet.executespec.testgen.model.TestTask;
import com.navneet.executespec.testgen.repository.TestPlanRepository;
import com.navneet.executespec.testgen.repository.TestResultRepository;
import com.navneet.executespec.testgen.repository.TestTaskRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.PageImpl;
import org.springframework.web.server.ResponseStatusException;

class WebQaFixtureControllerTest {

  @Test
  void cleanupRecognizesSiblingPrefixedFixtureWorkspaceSafely() {
    WebQaFixtureController controller =
        controller(mock(PipelineRunRepository.class), mock(AgentSessionRepository.class));

    assertThat(controller.isFixtureWorkspace(Path.of("workspace/web-qa-123"))).isTrue();
    assertThat(controller.isFixtureWorkspace(Path.of("workspace/customer-run"))).isFalse();
    assertThat(controller.isFixtureWorkspace(Path.of("workspace/web-qa/nested"))).isFalse();
  }

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
    GateRepository gates = mock(GateRepository.class);
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
    AppUserRepository users = mock(AppUserRepository.class);
    AppUser user =
        AppUser.builder()
            .id("user-1")
            .email("admin@example.com")
            .firstName("Admin")
            .globalRole("SUPER_ADMIN")
            .orgId("org-1")
            .status("ACTIVE")
            .deepSeekApiKey("web-qa-disabled-provider-key")
            .build();
    when(users.findById("user-1")).thenReturn(java.util.Optional.of(user));
    WebQaFixtureController controller =
        new WebQaFixtureController(
            runs,
            gates,
            tasks,
            reports,
            plans,
            mock(TestTaskRepository.class),
            mock(TestResultRepository.class),
            sessions,
            turns,
            events,
            users,
            mock(SseBroadcaster.class),
            featureFlags(),
            webQaEnvironment(),
            evidenceFixtures(),
            mock(com.navneet.executespec.project.security.ProjectAccessGuard.class));

    Map<String, Object> result = controller.cleanup(new AppUserDetails(user, null));

    assertThat(result).containsEntry("deletedAgentSessions", 1);
    assertThat(result).containsEntry("deletedRuns", 1);
    verify(gates).findFirstByRunIdAndGateType("web-qa-run", "PLAN");
    verify(runs).delete(run);
    verify(events).deleteBySessionId("web-qa-session");
    verify(turns).deleteBySessionId("web-qa-session");
    verify(sessions).delete(session);
    verify(users).save(user);
    assertThat(user.getDeepSeekApiKey()).isNull();
  }

  @Test
  void cleanupAlsoDeletesTheExactRealBrowserSessionOwnedByTheAuthenticatedUser() throws Exception {
    AgentSession seeded = AgentSession.builder().id("web-qa-session").userId("user-1").build();
    AgentSession created = AgentSession.builder().id("browser-session").userId("user-1").build();
    AgentSessionRepository sessions = mock(AgentSessionRepository.class);
    when(sessions.findByUserIdAndIdStartingWith("user-1", "web-qa-")).thenReturn(List.of(seeded));
    when(sessions.findById("browser-session")).thenReturn(java.util.Optional.of(created));
    PipelineRunRepository runs = mock(PipelineRunRepository.class);
    when(runs.findByCreatedByUserIdOrderByStartedAtDesc(eq("user-1"), any()))
        .thenReturn(new PageImpl<>(List.of()));
    WebQaFixtureController controller = controller(runs, sessions);

    Map<String, Object> result = controller.cleanup(user(), "browser-session", false);

    assertThat(result).containsEntry("deletedAgentSessions", 2);
    verify(sessions).delete(seeded);
    verify(sessions).delete(created);
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
  void recoveryFixtureCannotBypassRealRestartLifecycle() {
    PipelineRun run =
        PipelineRun.builder()
            .runId("web-qa-recovery")
            .createdByUserId("user-1")
            .status("AWAITING_APPROVAL")
            .build();
    PipelineRunRepository runs = mock(PipelineRunRepository.class);
    when(runs.findById("web-qa-recovery")).thenReturn(java.util.Optional.of(run));
    AgentTask task =
        AgentTask.builder().taskId("task-1").runId("web-qa-recovery").status("PENDING").build();
    AgentTaskRepository tasks = mock(AgentTaskRepository.class);
    when(tasks.findByRunId("web-qa-recovery")).thenReturn(List.of(task));
    WebQaFixtureController controller = controller(runs, mock(AgentSessionRepository.class), tasks);

    assertThatThrownBy(
            () ->
                controller.advance(
                    "web-qa-recovery",
                    new WebQaFixtureController.AdvanceRequest("RECOVERY"),
                    user()))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
        .isEqualTo(400);
    verify(runs, never()).save(run);
    verify(tasks, never()).save(task);
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

  @Test
  void visualFixtureDoesNotPublishSyntheticVerifiedScore() throws Exception {
    PipelineRunRepository runs = mock(PipelineRunRepository.class);
    AgentTaskRepository tasks = mock(AgentTaskRepository.class);
    ValidationReportRepository reports = mock(ValidationReportRepository.class);
    TestPlanRepository plans = mock(TestPlanRepository.class);
    TestTaskRepository testTasks = mock(TestTaskRepository.class);
    TestResultRepository testResults = mock(TestResultRepository.class);

    when(plans.save(any(TestPlan.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(testTasks.save(any(TestTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

    WebQaFixtureController controller =
        new WebQaFixtureController(
            runs,
            mock(GateRepository.class),
            tasks,
            reports,
            plans,
            testTasks,
            testResults,
            mock(AgentSessionRepository.class),
            mock(AgentTurnRepository.class),
            mock(AgentActionEventRepository.class),
            mock(AppUserRepository.class),
            mock(SseBroadcaster.class),
            featureFlags(),
            webQaEnvironment(),
            evidenceFixtures(),
            mock(com.navneet.executespec.project.security.ProjectAccessGuard.class));

    PipelineRun run =
        controller.createRun("user-1", "org-1", "COMPLETE", "CLEAN_COMPLETION", "fixture", true);

    ArgumentCaptor<ValidationReport> reportCaptor = ArgumentCaptor.forClass(ValidationReport.class);
    verify(reports).save(reportCaptor.capture());
    assertThat(run.getRunId()).startsWith("web-qa-");
    assertThat(reportCaptor.getValue().getVerifiedScore()).isNull();
    ArgumentCaptor<TestResult> resultCaptor = ArgumentCaptor.forClass(TestResult.class);
    verify(testResults, times(2)).save(resultCaptor.capture());
    assertThat(resultCaptor.getAllValues())
        .extracting(TestResult::getTestMethod)
        .containsExactly("fixture smoke", "fixture edge case");
  }

  @Test
  void seededApprovalFixturePersistsCoherentLegacyPlanGate() {
    GateRepository gates = mock(GateRepository.class);
    WebQaFixtureController controller =
        new WebQaFixtureController(
            mock(PipelineRunRepository.class),
            gates,
            mock(AgentTaskRepository.class),
            mock(ValidationReportRepository.class),
            mock(TestPlanRepository.class),
            mock(TestTaskRepository.class),
            mock(TestResultRepository.class),
            mock(AgentSessionRepository.class),
            mock(AgentTurnRepository.class),
            mock(AgentActionEventRepository.class),
            mock(AppUserRepository.class),
            mock(SseBroadcaster.class),
            featureFlags(),
            webQaEnvironment(),
            evidenceFixtures(),
            mock(com.navneet.executespec.project.security.ProjectAccessGuard.class));

    controller.createLegacyPlanGate("web-qa-gate");

    ArgumentCaptor<GateRecord> gate = ArgumentCaptor.forClass(GateRecord.class);
    verify(gates).save(gate.capture());
    assertThat(gate.getValue())
        .extracting(
            GateRecord::getGateKey,
            GateRecord::getRunId,
            GateRecord::getGateType,
            GateRecord::getStatus,
            GateRecord::getBindingMode,
            GateRecord::getBindingStatus)
        .containsExactly(
            "gate:plan:web-qa-gate", "web-qa-gate", "PLAN", "PENDING", "LEGACY", "UNBOUND");
  }

  @Test
  void safetyEvidenceRequiresOnlyTheProviderFreeWebQaProfile() {
    assertThat(WebQaFixtureController.class.getAnnotation(Profile.class).value())
        .containsExactly("web-qa");
    WebQaFixtureController controller =
        controller(mock(PipelineRunRepository.class), mock(AgentSessionRepository.class));

    assertThat(controller.safetyEvidence())
        .satisfies(
            evidence -> {
              assertThat(evidence.safe()).isTrue();
              assertThat(evidence.activeProfiles()).containsExactly("web-qa");
              assertThat(evidence.liveLlmEnabled()).isFalse();
              assertThat(evidence.featureFlagSnapshotSha256()).hasSize(64);
            });

    Environment unsafe = mock(Environment.class);
    when(unsafe.getActiveProfiles()).thenReturn(new String[] {"web-qa", "dev"});
    WebQaFixtureController sharedProfileController =
        new WebQaFixtureController(
            mock(PipelineRunRepository.class),
            mock(GateRepository.class),
            mock(AgentTaskRepository.class),
            mock(ValidationReportRepository.class),
            mock(TestPlanRepository.class),
            mock(TestTaskRepository.class),
            mock(TestResultRepository.class),
            mock(AgentSessionRepository.class),
            mock(AgentTurnRepository.class),
            mock(AgentActionEventRepository.class),
            mock(AppUserRepository.class),
            mock(SseBroadcaster.class),
            featureFlags(),
            unsafe,
            evidenceFixtures(),
            mock(com.navneet.executespec.project.security.ProjectAccessGuard.class));
    assertThat(sharedProfileController.safetyEvidence().safe()).isFalse();
  }

  @Test
  void seedFailsBeforeAnyWriteWhenTheRuntimeIsNotProviderFreeAndIsolated() {
    PipelineRunRepository runs = mock(PipelineRunRepository.class);
    AgentSessionRepository sessions = mock(AgentSessionRepository.class);
    Environment unsafe = mock(Environment.class);
    when(unsafe.getActiveProfiles()).thenReturn(new String[] {"web-qa", "dev"});
    WebQaFixtureController controller =
        new WebQaFixtureController(
            runs,
            mock(GateRepository.class),
            mock(AgentTaskRepository.class),
            mock(ValidationReportRepository.class),
            mock(TestPlanRepository.class),
            mock(TestTaskRepository.class),
            mock(TestResultRepository.class),
            sessions,
            mock(AgentTurnRepository.class),
            mock(AgentActionEventRepository.class),
            mock(AppUserRepository.class),
            mock(SseBroadcaster.class),
            featureFlags(),
            unsafe,
            evidenceFixtures(),
            mock(com.navneet.executespec.project.security.ProjectAccessGuard.class));

    assertThatThrownBy(() -> controller.seed(user()))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
        .isEqualTo(503);
    verifyNoInteractions(runs, sessions);
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
        mock(GateRepository.class),
        tasks,
        mock(ValidationReportRepository.class),
        mock(TestPlanRepository.class),
        mock(TestTaskRepository.class),
        mock(TestResultRepository.class),
        sessions,
        mock(AgentTurnRepository.class),
        mock(AgentActionEventRepository.class),
        mock(AppUserRepository.class),
        mock(SseBroadcaster.class),
        featureFlags(),
        webQaEnvironment(),
        evidenceFixtures(),
        mock(com.navneet.executespec.project.security.ProjectAccessGuard.class));
  }

  private static FeatureFlagService featureFlags() {
    FeatureFlagService flags = mock(FeatureFlagService.class);
    when(flags.listAll()).thenReturn(List.of());
    return flags;
  }

  private static Environment webQaEnvironment() {
    Environment environment = mock(Environment.class);
    when(environment.getActiveProfiles()).thenReturn(new String[] {"web-qa"});
    return environment;
  }

  private static WebQaProjectEvidenceFixtures evidenceFixtures() {
    WebQaProjectEvidenceFixtures fixtures = mock(WebQaProjectEvidenceFixtures.class);
    org.mockito.Mockito.when(
            fixtures.seedAgentSessionEvidence(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            inv ->
                new WebQaProjectEvidenceFixtures.SessionEvidence(
                    "web-qa-session",
                    com.navneet.executespec.agentsession.entity.AgentTurn.builder()
                        .id("web-qa-turn")
                        .sessionId("web-qa-session")
                        .build()));
    return fixtures;
  }
}

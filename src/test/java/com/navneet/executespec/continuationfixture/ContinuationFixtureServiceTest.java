package com.navneet.executespec.continuationfixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.execution.model.GoalContinuationAdvisorRequest;
import com.navneet.executespec.execution.model.GoalContinuationPolicyDecision;
import com.navneet.executespec.execution.model.RunContinuationDecision;
import com.navneet.executespec.execution.service.GoalContinuationAdvisor;
import com.navneet.executespec.harness.TaskStatusPersistenceService;
import com.navneet.executespec.router.ModelProviderScore;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ContinuationFixtureServiceTest {

  @ParameterizedTest
  @MethodSource("scenarios")
  void createsRealRowsAndDurableSseEvidenceWithoutProviderCalls(
      ContinuationFixtureScenario scenario) {
    PipelineRunRepository runs = mock(PipelineRunRepository.class);
    AgentTaskRepository tasks = mock(AgentTaskRepository.class);
    GoalContinuationAdvisor advisor = mock(GoalContinuationAdvisor.class);
    TaskStatusPersistenceService persistence = mock(TaskStatusPersistenceService.class);
    SseBroadcaster broadcaster = mock(SseBroadcaster.class);
    AtomicReference<PipelineRun> savedRun = new AtomicReference<>();
    AtomicReference<AgentTask> savedTask = new AtomicReference<>();
    when(runs.saveAndFlush(any(PipelineRun.class)))
        .thenAnswer(
            invocation -> {
              savedRun.set(invocation.getArgument(0));
              return invocation.getArgument(0);
            });
    when(tasks.saveAndFlush(any(AgentTask.class)))
        .thenAnswer(
            invocation -> {
              savedTask.set(invocation.getArgument(0));
              return invocation.getArgument(0);
            });
    when(advisor.advise(any(GoalContinuationAdvisorRequest.class)))
        .thenAnswer(invocation -> decisionFor(invocation.getArgument(0)));

    var service =
        new ContinuationFixtureService(
            runs, tasks, advisor, persistence, broadcaster, new ObjectMapper());
    var result = service.create(scenario, actor());

    assertThat(result.scenario()).isEqualTo(scenario);
    assertThat(result.providerCallsMade()).isFalse();
    assertThat(savedRun.get().getSourceType()).isEqualTo(ContinuationFixtureService.SOURCE_TYPE);
    assertThat(savedRun.get().getActualCostUsd()).isZero();
    assertThat(savedTask.get().getInputTokens()).isZero();
    assertThat(savedTask.get().getOutputTokens()).isZero();
    verify(advisor).advise(any(GoalContinuationAdvisorRequest.class));
    verify(persistence).persistGoalDecisionJson(anyString(), anyString(), anyString(), anyString());
    verify(broadcaster, atLeastOnce()).emit(anyString(), any());
  }

  @Test
  void fixtureDetectionIsSourceBoundAndDoesNotClaimOrdinaryRuns() {
    PipelineRunRepository runs = mock(PipelineRunRepository.class);
    var service =
        new ContinuationFixtureService(
            runs,
            mock(AgentTaskRepository.class),
            mock(GoalContinuationAdvisor.class),
            mock(TaskStatusPersistenceService.class),
            mock(SseBroadcaster.class),
            new ObjectMapper());
    PipelineRun fixture =
        PipelineRun.builder()
            .runId("fixture")
            .sourceType(ContinuationFixtureService.SOURCE_TYPE)
            .build();
    PipelineRun ordinary = PipelineRun.builder().runId("ordinary").sourceType("MANUAL").build();
    when(runs.findById("fixture")).thenReturn(Optional.of(fixture));
    when(runs.findById("ordinary")).thenReturn(Optional.of(ordinary));

    assertThat(service.isFixtureRun("fixture")).isTrue();
    assertThat(service.isFixtureRun("ordinary")).isFalse();
    assertThat(service.isFixtureRun("missing")).isFalse();
  }

  static Stream<ContinuationFixtureScenario> scenarios() {
    return Stream.of(ContinuationFixtureScenario.values());
  }

  private static AppUserDetails actor() {
    return new AppUserDetails(
        AppUser.builder()
            .id("admin-1")
            .email("admin@example.com")
            .firstName("Admin")
            .globalRole("SUPER_ADMIN")
            .tier("ENTERPRISE")
            .status("ACTIVE")
            .orgId("org-1")
            .build(),
        null);
  }

  private static GoalContinuationPolicyDecision decisionFor(
      GoalContinuationAdvisorRequest request) {
    RunContinuationDecision action;
    String provider;
    String model;
    boolean benchmark;
    if (request.taskSimplerThanExpected()) {
      action = RunContinuationDecision.STEP_DOWN_MODEL;
      provider = "anthropic";
      model = "claude-haiku-4-5-20251001";
      benchmark = true;
    } else if (request.providerBehaviorPoor()) {
      action = RunContinuationDecision.CHANGE_PROVIDER;
      provider = "openai";
      model = "gpt-5.4";
      benchmark = true;
    } else {
      action = RunContinuationDecision.STEP_UP_MODEL;
      provider = "anthropic";
      model = "claude-sonnet-5";
      benchmark = !request.benchmarkQualityPriorRequiredForAutomaticContinuation();
    }
    ModelProviderScore candidate =
        new ModelProviderScore(
            provider,
            model,
            request.taskCategory(),
            95,
            10,
            100,
            200,
            70,
            benchmark,
            true,
            true,
            List.of("fixture"),
            true,
            0);
    boolean blocked =
        request.routePinned()
            || request.expensiveContinuationRequiresConfirmation()
            || (request.benchmarkQualityPriorRequiredForAutomaticContinuation() && !benchmark);
    if (!blocked) {
      return new GoalContinuationPolicyDecision(
          action, Optional.of(candidate), List.of("fixture"), Map.of());
    }
    String reason =
        request.routePinned()
            ? "ROUTE_PINNED"
            : request.expensiveContinuationRequiresConfirmation()
                ? "EXPENSIVE_CONTINUATION_CONFIRMATION_REQUIRED"
                : "BENCHMARK_PRIOR_REQUIRED";
    return new GoalContinuationPolicyDecision(
        RunContinuationDecision.STOP_BLOCKED,
        Optional.of(candidate),
        List.of("fixture blocked"),
        Map.of("proposedAction", action.name(), "blockedAutomaticContinuationReason", reason));
  }
}

package com.navneet.executespec.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.agent.PlannedTask.Complexity;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.context.ContextStore;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.execution.service.GoalContinuationAdvisor;
import com.navneet.executespec.router.InitialRoutingDecision;
import com.navneet.executespec.router.InitialRoutingService;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelMatrixProperties;
import com.navneet.executespec.router.ModelProviderCandidateRequest;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.tool.BuildProjectTool;
import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class SequentialExecutorInitialRoutingTest {

  private FeatureFlagService featureFlags;
  private InitialRoutingService initialRouting;
  private SequentialExecutor executor;
  private ExecutionRequest request;
  private PlannedTask task;

  @BeforeEach
  void setUp() {
    featureFlags = mock(FeatureFlagService.class);
    initialRouting = mock(InitialRoutingService.class);
    var availableProviders = mock(com.navneet.executespec.router.AvailableProviderResolver.class);
    when(availableProviders.resolve("run-1")).thenReturn(Set.of(LlmProvider.ANTHROPIC));
    executor =
        new SequentialExecutor(
            mock(com.navneet.executespec.engine.WorkerAgent.class),
            mock(BuildProjectTool.class),
            new ObjectMapper(),
            mock(com.navneet.executespec.observability.Metrics.class),
            mock(SseBroadcaster.class),
            mock(AgentTaskRepository.class),
            mock(ModelResolver.class),
            mock(ModelMatrixProperties.class),
            mock(com.navneet.executespec.project.rag.RetrievalService.class),
            mock(com.navneet.executespec.smoketest.WorkspaceTestRunner.class),
            featureFlags,
            mock(ContextStore.class),
            mock(RunCostTracker.class),
            mock(com.navneet.executespec.deployment.GcsTaskCheckpointService.class),
            availableProviders,
            mock(GoalContinuationAdvisor.class),
            null);
    ReflectionTestUtils.setField(executor, "initialRoutingService", initialRouting);
    task =
        PlannedTask.builder()
            .id("T1")
            .title("fix bug")
            .description("fix bug")
            .complexity(Complexity.MEDIUM)
            .touchesFiles(List.of("src/Main.java"))
            .dependsOn(List.of())
            .hardConstraints(List.of())
            .build();
    request =
        ExecutionRequest.builder()
            .plan(com.navneet.executespec.agent.Plan.builder().tasks(List.of(task)).build())
            .provider("openai")
            .model("current")
            .runContext(new ExecutionContext("run-1", "T1", Path.of("/tmp")))
            .routePinned(false)
            .build();
  }

  @Test
  void disabledKeepsRouteAndDoesNotCollectCandidates() {
    when(featureFlags.isEnabled(SequentialExecutor.FLAG_INITIAL_ROUTING)).thenReturn(false);

    InitialRoutingDecision result =
        executor.initialRouteFor(task, request, "openai", "current", true);

    assertThat(result.routeLabel()).isEqualTo("openai/current");
    assertThat(result.changed()).isFalse();
    verify(initialRouting, never()).decide(any(), any(), any());
  }

  @Test
  void enabledUsesObservedCandidateDecisionAndForwardsSignals() {
    when(featureFlags.isEnabled(SequentialExecutor.FLAG_INITIAL_ROUTING)).thenReturn(true);
    InitialRoutingDecision selected =
        new InitialRoutingDecision(
            "anthropic",
            "sonnet",
            true,
            true,
            List.of(),
            List.of(),
            List.of("observed quality evidence authorized initial route selection"));
    when(initialRouting.decide(any(), any(), any())).thenReturn(selected);

    InitialRoutingDecision result =
        executor.initialRouteFor(task, request, "openai", "current", true);

    assertThat(result.routeLabel()).isEqualTo("anthropic/sonnet");
    ArgumentCaptor<ModelProviderCandidateRequest> captor =
        ArgumentCaptor.forClass(ModelProviderCandidateRequest.class);
    verify(initialRouting)
        .decide(
            org.mockito.ArgumentMatchers.eq("openai"),
            org.mockito.ArgumentMatchers.eq("current"),
            captor.capture());
    assertThat(captor.getValue().taskCategory()).isEqualTo("BUG_FIX");
    assertThat(captor.getValue().providerAvailability()).containsEntry("anthropic", true);
    assertThat(captor.getValue().providerPreferenceOrder()).contains("openai");
  }

  @Test
  void pinnedRouteBypassesInitialSelectionEvenWhenEnabled() {
    when(featureFlags.isEnabled(SequentialExecutor.FLAG_INITIAL_ROUTING)).thenReturn(true);
    ExecutionRequest pinned =
        ExecutionRequest.builder()
            .plan(request.plan())
            .provider("openai")
            .model("pinned")
            .runContext(request.runContext())
            .routePinned(true)
            .build();

    InitialRoutingDecision result =
        executor.initialRouteFor(task, pinned, "openai", "pinned", true);

    assertThat(result.routeLabel()).isEqualTo("openai/pinned");
    assertThat(result.reasons()).anyMatch(reason -> reason.contains("route pinned"));
    verify(initialRouting, never()).decide(any(), any(), any());
  }

  @Test
  void noObservedEvidenceKeepsCurrentRoute() {
    when(featureFlags.isEnabled(SequentialExecutor.FLAG_INITIAL_ROUTING)).thenReturn(true);
    InitialRoutingDecision unchanged =
        new InitialRoutingDecision(
            "openai", "current", false, false, List.of(), List.of(), List.of());
    when(initialRouting.decide(any(), any(), any())).thenReturn(unchanged);

    InitialRoutingDecision result =
        executor.initialRouteFor(task, request, "openai", "current", true);

    assertThat(result.changed()).isFalse();
    assertThat(result.observedQualityEvidenceUsed()).isFalse();
  }
}

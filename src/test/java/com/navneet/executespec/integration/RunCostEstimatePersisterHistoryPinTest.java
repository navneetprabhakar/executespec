package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.navneet.executespec.agent.ApiKeyResolver;
import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlanStrategy;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.cost.CostHistoryService;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;

class RunCostEstimatePersisterHistoryPinTest {

  private static final String RUN_ID = "run-1";
  private static final String PRO = "deepseek-v4-pro-native";

  private final PipelineRunRepository runs = mock(PipelineRunRepository.class);
  private final RunCostTracker tracker = mock(RunCostTracker.class);
  private final CostHistoryService history = mock(CostHistoryService.class);
  private final ApiKeyResolver keys = mock(ApiKeyResolver.class);
  private final RunCostEstimatePersister persister =
      new RunCostEstimatePersister(runs, tracker, history, keys);

  @Test
  void equivalentExplicitDeepSeekPinReusesTenantHistory() {
    PipelineRun run = pinnedProjectRun();
    RunCostTracker.Estimate historical = new RunCostTracker.Estimate(.01, .03, 1, 5, 1);
    when(runs.findById(RUN_ID)).thenReturn(Optional.of(run));
    when(tracker.expectedRouteFingerprint(
            "deepseek", List.of("LOW"), PlanStrategy.SINGLE_SHOT, null))
        .thenReturn("DEEPSEEK:" + PRO);
    when(history.estimateFromHistory(
            "org-a", "SINGLE_SHOT", "DEEPSEEK", "DEEPSEEK:" + PRO, 1, 1, "LOW:1,MEDIUM:0,HIGH:0"))
        .thenReturn(Optional.of(historical));

    persister.persistEstimate(RUN_ID, "deepseek", PRO, lowSingleShot(), null);

    assertThat(run.getCostEstimateLow()).isEqualTo(.01);
    assertThat(run.getCostEstimateHigh()).isEqualTo(.03);
    verify(tracker, never())
        .estimateRunTierAware(anyString(), any(), any(), anyInt(), anyBoolean(), anyString());
  }

  @Test
  void nonEquivalentPinBypassesModelBlindHistoryAndUsesExactModelFormula() {
    PipelineRun run = pinnedProjectRun();
    RunCostTracker.Estimate formula = new RunCostTracker.Estimate(.05, .15, 1, 5, 1);
    when(runs.findById(RUN_ID)).thenReturn(Optional.of(run));
    when(tracker.expectedRouteFingerprint(
            "deepseek", List.of("LOW"), PlanStrategy.SINGLE_SHOT, null))
        .thenReturn("DEEPSEEK:other-model");
    when(tracker.estimateRunTierAware(
            "deepseek", List.of("LOW"), PlanStrategy.SINGLE_SHOT, 1, true, PRO))
        .thenReturn(formula);

    persister.persistEstimate(RUN_ID, "deepseek", PRO, lowSingleShot(), null);

    assertThat(run.getCostEstimateLow()).isEqualTo(.05);
    assertThat(run.getCostEstimateHigh()).isEqualTo(.15);
    verify(history)
        .estimateFromHistory(
            "org-a",
            "SINGLE_SHOT",
            "DEEPSEEK",
            "DEEPSEEK:other-model",
            1,
            1,
            "LOW:1,MEDIUM:0,HIGH:0");
  }

  private PipelineRun pinnedProjectRun() {
    return PipelineRun.builder()
        .runId(RUN_ID)
        .orgId("org-a")
        .projectId("project-a")
        .createdByUserId("user-a")
        .planStrategy("SINGLE_SHOT")
        .routePinned(true)
        .requestedModel(PRO)
        .selectedRepoIds("[\"11111111-1111-1111-1111-111111111111\"]")
        .build();
  }

  private static Plan lowSingleShot() {
    return Plan.builder()
        .tasks(
            List.of(
                PlannedTask.builder()
                    .id("T1")
                    .title("Ping")
                    .complexity(PlannedTask.Complexity.LOW)
                    .build()))
        .build();
  }
}

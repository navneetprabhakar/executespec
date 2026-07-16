package com.navneet.executespec.harness;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.engine.WorkerAgent;
import com.navneet.executespec.engine.WorkerResult;
import com.navneet.executespec.engine.WorkerTaskRequest;
import com.navneet.executespec.router.TaskOutcomeMetricService;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.tool.BuildProjectTool;
import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.Test;

class SequentialExecutorTaskOutcomeMetricTest {

  @Test
  void recordsTerminalWorkerOutcomeWithTaskScopedUsage() {
    WorkerAgent worker = mock(WorkerAgent.class);
    when(worker.run(any(WorkerTaskRequest.class), any()))
        .thenReturn(
            WorkerResult.builder()
                .outcome(WorkerResult.Outcome.COMPLETED)
                .iterations(2)
                .toolCallsDispatched(1)
                .toolCallLog(List.of("writeFile -> OK"))
                .totalLatencyMs(25)
                .build());
    AgentTaskRepository taskRepository = mock(AgentTaskRepository.class);
    when(taskRepository.findByRunIdAndTaskId(any(), any())).thenReturn(Optional.empty());
    RunCostTracker costTracker = mock(RunCostTracker.class);
    when(costTracker.snapshotTask("run-metric", "T1"))
        .thenReturn(new RunCostTracker.Snapshot(100, 321, 0, 0, 0.01, 2));
    when(costTracker.snapshot("run-metric"))
        .thenReturn(new RunCostTracker.Snapshot(100, 321, 0, 0, 0.01, 2));
    TaskOutcomeMetricService metricService = mock(TaskOutcomeMetricService.class);

    SequentialExecutor executor =
        new SequentialExecutor(
            worker,
            mock(BuildProjectTool.class),
            new ObjectMapper(),
            mock(com.navneet.executespec.observability.Metrics.class),
            mock(com.navneet.executespec.sse.SseBroadcaster.class),
            taskRepository,
            null,
            null,
            null,
            null,
            mock(com.navneet.executespec.config.featureflag.FeatureFlagService.class),
            null,
            costTracker,
            mock(com.navneet.executespec.deployment.GcsTaskCheckpointService.class),
            mock(com.navneet.executespec.router.AvailableProviderResolver.class),
            null,
            null,
            metricService);
    PlannedTask task =
        PlannedTask.builder()
            .id("T1")
            .title("Implement auth fix")
            .complexity(PlannedTask.Complexity.LOW)
            .touchesFiles(List.of("src/AuthService.java"))
            .build();
    ExecutionRequest request =
        ExecutionRequest.builder()
            .plan(Plan.builder().planTitle("metric").tasks(List.of(task)).build())
            .provider("anthropic")
            .model("claude-sonnet-4-6")
            .runContext(new ExecutionContext("run-metric", "__root__", Path.of("/tmp/run-metric")))
            .enableSmokeTests(false)
            .build();

    executor.execute(request);

    verify(metricService)
        .record(
            eq("run-metric"),
            eq("BUG_FIX"),
            eq("LOW"),
            eq("java"),
            eq(1),
            any(TaskOutcome.class),
            eq(321),
            eq(4096),
            eq(2),
            eq(0.01));
  }
}

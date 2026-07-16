package com.navneet.executespec.eval.v2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.engine.WorkerAgent;
import com.navneet.executespec.engine.WorkerResult;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;

import org.junit.jupiter.api.Test;

class TierEvalRunnerWorkhorseDiagnosticsTest {

  @Test
  void workhorseRuntimeErrorPreservesWorkerDiagnosticInEvalDetail() {
    ModelResolver resolver = mock(ModelResolver.class);
    when(resolver.resolve(anyString(), any(), any(AgentTier.class)))
        .thenReturn(
            ResolvedModel.builder()
                .model("claude-sonnet-5")
                .requestedProvider(com.navneet.executespec.router.LlmProvider.ANTHROPIC)
                .actualProvider(com.navneet.executespec.router.LlmProvider.ANTHROPIC)
                .agentTier(AgentTier.WORKHORSE)
                .build());
    RunCostTracker costs = mock(RunCostTracker.class);
    when(costs.snapshot(anyString())).thenReturn(new RunCostTracker.Snapshot(0, 0, 0, 0, 0, 0));
    WorkerAgent worker = mock(WorkerAgent.class);
    when(worker.run(any()))
        .thenReturn(
            WorkerResult.builder()
                .outcome(WorkerResult.Outcome.ERROR)
                .iterations(1)
                .finalAssistantText(
                    "router error: IllegalStateException: Anthropic 404 model not found")
                .build());
    TierEvalRunner runner =
        new TierEvalRunner(
            mock(com.navneet.executespec.agent.LlmRouter.class),
            resolver,
            costs,
            new ObjectMapper(),
            worker);

    WorkhorseEvalCase testCase =
        new WorkhorseEvalCase() {
          @Override
          public String name() {
            return "diagnostic-case";
          }

          @Override
          public String taskPrompt() {
            return "add a function";
          }

          @Override
          public void seedWorkspace(Path workspaceDir) {}

          @Override
          public TierEvalCase.ValidationVerdict validate(Path workspaceDir, WorkerResult result) {
            return new TierEvalCase.ValidationVerdict(0, "no output");
          }
        };

    TierEvalResult result = runner.runWorkhorse(testCase, "anthropic");

    assertThat(result.failureMode()).isEqualTo(TierEvalFailureMode.RUNTIME_ERROR);
    assertThat(result.detail()).contains("workerError=router error: IllegalStateException");
    assertThat(result.detail()).contains("Anthropic 404 model not found");
  }
}

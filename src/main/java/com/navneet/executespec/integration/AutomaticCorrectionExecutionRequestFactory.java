package com.navneet.executespec.integration;

import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.harness.ExecutionRequest;
import com.navneet.executespec.router.ModelProviderScore;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.workspacegeneration.WorkspaceOrigin;

/** Builds the deliberately narrow worker request used by a post-review correction attempt. */
final class AutomaticCorrectionExecutionRequestFactory {

  private AutomaticCorrectionExecutionRequestFactory() {}

  static ExecutionRequest create(
      Plan plan,
      ModelProviderScore candidate,
      ExecutionContext context,
      boolean confirmationRequired,
      boolean benchmarkPriorRequired,
      int providerSwitches,
      int modelStepUps,
      int modelStepDowns,
      String originalGoal,
      String designBlock,
      String projectId,
      String workspaceSummary,
      WorkspaceOrigin workspaceOrigin) {
    return ExecutionRequest.builder()
        .plan(plan)
        .provider(candidate.provider())
        .model(candidate.model())
        .runContext(context)
        .maxIterationsPerTask(null)
        .enableSmokeTests(true)
        .requireConfirmationBeforeExpensiveContinuation(confirmationRequired)
        .requireBenchmarkPriorForAutomaticContinuation(benchmarkPriorRequired)
        .initialProviderSwitchesUsed(providerSwitches)
        .initialModelStepUpsUsed(modelStepUps)
        .initialModelStepDownsUsed(modelStepDowns)
        .originalGoal(originalGoal)
        .designBlock(designBlock)
        .workspaceOrigin(workspaceOrigin)
        .projectId(projectId)
        .workspaceSummary(workspaceSummary)
        .build();
  }
}

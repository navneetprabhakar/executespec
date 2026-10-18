package com.navneet.executespec.engine;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.integration.RunWorkspaceAuthorityService;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationDispatchAuthority;

import org.springframework.ai.chat.messages.AssistantMessage;

/** Propagates and enforces an automatic-correction workspace lease at worker tool boundaries. */
final class WorkerCorrectionToolFence {

  private WorkerCorrectionToolFence() {}

  static Future<?> submitCurrent(ExecutorService executor, Runnable dispatch) {
    RunWorkspaceAuthorityService.Lease correctionLease =
        RunWorkspaceAuthorityService.currentCorrectionToolLease();
    return executor.submit(
        () ->
            RunWorkspaceAuthorityService.withCorrectionToolLease(
                correctionLease,
                () -> {
                  dispatch.run();
                  return null;
                }));
  }

  static Future<?> submitRead(
      ExecutorService executor,
      WorkerAgent worker,
      Map<String, String> output,
      AssistantMessage.ToolCall call,
      WorkerTaskRequest request,
      WorkerDispatchMode mode) {
    return submitCurrent(
        executor, () -> output.put(safeId(call.id()), worker.dispatchOne(call, request, mode)));
  }

  static void assertDispatch(ExecutionContext context) {
    context.assertVerificationSandboxLive();
    RunWorkspaceAuthorityService.assertCorrectionToolDispatch(context.runId());
  }

  static void assertAttemptAdmission(WorkerTaskRequest request) {
    if (request.changeMode() == CodeChangeMode.BUILD) {
      request
          .context()
          .assertWorkspaceGenerationDispatch(WorkspaceGenerationDispatchAuthority.Phase.MUTATION);
    }
  }

  static String guardDispatch(
      WorkerTaskRequest request, RunCostTracker costTracker, WorkerDispatchMode mode) {
    assertAttemptAdmission(request);
    assertDispatch(request.context());
    return WorkerLiveBudgetGuard.guard(
        request, costTracker, request.workerStartedAtEpochMs(), mode);
  }

  private static String safeId(String id) {
    return id == null ? "" : id;
  }
}

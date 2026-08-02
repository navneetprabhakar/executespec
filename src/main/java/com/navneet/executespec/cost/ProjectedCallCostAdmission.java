package com.navneet.executespec.cost;

import java.util.List;

import com.navneet.executespec.agent.LlmRequest;
import com.navneet.executespec.context.ContextPlanner;
import com.navneet.executespec.router.ModelRegistry;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Conservative dollar-cap admission for the exact route about to reach provider transport. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectedCallCostAdmission {

  private final RunCostTracker costTracker;
  private final ModelRegistry modelRegistry;

  public void admit(LlmRequest request, List<ToolCallback> tools) {
    boolean runCapActive = request.runHardCapUsd() != null && request.runHardCapUsd() > 0;
    boolean taskCapActive = request.taskCostCapUsd() != null && request.taskCostCapUsd() > 0;
    if (!runCapActive && !taskCapActive) return;

    int maxOutputTokens =
        request.maxTokens() != null
            ? Math.max(0, request.maxTokens())
            : Math.max(0, modelRegistry.maxOutputForModel(request.model()));
    long estimatedInputTokens = ContextPlanner.estimateInputTokens(request.messages(), tools);
    var projection =
        costTracker.estimateConservativeCallCostUsd(
            request.provider(), request.model(), estimatedInputTokens, maxOutputTokens);
    if (projection.isEmpty()) {
      log.warn(
          "Projected call reserve unavailable run={} task={} provider={} model={}; continuing "
              + "with observed-spend ceilings only",
          request.context().runId(),
          request.context().taskId(),
          request.provider(),
          request.model());
      return;
    }

    double projectedUsd = projection.getAsDouble();
    String runId = request.context().runId();
    if (runCapActive) {
      reject(
          "run hard cap",
          costTracker.snapshot(runId).costUsd(),
          projectedUsd,
          request.runHardCapUsd());
    }
    if (taskCapActive) {
      reject(
          "task cost ceiling",
          costTracker.snapshotTask(runId, request.context().taskId()).costUsd(),
          projectedUsd,
          request.taskCostCapUsd());
    }
  }

  private static void reject(String label, double spentUsd, double projectedUsd, double capUsd) {
    if (spentUsd + projectedUsd <= capUsd) return;
    throw new ProjectedCallBudgetExceededException(label, capUsd, spentUsd, projectedUsd);
  }
}

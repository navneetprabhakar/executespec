package com.navneet.executespec.v2.harness;

import com.navneet.executespec.v2.agent.PlanV2;
import com.navneet.executespec.v2.tool.ExecutionContext;
import lombok.Builder;

/**
 * Input to {@link SequentialExecutorV2#execute(ExecutionRequest)}.
 *
 * @param plan              the plan to execute (typically from PlannerAgentV2)
 * @param provider          LLM provider for worker calls
 * @param model             LLM model for worker calls
 * @param runContext        execution context (workspace + ids). A per-task context is
 *                          derived from this by substituting taskId on each iteration.
 * @param maxIterationsPerTask  override for worker iteration cap per task (null = default 40)
 * @param enableSmokeTests  if false, skip smoke tests entirely (useful for tests where the
 *                          workspace has no buildable project)
 */
@Builder
public record ExecutionRequest(
        PlanV2 plan,
        String provider,
        String model,
        ExecutionContext runContext,
        Integer maxIterationsPerTask,
        Boolean enableSmokeTests,
        String designBlock) {

    public ExecutionRequest {
        if (plan == null) throw new IllegalArgumentException("plan required");
        if (plan.tasks().isEmpty()) throw new IllegalArgumentException("plan must contain at least one task");
        if (provider == null || provider.isBlank()) throw new IllegalArgumentException("provider required");
        if (model == null || model.isBlank()) throw new IllegalArgumentException("model required");
        if (runContext == null) throw new IllegalArgumentException("runContext required");
        if (enableSmokeTests == null) enableSmokeTests = true;
    }
}

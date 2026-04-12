package com.navneet.executespec.v2.tool;

import java.nio.file.Path;

/**
 * Per-task execution context passed to every tool invocation.
 *
 * <p>V2 is sequential — at any given moment, exactly one task is executing in a run,
 * so the context maps cleanly to a single workspace root. No worktrees, no per-task
 * branches; tools operate directly on the run's main workspace.
 *
 * @param runId         the V2 run identifier
 * @param taskId        the currently executing task's identifier (or {@code "__single_shot__"}
 *                      for the SINGLE_SHOT classification)
 * @param workspaceRoot absolute path to the run's workspace root — all relative paths
 *                      passed by the LLM are resolved against this
 */
public record ExecutionContext(String runId, String taskId, Path workspaceRoot) {

    public ExecutionContext {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId required");
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("taskId required");
        if (workspaceRoot == null) throw new IllegalArgumentException("workspaceRoot required");
        if (!workspaceRoot.isAbsolute()) {
            throw new IllegalArgumentException("workspaceRoot must be absolute: " + workspaceRoot);
        }
    }
}

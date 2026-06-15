package com.navneet.executespec.harness;

import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.tool.ExecutionContext;

import lombok.Builder;

/**
 * Input to {@link SequentialExecutor#execute(ExecutionRequest)}.
 *
 * @param plan the plan to execute (typically from PlannerAgent)
 * @param provider LLM provider for worker calls
 * @param model LLM model for worker calls
 * @param runContext execution context (workspace + ids). A per-task context is derived from this by
 *     substituting taskId on each iteration.
 * @param maxIterationsPerTask override for worker iteration cap per task (null = default 40)
 * @param enableSmokeTests if false, skip smoke tests entirely (useful for tests where the workspace
 *     has no buildable project)
 * @param localHandsMode when true, the worker is editing the caller's local working tree through
 *     proxied tools; prompt discipline should avoid unsolicited scaffold/test files.
 */
@Builder
public record ExecutionRequest(
    Plan plan,
    String provider,
    String model,
    ExecutionContext runContext,
    Integer maxIterationsPerTask,
    Boolean enableSmokeTests,
    Boolean localHandsMode,
    String designBlock,
    /**
     * Project ID for RAG-backed reference-doc injection (v2.5.2+). Null for runs that aren't
     * project-scoped (e.g. legacy/test harnesses). When set, each worker's system prompt gets a
     * small {@code REFERENCE DOCS} block retrieved from {@code RetrievalService} keyed on the task
     * description.
     */
    String projectId,

    /**
     * Workspace summary built by the orchestrator (RunOrchestrator.buildMultiRepoSummary or the
     * codemap fallback). When the run targets a multi-repo project the summary contains the
     * "MULTI-REPO PROJECT WORKSPACE" header + the {@code repos/<name>/} subdir list. The worker's
     * system prompt receives this block verbatim so generated files land inside the right subdir;
     * without it the worker treats the workspace as an empty scratch dir, writes to root, and the
     * per-repo push step finds no diff to ship. See {@code SequentialExecutor.composeSystemPrompt}
     * + the regression test in {@code MultiRepoWorkerPromptTest}. Null is allowed (single-repo /
     * scratch-workspace runs).
     */
    String workspaceSummary) {

  public ExecutionRequest {
    if (plan == null) throw new IllegalArgumentException("plan required");
    if (plan.tasks().isEmpty())
      throw new IllegalArgumentException("plan must contain at least one task");
    if (provider == null || provider.isBlank())
      throw new IllegalArgumentException("provider required");
    if (model == null || model.isBlank()) throw new IllegalArgumentException("model required");
    if (runContext == null) throw new IllegalArgumentException("runContext required");
    if (enableSmokeTests == null) enableSmokeTests = true;
    if (localHandsMode == null) localHandsMode = false;
  }
}

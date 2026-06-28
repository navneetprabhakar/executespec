package com.navneet.executespec.harness;

import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.execution.model.RunContinuationDecision;
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
 * @param requireConfirmationBeforeExpensiveContinuation per-run tightening override that blocks
 *     model/provider continuation spend until a client confirms it. Null/false leaves the server
 *     YAML default in control.
 * @param requireBenchmarkPriorForAutomaticContinuation optional per-run override for whether
 *     automatic model/provider continuation requires a compatible benchmark quality prior. Null
 *     leaves the server YAML default in control.
 * @param userConfirmedExpensiveContinuation true only for a retry that is being executed after a
 *     user approved a blocked continuation proposal. This lets the retry spend on the approved
 *     provider/model without weakening the run's default confirmation or benchmark-prior policy.
 * @param userConfirmedContinuationAction approved continuation action scope. Confirmation applies
 *     only when the later policy proposal matches this action, provider, and model.
 * @param userConfirmedContinuationProvider approved continuation provider scope.
 * @param userConfirmedContinuationModel approved continuation model scope.
 * @param initialProviderSwitchesUsed initial run-level provider-switch count to seed before
 *     continuation advice. Used by approved blocked continuation retries so the approved action
 *     counts against the same ceiling as automatic reruns.
 * @param initialModelStepUpsUsed initial target-task model-step-up count to seed before continuation
 *     advice.
 * @param initialModelStepDownsUsed initial target-task model-step-down count to seed before
 *     continuation advice.
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
    Boolean requireConfirmationBeforeExpensiveContinuation,
    Boolean requireBenchmarkPriorForAutomaticContinuation,
    Boolean userConfirmedExpensiveContinuation,
    RunContinuationDecision userConfirmedContinuationAction,
    String userConfirmedContinuationProvider,
    String userConfirmedContinuationModel,
    Integer initialProviderSwitchesUsed,
    Integer initialModelStepUpsUsed,
    Integer initialModelStepDownsUsed,
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
    if (requireConfirmationBeforeExpensiveContinuation == null)
      requireConfirmationBeforeExpensiveContinuation = false;
    if (userConfirmedExpensiveContinuation == null) userConfirmedExpensiveContinuation = false;
    if (initialProviderSwitchesUsed == null) initialProviderSwitchesUsed = 0;
    if (initialModelStepUpsUsed == null) initialModelStepUpsUsed = 0;
    if (initialModelStepDownsUsed == null) initialModelStepDownsUsed = 0;
    initialProviderSwitchesUsed = Math.max(0, initialProviderSwitchesUsed);
    initialModelStepUpsUsed = Math.max(0, initialModelStepUpsUsed);
    initialModelStepDownsUsed = Math.max(0, initialModelStepDownsUsed);
  }
}

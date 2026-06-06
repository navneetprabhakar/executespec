package com.navneet.executespec.engine;

import com.navneet.executespec.tool.ExecutionContext;

import lombok.Builder;

/**
 * Input to {@link WorkerAgent#run(WorkerTaskRequest)}.
 *
 * <p>The worker is provider-agnostic: it threads the supplied prompts through {@link LlmRouter}
 * which dispatches based on {@link #provider()}.
 *
 * @param provider {@code "anthropic" | "openai" | "gemini"}
 * @param model explicit model to use (caller's responsibility — tier resolution happens upstream of
 *     the worker)
 * @param systemPrompt system message — typically the worker prompt template with task constraints
 *     injected
 * @param userPrompt user message — typically the task description
 * @param context per-task execution context (workspace + ids)
 * @param maxIterations max LLM calls; 0/null defaults to {@link WorkerAgent#DEFAULT_MAX_ITER}
 * @param temperature sampling temperature; null defaults to 0.0
 * @param maxTokensPerCall max output tokens per LLM call; null defaults per-provider
 * @param stagnationWarnAfter optional override for the stagnation-warn threshold (consecutive
 *     non-write iters before injecting a one-shot PROGRESS CHECK). When null, falls through to
 *     {@link WorkerAgent#STAGNATION_WARN_AFTER_N}. Use to give complex (HIGH-complexity)
 *     reverse-engineering tasks more headroom for legitimate read+plan iterations before the
 *     warning fires.
 * @param stagnationForceAfter optional override for the stagnation-force-terminate threshold. When
 *     null, falls through to {@link WorkerAgent#STAGNATION_FORCE_AFTER_N}. Should be ≥
 *     stagnationWarnAfter + 4 so the worker has at least 4 iterations after the warn to recover.
 * @param requireTestVerification V182 layer-3 gate. When true, {@link
 *     com.navneet.executespec.tool.CompleteTaskTool} calls are refused unless the agent has made at
 *     least one successful {@code runTests} call within {@link
 *     WorkerAgent#TEST_VERIFICATION_RECENCY_ITERS} iterations. Set by {@link PatchAgent} so bug-fix
 *     runs can't claim completion without observing tests run; build-mode workers leave this
 *     null/false (build-mode has its own smoke gate downstream of the loop).
 * @param requireBuildVerification 2026-05-08 reviewer-engagement gate. When true, {@link
 *     com.navneet.executespec.tool.CompleteTaskTool} calls are refused unless the agent has made at
 *     least one successful {@code buildProject} call (exit=0) within {@link
 *     WorkerAgent#BUILD_VERIFICATION_RECENCY_ITERS} iterations. Set by {@link
 *     com.navneet.executespec.reviewer.ReviewerAgent} when entering a review pass on a workspace
 *     whose build is currently failing — without this the reviewer often calls completeTask after a
 *     few exploratory edits without re-running the build, leaving the run in
 *     PARTIAL/FINAL_BUILD_FAILED. Mirrors the {@code requireTestVerification} pattern.
 * @param wallClockSeconds 2026-05-10 — per-task wall-clock budget in seconds. When the worker has
 *     been running for longer than this, the loop terminates with {@link
 *     WorkerResult.Outcome#WALL_CLOCK_EXCEEDED} on the next iteration boundary (LLM calls are not
 *     interrupted mid-flight). Acts as a sibling of {@link #maxIterations()} — whichever fires
 *     first wins. Protects against (a) verbose-model bias where a model uses too many "thin"
 *     iterations per write and (b) genuine long-tail work where the iter cap kills a worker that's
 *     making real progress (see dev run a59f9798 T12: 100 iters, 92 files written, hit cap mid-test
 *     suite). Null/0 leaves wall-clock unbounded — only the iter cap + stagnation watchdog + cost
 *     cap apply.
 * @param forceToolChoiceOnSalvage RL-2026-05-059 — when true, the Lever-B salvage call (fired on
 *     iter-limit / budget-exceeded / progress-force with zero writes) restricts the tool set to
 *     {@code writeFile}/{@code editFile}/{@code completeTask} AND sets {@code
 *     tool_choice="required"} so the model MUST emit one of them instead of returning
 *     reasoning-only text that silently fails the salvage. Deep-dive on the 2026-05-30
 *     native-DeepSeek mixed10 eval found 4/10 runs produced ZERO edits across 18 iters and the
 *     salvage never once rescued them (the model returned a tool-less response). Honoured only by
 *     the OpenAI-wire clients (OpenAI / DeepSeek / OpenRouter); Anthropic + Gemini ignore the
 *     {@code tool_choice} today, but still benefit from the restricted tool set. Set by {@link
 *     com.navneet.executespec.harness.SequentialExecutor} from the {@code
 *     worker.salvage-force-tool-choice.enabled} flag (V224, default OFF). Null/false ⇒ today's
 *     behaviour (full tool set, {@code tool_choice="auto"}).
 * @param terminalArtifactDeadlineAfter optional absolute iteration for Terminal-Bench style tasks.
 *     When set, the worker injects a stronger deliverable checkpoint at that iteration even if
 *     shell commands are still making progress. This preserves shell-as-progress while preventing
 *     long terminal loops from spending the enlarged iteration budget without producing the
 *     requested {@code /app} artifact.
 * @param terminalTaskMode true for Terminal-Bench style tasks where shell commands are the normal
 *     way to mutate the sandbox and produce deliverables under {@code /app}. In this mode
 *     {@code shell} counts as progress for the stagnation guard, and progress nudges use
 *     terminal-output wording instead of product code-edit wording. Null/false preserves the
 *     product worker behaviour.
 */
@Builder
public record WorkerTaskRequest(
    String provider,
    String model,
    String systemPrompt,
    String userPrompt,
    ExecutionContext context,
    Integer maxIterations,
    Double temperature,
    Integer maxTokensPerCall,
    Integer stagnationWarnAfter,
    Integer stagnationForceAfter,
    Boolean requireTestVerification,
    Boolean requireBuildVerification,
    Integer wallClockSeconds,
    Boolean forceToolChoiceOnSalvage,
    Integer terminalArtifactDeadlineAfter,
    Boolean terminalTaskMode) {

  public WorkerTaskRequest {
    if (provider == null || provider.isBlank())
      throw new IllegalArgumentException("provider required");
    if (model == null || model.isBlank()) throw new IllegalArgumentException("model required");
    if (userPrompt == null || userPrompt.isBlank())
      throw new IllegalArgumentException("userPrompt required");
    if (context == null) throw new IllegalArgumentException("context required");
  }
}

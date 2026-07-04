package com.navneet.executespec.engine;

import java.util.List;

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
 *     WorkerAgent#TEST_VERIFICATION_RECENCY_ITERS} iterations. Set by {@link CodeGenEngineImpl} so
 *     bug-fix runs can't claim completion without observing tests run; build-mode workers leave
 *     this null/false (build-mode has its own smoke gate downstream of the loop).
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
 *     way to mutate the sandbox and produce deliverables under {@code /app}. In this mode {@code
 *     shell} counts as progress for the stagnation guard, and progress nudges use terminal-output
 *     wording instead of product code-edit wording. Null/false preserves the product worker
 *     behaviour.
 * @param terminalVerifyGate RL-2026-06 — terminal-mode enforced-verify gate. The generic
 *     verify-before-done nudge (the {@code !gateOn} soft nudge) only fires when the worker edited
 *     but ran NOTHING since its last edit ({@code lastEditIter > lastVerifyIter}); a TRIVIAL shell
 *     smoke (e.g. {@code python -c "import run"}) bumps {@code lastVerifyIter} and silently defeats
 *     it. The dominant Terminal-Bench miss is exactly this: a premature {@code completeTask} after
 *     a shallow check, failing only the task's stated edge/example that the hidden tests assert
 *     (full-89 DeepSeek: {@code cancel-async-tasks} completed in 6 tools, failed only the
 *     SIGINT-cleanup edge the prose calls out). When true (and {@code terminalTaskMode}), the
 *     worker fires ONE verify nudge before the FIRST {@code completeTask} whenever it has written
 *     something — regardless of whether a shallow smoke ran — pointing it at the task's own
 *     concrete example/criterion. One-shot ({@code verifyNudged}) so it can never loop; the message
 *     protects already-correct solutions by telling the model to complete immediately if its output
 *     already matches. Null/false ⇒ today's behaviour (generic nudge only). Operator-set by {@link
 *     com.navneet.executespec.terminalbench.TerminalBenchRunService} ({@code ES_TB_VERIFY_GATE},
 *     default on) so the lever can be A/B'd on the base-20 slice without a rebuild.
 * @param requiredDeliverablePaths RL-2026-06 — terminal-mode deterministic deliverable gate
 *     (harness stage #2). The exact absolute path(s) the task REQUIRES to exist when complete,
 *     extracted from the instruction once at run start ({@code
 *     TerminalBenchRunService.extractDeliverablePaths}). At {@code completeTask} (terminal + remote
 *     mode) the worker dispatches ONE deterministic {@code shell} existence check in the container;
 *     any missing path refuses completion with the specific path(s) named, bounded to {@link
 *     WorkerAgent#DELIVERABLE_GATE_MAX_REFUSALS} refusals then YIELDS (never permanently blocks —
 *     fail-open, so an imperfect extraction can only cost a couple of iterations, never a false
 *     FAIL). Targets the {@code test_*_exists} miss class where the worker put the deliverable at
 *     the wrong path or never produced it. Null/empty ⇒ inert.
 * @param criticPass RL-2026-06 — critic/reviewer pass (harness stage #1), COMMON to every worker
 *     consumer (BUILD / PATCH / TB), not a Terminal-Bench fork. When true, the FIRST {@code
 *     completeTask} triggers ONE fresh-context acceptance review: a no-tool {@link LlmRouter} call
 *     that sees ONLY the task + the candidate's deliverable and lists every requirement it fails —
 *     independent of the worker's own (anchored) reasoning. The deliverable is read workspace-
 *     agnostically: a {@code git diff} of the local workspace for product runs, or the {@code /app}
 *     files in the container for a remote TB run. Concrete gaps are injected back and the worker
 *     fixes them before completing; a {@code PASS} verdict or any critic error permits completion
 *     (fail-open). One-shot ({@code criticNudged}) so it can't loop. Catches the dominant
 *     premature-completion class (structural/smoke pass, the one real requirement fails). When on
 *     it subsumes + suppresses the lighter {@code terminalVerifyGate} nudge. Set by {@code
 *     TerminalBenchRunService} (TB) and {@code CodeGenEngineImpl} (PATCH, behind {@code
 *     worker.critic-pass.enabled}, default OFF). Null/false ⇒ no critic pass (byte-identical).
 * @param verifyBeforeComplete VBC verify-before-complete (RL-2026-06-…, flag {@code
 *     worker.verify-before-complete.enabled}, V235, default OFF). When true, the worker SERVER-RUNS
 *     the project's own test suite at the FIRST {@code completeTask} — distinct from the {@code
 *     requireTestVerification}/{@code requireBuildVerification} soft gates, which only check
 *     whether the MODEL recently ran tests/build. A worker that calls completeTask without ever
 *     verifying is therefore still caught. Tests run via {@link
 *     com.navneet.executespec.smoketest.WorkspaceTestRunner} DIRECTLY (not the flag-gated {@code
 *     runWorkspaceTests} tool), so VBC is independent of {@code worker.build-tdd.enabled} (V217)
 *     and carries no model-visible schema change (sidesteps the RL-2026-05-061 schema-stall class).
 *     Real test failures refuse completion (bounded to {@link WorkerAgent#VBC_GATE_MAX_REFUSALS},
 *     then YIELD); no resolvable runner / a remote run / any error ⇒ fail-open (never false-FAILs a
 *     complete run). Set by {@link com.navneet.executespec.harness.SequentialExecutor} (BUILD) and
 *     {@link CodeGenEngineImpl} (PATCH) from the flag. Null/false ⇒ inert (byte-identical).
 * @param progressV2 harness-resolution Phase 1 (flag {@code worker.progress-v2.enabled}, V255,
 *     default OFF). Replaces the write-only progress definition with evidence-based progress:
 *     (a) any tool call with a NOVEL (name, normalized-args) signature resets the stagnation
 *     counter — investigation counts, repetition does not; (b) stagnation force adds a
 *     repetition trigger (the same call signature failing repeatedly) so true loops still
 *     terminate; (c) the PATCH pre-edit nudge moves from 6 non-write iters to a total-iteration
 *     exploration ceiling; (d) a text-only stop is retried with {@code tool_choice=required}
 *     (bounded) instead of terminating the run; (e) the corrective nudge drops the
 *     "an imperfect edit beats zero" directive (benchmark-diff logic, wrong for customer PRs).
 *     Null/false ⇒ the RL-2026-05-005 write-only engine runs byte-identically.
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
    Boolean terminalTaskMode,
    Boolean terminalVerifyGate,
    List<String> requiredDeliverablePaths,
    Boolean criticPass,
    Boolean verifyBeforeComplete,
    Boolean progressV2) {

  public WorkerTaskRequest {
    if (provider == null || provider.isBlank())
      throw new IllegalArgumentException("provider required");
    if (model == null || model.isBlank()) throw new IllegalArgumentException("model required");
    if (userPrompt == null || userPrompt.isBlank())
      throw new IllegalArgumentException("userPrompt required");
    if (context == null) throw new IllegalArgumentException("context required");
  }
}

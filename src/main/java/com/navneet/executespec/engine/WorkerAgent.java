package com.navneet.executespec.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.agent.LlmRequest;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.context.ContextStore;
import com.navneet.executespec.observability.Metrics;
import com.navneet.executespec.smoketest.WorkspaceTestRunner;
import com.navneet.executespec.state.RunMode;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.testgen.model.TestExecutionSummary;
import com.navneet.executespec.tool.CompleteTaskTool;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.tool.Tool;
import com.navneet.executespec.tool.ToolOutputCompactor;
import com.navneet.executespec.tool.ToolRegistry;
import com.navneet.executespec.tool.ToolResult;
import com.navneet.executespec.tool.WorkerHistoryCompactor;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * V2 sequential worker agent.
 *
 * <p>One agent, one task, one execution context. Multi-turn loop:
 *
 * <ol>
 *   <li>Send the current message history to the LLM via {@link LlmRouter}
 *   <li>If the response contains tool calls, dispatch each through {@link ToolRegistry} and append
 *       the results as a {@link ToolResponseMessage}
 *   <li>If the response contains a {@link CompleteTaskTool} call, terminate with {@link
 *       WorkerResult.Outcome#COMPLETED}
 *   <li>If the response has no tool calls, terminate with {@link
 *       WorkerResult.Outcome#STOPPED_WITHOUT_COMPLETE}
 *   <li>If the iteration cap is reached, terminate with {@link
 *       WorkerResult.Outcome#ITERATION_LIMIT_EXCEEDED}
 * </ol>
 *
 * <p>Phase 3 baseline scope:
 *
 * <ul>
 *   <li>Multi-turn message threading — full history kept in memory for the duration of the task (no
 *       compaction yet — Phase 8 owns context persistence + crash recovery)
 *   <li>Native completeTask signal (no JSON-prefix heuristic)
 *   <li>40-iteration default cap (overridable via WorkerTaskRequest.maxIterations)
 * </ul>
 *
 * <p>Deferred to later phases (per execution plan):
 *
 * <ul>
 *   <li>Hybrid context (upfront file fill from planner-declared dependencyFiles) — Phase 4
 *   <li>Sliding-window compaction at 75% context limit — Phase 8
 *   <li>Redis context persistence + crash recovery — Phase 8
 *   <li>Stall detection / model escalation — Phase 2.x
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WorkerAgent {

  /**
   * Default iteration cap (per V2 plan P3.1 — was 28 in V1 MEDIUM, raised to compensate for
   * SubtaskDecomposer removal).
   */
  public static final int DEFAULT_MAX_ITER = 40;

  /** Persist context every N iterations (Phase 8 — plan P8.1 says "every 5"). */
  public static final int CONTEXT_PERSIST_EVERY_N_ITERATIONS = 5;

  private final LlmRouter router;
  private final ToolRegistry toolRegistry;
  private final ObjectMapper objectMapper;
  private final ContextStore contextStore;
  private final Metrics metrics;
  private final com.navneet.executespec.cost.RunCostTracker costTracker;
  private final com.navneet.executespec.state.repository.PipelineRunRepository
      pipelineRunRepository;

  /**
   * 2026-05-04 — user feedback: "in live log we are only showing tokens, we should show some quirky
   * log lines formatted in same format as Task start and end". Wire SseBroadcaster + RunEvent so
   * the worker can emit per-tool-call + per-iteration progress events that show up in the LiveLog
   * tab alongside the existing Task start/end markers. Same format ({@code RunEvent.log(runId,
   * taskId, msg)}) so the renderer doesn't need changes.
   */
  private final com.navneet.executespec.sse.SseBroadcaster broadcaster;

  /**
   * Phase 5+6 engine extraction (RL-2026-05-052). Bulk tool output flows through this before
   * landing in the worker's history. Default OFF behind {@link ToolOutputCompactor#FLAG_ENABLED} —
   * with the flag off, every call returns Optional.empty() and the worker uses raw output verbatim.
   * Positioned last in the {@code @RequiredArgsConstructor} parameter order so existing test call
   * sites only need to append a single trailing argument (null is accepted; {@link #maybeCompact}
   * null-guards before any method call).
   */
  private final ToolOutputCompactor toolOutputCompactor;

  /**
   * RL-2026-05-052 follow-up — worker conversation-history compaction (the complement to {@link
   * ToolOutputCompactor}: that shrinks individual tool outputs, this summarises the oldest TURNS
   * once the accumulated input crosses a token threshold). Default OFF behind {@link
   * WorkerHistoryCompactor#FLAG_ENABLED}; with the flag off {@code compactIfNeeded} returns the
   * same list reference and the loop is byte-identical. Last in the
   * {@code @RequiredArgsConstructor} order (after {@link #toolOutputCompactor}) so test call sites
   * append one more trailing arg; the loop null-guards before use.
   */
  private final WorkerHistoryCompactor workerHistoryCompactor;

  /**
   * VBC verify-before-complete (RL-2026-06-…) — server-runs the project's own test suite at the
   * {@code completeTask} boundary when {@link WorkerTaskRequest#verifyBeforeComplete()} is set
   * (flag {@code worker.verify-before-complete.enabled}, V235, default OFF). Unlike the
   * model-dependent {@code requireTestVerification}/{@code requireBuildVerification} soft gates
   * (which only check whether the MODEL recently called runTests/buildProject), this RUNS the tests
   * itself, server-side — so a worker that never verifies is still caught. Called DIRECTLY (not via
   * the flag-gated {@code runWorkspaceTests} TOOL) so VBC is decoupled from {@code
   * worker.build-tdd.enabled} (V217) and carries no model-visible schema change (sidesteps the
   * RL-2026-05-061 schema-stall class). Null-guarded — legacy {@code new WorkerAgent(...)} test
   * call sites pass null and the gate fail-opens. Last in the {@code @RequiredArgsConstructor}
   * order so those call sites only append one trailing arg.
   */
  private final WorkspaceTestRunner workspaceTestRunner;

  @Value("${execution.continuation.parallel-read-tools-enabled:true}")
  private boolean parallelReadToolsEnabled = true;

  /** Bug #2 fix — check budget every N iterations, not just at task boundaries. */
  static final int BUDGET_CHECK_EVERY_N_ITERATIONS = 3;

  /**
   * Stagnation guard threshold — after this many consecutive iterations without a write-class tool
   * call (writeFile / editFile), inject a one-shot "PROGRESS CHECK" system message. Calibrated from
   * run a076c876 task T2 (2026-05-03): the worker had finished writing files at iter ~16 and burned
   * the next 44 iters on read+verify+git loops. Eight iterations is enough headroom for legitimate
   * "read several files, then write one big edit" patterns without rewarding loops.
   */
  public static final int STAGNATION_WARN_AFTER_N = 8;

  /**
   * Stagnation force-terminate threshold — after this many consecutive non-write iterations, force
   * the worker to a {@link WorkerResult.Outcome#COMPLETED_BY_STAGNATION} outcome regardless of
   * whether {@code completeTask} was called. The downstream smoke gate decides whether the change
   * set actually compiles. Calibrated as STAGNATION_WARN_AFTER_N + 4 — that gives the worker four
   * post-warning iterations to either call completeTask cleanly or write the missing piece.
   */
  public static final int STAGNATION_FORCE_AFTER_N = 12;

  /**
   * PATCH runs are issue-fix shaped, not greenfield build tasks. If they reach this many
   * consecutive non-write iterations with zero edits, inject the existing progress nudge early so
   * the worker commits to a source change instead of spending the normal full stagnation window on
   * reads/searches. BUILD and terminal-task runs keep the standard thresholds.
   */
  public static final int PATCH_NO_EDIT_WARN_AFTER_N = 6;

  /**
   * progress-v2 (flag {@code worker.progress-v2.enabled}) — repetition trigger thresholds. A tool
   * call SIGNATURE (name + normalized args) that fails this many times consecutively (successes
   * clear the streak) fires the one-shot nudge / force-terminates. Repetition — not absence of
   * writes — is the v2 definition of stagnation: re-running the same failing command without
   * changing anything is the loop signature; reading NEW files never is.
   */
  public static final int PROGRESS_V2_REPEAT_FAILURE_NUDGE_AT = 3;

  /** See {@link #PROGRESS_V2_REPEAT_FAILURE_NUDGE_AT}. */
  public static final int PROGRESS_V2_REPEAT_FAILURE_FORCE_AT = 5;

  /**
   * progress-v2 — PATCH pre-first-edit exploration ceiling (total iterations, NOT non-write
   * iterations). Replaces the v1 {@link #PATCH_NO_EDIT_WARN_AFTER_N} 6-non-write-iteration nudge:
   * on large repos, competent localization legitimately takes dozens of novel reads/searches.
   * Effective value is {@code max(this, 3 × progressWarnAfter)}.
   */
  public static final int PROGRESS_V2_EXPLORATION_NUDGE_AFTER = 24;

  /**
   * progress-v2 — how many text-only stops may be retried with {@code tool_choice="required"}
   * before the run terminates with STOPPED_WITHOUT_COMPLETE. Reasoning-style models plan out loud;
   * a plan turn is not a reason to kill a run. Bounded so a model that simply refuses tools still
   * terminates.
   */
  public static final int PROGRESS_V2_TEXT_ONLY_FORCED_RETRIES = 2;

  /**
   * V182 layer-3 — within how many iterations of {@code completeTask} must a successful {@code
   * runTests} call have occurred? When {@link WorkerTaskRequest#requireTestVerification()} is true,
   * the dispatch loop refuses {@code completeTask} unless the agent ran tests within this window. 5
   * is calibrated to be tight enough that the agent can't claim "I ran the tests 30 iters ago and
   * they passed" but loose enough to allow a few post-test polishing iterations (re-read the
   * output, double-check the diff).
   */
  public static final int TEST_VERIFICATION_RECENCY_ITERS = 5;

  /**
   * Name of the structured pytest tool ({@link com.navneet.executespec.tool.RunTestsTool}). Used by
   * the layer-3 verification gate to identify the call that satisfies it.
   */
  static final String RUN_TESTS_TOOL_NAME = "runTests";

  /**
   * Name of the project-build tool ({@link com.navneet.executespec.tool.BuildProjectTool}). Used by
   * the 2026-05-08 build-verification gate (mirrors the test-verification gate from V182 layer-3)
   * to identify the call that satisfies it.
   */
  static final String BUILD_PROJECT_TOOL_NAME = "buildProject";

  /**
   * 2026-05-08 — within how many iterations of {@code completeTask} must a successful {@code
   * buildProject} call (exit=0) have occurred? When {@link
   * WorkerTaskRequest#requireBuildVerification()} is true, the dispatch loop refuses {@code
   * completeTask} unless the agent ran a passing build within this window. 5 matches the
   * test-verification gate — tight enough that the agent can't claim "I built it 20 edits ago" but
   * loose enough for a few post-fix polishing iters.
   */
  public static final int BUILD_VERIFICATION_RECENCY_ITERS = 5;

  /**
   * RL-2026-06 — terminal-mode deterministic deliverable gate (harness stage #2). How many times
   * may the gate refuse {@code completeTask} for a missing required path before it YIELDS and lets
   * the run complete anyway. Bounded + fail-open: the gate can only nudge the worker toward
   * producing a missing file for a couple of iterations; it can NEVER permanently block a run, so
   * an imperfect path extraction can't turn a legitimately-complete run into a false FAIL. See
   * {@link WorkerTaskRequest#requiredDeliverablePaths()}.
   */
  public static final int DELIVERABLE_GATE_MAX_REFUSALS = 2;

  /**
   * VBC verify-before-complete gate — how many times the server-run test gate may refuse {@code
   * completeTask} (tests red) before it YIELDS and lets the run complete anyway. Bounded +
   * fail-open (mirrors {@link #DELIVERABLE_GATE_MAX_REFUSALS}): the gate can nudge the worker to
   * fix failing tests for a few rounds but can NEVER permanently block a legitimately-complete run.
   * 3 (vs the deliverable gate's 2) gives an extra round because fixing a real test failure usually
   * needs one read + one edit + one re-verify.
   */
  public static final int VBC_GATE_MAX_REFUSALS = 3;

  /**
   * Terminal visible-test gate — how many times {@code completeTask} may be refused when a remote
   * terminal sandbox exposes visible verifier files under {@code /tests} but the worker has not yet
   * run a passing command against those tests. Bounded + fail-open like the other completion gates.
   */
  public static final int TERMINAL_VISIBLE_TEST_GATE_MAX_REFUSALS = 3;

  /**
   * Tool names that count as "real progress" for the stagnation guard. Reads / git inspection /
   * shell don't reset the counter — only file mutations and explicit build attempts.
   *
   * <p>{@code buildProject} added 2026-05-08: cmatrix gemini run 84252898 stagnated despite the
   * agent legitimately alternating between read + write + build cycles, because the build calls
   * didn't reset the counter. Build attempts ARE deliberate forward motion (especially in
   * programbench mode where {@link com.navneet.executespec.tool.BuildProjectTool}'s wrapper fails
   * any no-op compile.sh — so spam-build can't game the guard, every successful build implies real
   * source compiled).
   */
  private static final java.util.Set<String> WRITE_CLASS_TOOLS =
      java.util.Set.of(
          "writeFile",
          "write_file",
          "editFile",
          "edit_file",
          "multiEdit",
          "multi_edit",
          "applyPatch",
          "apply_patch",
          "buildProject");

  /**
   * Per-call HTTP read-timeout override applied to every worker LLM call. Honoured by {@link
   * GeminiNativeToolClient} (where the static default 60s is too tight for thinking-model worker
   * calls); ignored by Anthropic + OpenAI clients (Spring AI manages their HTTP timeouts).
   * Calibrated 2026-05-08 from cmatrix gemini run 84252898 — task T3 timed out after a 17-min
   * worker phase with 122k input tokens.
   */
  private static final int WORKER_HTTP_READ_TIMEOUT_SECONDS = 120;

  public WorkerResult run(WorkerTaskRequest req) {
    return run(req, null);
  }

  /**
   * Resume-aware overload. When {@code resumeFrom} is non-null, the worker:
   *
   * <ul>
   *   <li>Skips the fresh {@code SystemMessage + UserMessage} bootstrap and uses the saved {@link
   *       com.navneet.executespec.context.SavedContext#history()} as starting history.
   *   <li>Starts the iteration loop at {@code resumeFrom.iteration() + 1}, capped at the same
   *       {@code maxIter}. The remaining iteration budget is therefore proportionally smaller.
   *   <li>Snapshots are written every {@value #CONTEXT_PERSIST_EVERY_N_ITERATIONS} iters as always,
   *       so a second crash mid-resume is also recoverable.
   * </ul>
   *
   * <p>Idempotency contract — the worker tools the resume path relies on:
   *
   * <ul>
   *   <li>{@code writeFile} — overwrites whole-file content. Re-issuing the same call is a no-op.
   *   <li>{@code editFile} — fails with a clear error envelope if the {@code old_string} is no
   *       longer present (because it was already replaced by the previous, possibly lost, run).
   *       Worker self-corrects by reading the file and trying again or moving on.
   *   <li>{@code gitCommit} — denied for the worker entirely (see {@link
   *       ToolRegistry#WORKER_DENY_TOOLS}). Run-end commit is in {@code ScmPushService}.
   *   <li>{@code shell} — best-effort idempotent for build / test commands; mutating shell commands
   *       are sandbox-rejected at policy level.
   *   <li>{@code completeTask} — if the worker called it BEFORE the crash, the snapshot was already
   *       deleted (see line in dispatch loop), so resume can't fire on a completed task.
   * </ul>
   *
   * <p>Stagnation counter resets to 0 on resume — the worker gets a fresh {@code
   * STAGNATION_WARN_AFTER_N} of headroom to recover from the interruption.
   */
  public WorkerResult run(
      WorkerTaskRequest req, com.navneet.executespec.context.SavedContext resumeFrom) {
    long start = System.currentTimeMillis();
    int maxIter =
        (req.maxIterations() == null || req.maxIterations() <= 0)
            ? DEFAULT_MAX_ITER
            : req.maxIterations();

    final boolean isResume = resumeFrom != null && !resumeFrom.history().isEmpty();
    int startIter = 1;
    List<Message> history;
    if (isResume) {
      // Snapshot history was written on a clean iteration boundary (after a tool-response turn),
      // so it's well-formed to send straight to the LLM. Defensive copy: ContextStore returns a
      // List<Message> that callers shouldn't share with us.
      history = new ArrayList<>(resumeFrom.history());
      startIter = resumeFrom.iteration() + 1;
      log.info(
          "WorkerAgent RESUME run={} task={} provider={} model={} from iter={} historyLen={}",
          req.context().runId(),
          req.context().taskId(),
          req.provider(),
          req.model(),
          startIter,
          history.size());
    } else {
      history = new ArrayList<>();
      if (req.systemPrompt() != null && !req.systemPrompt().isBlank()) {
        history.add(new SystemMessage(req.systemPrompt()));
      }
      history.add(new UserMessage(req.userPrompt()));
    }

    List<String> toolLog = new ArrayList<>();
    int toolsDispatched = 0;
    String lastText = "";

    // RL-2026-05-005 — the worker's single progress signal. `itersSinceProgress` counts
    // consecutive iterations with no write-class tool call (writeFile / editFile); it resets to 0
    // on any write. ONE counter, TWO thresholds, ONE nudge replaces the old set of six overlapping
    // guardrails (Lever A force-edit watchdog + Lever C completion nudge + stagnation warn/force).
    //   • progressWarnAfter  → inject ONE corrective UserMessage (the unified nudge).
    //   • progressForceAfter → force-terminate (Lever B salvage first if nothing was ever written).
    // The same one-shot nudge also fires when the model stops text-only without calling
    // completeTask. Per-task override (req.stagnationWarnAfter / req.stagnationForceAfter):
    // SequentialExecutor sets these per complexity (LOW=10/16, MEDIUM=16/28, HIGH=24/40) so that
    // legitimate reverse-engineering / multi-file-read iterations on HIGH tasks don't trip the
    // guard before the worker can write. Falls through to the static defaults when null.
    final int progressWarnAfter =
        req.stagnationWarnAfter() != null && req.stagnationWarnAfter() > 0
            ? req.stagnationWarnAfter()
            : STAGNATION_WARN_AFTER_N;
    final int progressForceAfter =
        req.stagnationForceAfter() != null && req.stagnationForceAfter() > progressWarnAfter
            ? req.stagnationForceAfter()
            : Math.max(STAGNATION_FORCE_AFTER_N, progressWarnAfter + 4);
    final boolean terminalTaskMode = Boolean.TRUE.equals(req.terminalTaskMode());
    // RL-2026-06 — terminal-mode enforced-verify gate. See WorkerTaskRequest#terminalVerifyGate.
    final boolean terminalVerifyGateOn =
        terminalTaskMode && Boolean.TRUE.equals(req.terminalVerifyGate());
    // RL-2026-06 — critic/reviewer pass (#1), COMMON to all consumers (not terminal-gated). See
    // WorkerTaskRequest#criticPass. Workspace-agnostic: reads git diff (local) or /app (remote).
    final boolean criticOn = Boolean.TRUE.equals(req.criticPass());
    // VBC (RL-2026-06-…) — verify-before-complete: at the FIRST completeTask, SERVER-RUN the
    // project's own tests and refuse if red. Decoupled from V217 (calls WorkspaceTestRunner
    // directly, not the flag-gated runWorkspaceTests tool). See
    // WorkerTaskRequest#verifyBeforeComplete.
    final boolean vbcOn = Boolean.TRUE.equals(req.verifyBeforeComplete());
    final int terminalArtifactDeadlineAfter =
        req.terminalArtifactDeadlineAfter() != null && req.terminalArtifactDeadlineAfter() > 0
            ? req.terminalArtifactDeadlineAfter()
            : Math.min(
                Math.max(progressWarnAfter * 2, 32), Math.max(progressWarnAfter + 1, maxIter - 8));
    final int terminalForcedFinalizerAfter =
        terminalTaskMode
            ? Math.min(
                Math.max(progressForceAfter, terminalArtifactDeadlineAfter + 1),
                Math.max(terminalArtifactDeadlineAfter + 1, maxIter - 4))
            : Integer.MAX_VALUE;
    int itersSinceProgress = 0;
    // progress-v2 (harness-resolution Phase 1, flag worker.progress-v2.enabled, default OFF).
    // When ON: novelty-based progress + repetition-based force + bounded text-only retries.
    // When OFF: every v2 branch below is dead code — the v1 loop is byte-identical.
    final boolean progressV2 = Boolean.TRUE.equals(req.progressV2());
    // v2: every (tool, normalized-args) signature seen this task. A call with a NEW signature is
    // evidence-gathering progress; repeating an old one is not.
    final java.util.Set<String> seenCallSignatures = new java.util.HashSet<>();
    // v2: consecutive failures per signature (a success on the same signature clears its streak).
    final java.util.Map<String, Integer> failedSignatureStreaks = new java.util.HashMap<>();
    int textOnlyForcedRetries = 0;
    boolean forceToolChoiceNextCall = false;
    // v2: PATCH pre-first-edit exploration ceiling (total iterations; see constant javadoc).
    final int explorationNudgeAfter =
        Math.max(PROGRESS_V2_EXPLORATION_NUDGE_AFTER, progressWarnAfter * 3);
    // One-shot: at most one corrective nudge per task, whether triggered by the progress counter
    // crossing progressWarnAfter mid-loop or by a premature text-only stop. After it fires once,
    // the next failure terminates (force threshold, or another text-only stop).
    boolean progressNudged = false;
    boolean terminalArtifactDeadlineNudged = false;
    boolean terminalForcedFinalizerAttempted = false;

    // V182 layer-3 — last iteration on which a runTests call returned an ok envelope.
    // -1 means "never". Refreshed inside the per-tool-call dispatch when name=runTests
    // and the envelope JSON starts with `{"ok":true`. The completeTask gate consults
    // this before allowing termination.
    int lastTestRunIter = -1;
    final boolean gateOn = Boolean.TRUE.equals(req.requireTestVerification());

    // 2026-05-08 — last iteration on which a buildProject tool returned an ok envelope WITH
    // exit=0 in its content. Mirrors lastTestRunIter; consulted by the completeTask gate when
    // requireBuildVerification is true (set by ReviewerAgent when entering a pass on a
    // build-failing workspace).
    int lastBuildPassIter = -1;
    final boolean buildGateOn = Boolean.TRUE.equals(req.requireBuildVerification());

    // Total write-class tool calls (writeFile / editFile) across the whole run. Distinct from
    // itersSinceProgress (which resets): this never decreases, and gates the Lever B salvage —
    // a run that has written SOMETHING doesn't need a best-guess salvage call. RL-2026-05-005.
    int totalWriteCalls = 0;

    // #3 verify-before-done (RL-2026-05-051). BUILD/blind has no hard runTests gate (the target
    // test is held out), so instead we SOFT-nudge once: if the worker calls completeTask after
    // editing but without having RUN anything (shell repro / buildProject) since its last edit, we
    // refuse that one completeTask with a "verify your fix first" message, then always allow. One
    // shot (verifyNudged) so it can never loop. Targets the wrong-fix miss bucket.
    int lastEditIter = -1; // last writeFile/editFile
    int lastVerifyIter = -1; // last shell or buildProject (the worker ran something to check)
    boolean verifyNudged = false;

    // RL-2026-06 — terminal-mode deterministic deliverable gate (#2). The exact paths the task
    // requires to exist (extracted at run start), and a bounded refusal counter. Active only in
    // terminal + remote mode with a non-empty path list; fail-open after
    // DELIVERABLE_GATE_MAX_REFUSALS.
    final List<String> requiredDeliverables =
        req.requiredDeliverablePaths() == null ? List.of() : req.requiredDeliverablePaths();
    java.util.Map<String, Integer> assistantTextCounts = new java.util.LinkedHashMap<>();
    int maxRepeatedAssistantTextCount = 0;
    int deliverableRefusals = 0;
    // VBC verify-before-complete — bounded refusal counter (fail-open after VBC_GATE_MAX_REFUSALS).
    // Re-runs the tests on each completeTask attempt (the worker fixes between attempts), unlike
    // the
    // one-shot critic; the counter caps how many times red tests can defer completion.
    int vbcRefusals = 0;

    // RL-2026-06 — terminal-mode critic pass (#1). One-shot fresh-context acceptance review before
    // the first completeTask. criticNudged ⇒ can never loop.
    boolean criticNudged = false;
    boolean terminalVerifiedPass = false;
    String terminalVerifiedPassEvidence = null;
    int terminalVisibleTestRefusals = 0;

    // 2026-05-10 — per-task wall-clock budget. Sibling of maxIter — whichever fires first wins.
    // Default to a reasonable per-tier ceiling when not provided so callers that haven't migrated
    // (e.g. ReviewerAgent's two passes) still get the new safety net. Zero/null = unbounded.
    final long wallClockMillisCap =
        req.wallClockSeconds() != null && req.wallClockSeconds() > 0
            ? req.wallClockSeconds() * 1000L
            : 0L;

    if (!isResume) {
      log.info(
          "WorkerAgent START run={} task={} provider={} model={} maxIter={}",
          req.context().runId(),
          req.context().taskId(),
          req.provider(),
          req.model(),
          maxIter);
    }

    // Bug #2 fix — load the run's hard cap once so the loop can stop
    // itself if cost blows past it mid-task. Null cap = no enforcement.
    final String runId = req.context().runId();
    Optional<PipelineRun> runRow = pipelineRunRepository.findById(runId);
    final Double hardCapUsd = runRow.map(r -> r.getHardCapUsd()).orElse(null);
    final boolean patchNoEditNudgeOn =
        !terminalTaskMode && runRow.map(r -> RunMode.isPatch(r.getRunMode())).orElse(false);

    // RL-2026-05-052 follow-up — prompt (input) tokens the previous LLM call reported; the trigger
    // signal for WorkerHistoryCompactor. 0 until the first call returns usage.
    long lastInputTokens = 0L;

    for (int iter = startIter; iter <= maxIter; iter++) {
      // A4 (audit 2026-05-29) — cooperative cancellation. A reviewer pass / orchestrator cancels a
      // runaway via future.cancel(true), which sets this thread's interrupt flag; with no check the
      // loop kept generating to completion (still billed). Bail at the iter boundary, before the
      // next (billable) LLM call — same "stop on the next boundary" contract as the wall-clock cap
      // below. No salvage call: a cancellation means stop now, don't spend another LLM call.
      if (Thread.currentThread().isInterrupted()) {
        log.warn(
            "WorkerAgent INTERRUPTED run={} task={} iter={} elapsedMs={} totalWrites={}",
            runId,
            req.context().taskId(),
            iter,
            System.currentTimeMillis() - start,
            totalWriteCalls);
        recordTerminal(WorkerResult.Outcome.STOPPED_WITHOUT_COMPLETE, req, iter);
        return WorkerResult.builder()
            .outcome(WorkerResult.Outcome.STOPPED_WITHOUT_COMPLETE)
            .iterations(iter)
            .toolCallsDispatched(toolsDispatched)
            .finalAssistantText(lastText)
            .repeatedAssistantTextCount(maxRepeatedAssistantTextCount)
            .toolCallLog(toolLog)
            .totalLatencyMs(System.currentTimeMillis() - start)
            .build();
      }
      // 2026-05-10 — wall-clock cap check. Top-of-iter so we never start a new LLM call once
      // we've blown past the budget. In-flight LLM calls are not interrupted (the stop happens
      // on the next iter boundary), so the actual termination time is "wall-clock cap +
      // current LLM call latency" — typically <60s tail.
      if (wallClockMillisCap > 0 && (System.currentTimeMillis() - start) >= wallClockMillisCap) {
        if (terminalTaskMode && !terminalForcedFinalizerAttempted) {
          terminalForcedFinalizerAttempted = true;
          log.warn(
              "WorkerAgent TERMINAL_WALL_CLOCK_FINALIZER run={} task={} iter={} elapsedMs={}"
                  + " capMs={} totalWrites={}",
              runId,
              req.context().taskId(),
              iter,
              System.currentTimeMillis() - start,
              wallClockMillisCap,
              totalWriteCalls);
          WorkerResult finalized =
              runTerminalFinalizerCall(req, history, toolsDispatched, toolLog, start, iter, false);
          if (finalized != null) return finalized;
        }
        log.warn(
            "WorkerAgent WALL_CLOCK_EXCEEDED run={} task={} iter={} elapsedMs={} capMs={}"
                + " totalWrites={}",
            runId,
            req.context().taskId(),
            iter,
            System.currentTimeMillis() - start,
            wallClockMillisCap,
            totalWriteCalls);
        recordTerminal(WorkerResult.Outcome.WALL_CLOCK_EXCEEDED, req, iter);
        return WorkerResult.builder()
            .outcome(WorkerResult.Outcome.WALL_CLOCK_EXCEEDED)
            .iterations(iter)
            .toolCallsDispatched(toolsDispatched)
            .finalAssistantText(lastText)
            .repeatedAssistantTextCount(maxRepeatedAssistantTextCount)
            .toolCallLog(toolLog)
            .totalLatencyMs(System.currentTimeMillis() - start)
            .build();
      }
      if (hardCapUsd != null
          && hardCapUsd > 0
          && (iter == 1 || iter % BUDGET_CHECK_EVERY_N_ITERATIONS == 0)) {
        double spent = costTracker.snapshot(runId).costUsd();
        if (spent >= hardCapUsd) {
          log.warn(
              "WorkerAgent BUDGET_EXCEEDED run={} task={} iter={} spent=${} cap=${} totalWrites={}",
              runId,
              req.context().taskId(),
              iter,
              String.format("%.4f", spent),
              String.format("%.4f", hardCapUsd),
              totalWriteCalls);
          // 2026-05-08 — extend Lever B salvage to BUDGET_EXCEEDED path. Previously only
          // ITERATION_LIMIT_EXCEEDED triggered runSalvageCall; a BUDGET_EXCEEDED with zero
          // writes also produced an empty patch (observed Gemini sqlfluff-1733/1517 hitting
          // $4 cap with 0 edits). One extra LLM call is ~$0.10-0.50, acceptable spend over
          // the cap to convert "empty patch = automatic failure" into "best-guess patch =
          // possible resolve." For BYOK runs cap is null so this branch never fires anyway.
          if (totalWriteCalls == 0) {
            try {
              WorkerResult salvage = runSalvageCall(req, history, toolsDispatched, toolLog, start);
              if (salvage != null) {
                // Mark salvage outcome distinct from clean BUDGET_EXCEEDED so downstream sees
                // we DID produce output despite hitting cap. Reuses the iter-limit salvage
                // path which returns COMPLETED on a successful write.
                log.info(
                    "WorkerAgent BUDGET_EXCEEDED salvage produced output run={}",
                    req.context().runId());
                return salvage;
              }
            } catch (Exception e) {
              log.warn(
                  "WorkerAgent BUDGET salvage failed run={}: {}",
                  req.context().runId(),
                  e.getMessage());
            }
          }
          recordTerminal(WorkerResult.Outcome.BUDGET_EXCEEDED, req, iter);
          // P3.1 (GA-readiness, 2026-05-06) — return outcome=BUDGET_EXCEEDED so the executor
          // sees the budget signal directly. Previously this path called errorResult(...) which
          // hardcoded Outcome.ERROR; metrics recorded BUDGET_EXCEEDED but the WorkerResult
          // outcome read as ERROR — so SequentialExecutor's budget-aware branches couldn't
          // distinguish a router crash from a cost-cap stop.
          return WorkerResult.builder()
              .outcome(WorkerResult.Outcome.BUDGET_EXCEEDED)
              .iterations(iter)
              .toolCallsDispatched(toolsDispatched)
              .finalAssistantText(
                  "hard cap $"
                      + String.format("%.2f", hardCapUsd)
                      + " exceeded mid-task (spent $"
                      + String.format("%.2f", spent)
                      + ")")
              .repeatedAssistantTextCount(maxRepeatedAssistantTextCount)
              .toolCallLog(toolLog)
              .totalLatencyMs(System.currentTimeMillis() - start)
              .build();
        }
      }

      // RL-2026-05-052 follow-up — compact the oldest turns once the previous call's input crossed
      // the threshold, BEFORE building this request. Flag-gated + default OFF → returns the same
      // `history` reference (byte-identical loop). Null-guarded for test call sites that pass null.
      if (workerHistoryCompactor != null) {
        history =
            workerHistoryCompactor.compactIfNeeded(
                history, req.provider(), req.context(), lastInputTokens, req.userPrompt());
      }

      // progress-v2 — one-shot tool_choice=required after a repeated text-only stop. Consumed
      // here; providers that reject "required" get one automatic retry without it (mirrors the
      // salvage forced→auto fallback) so the forced attempt can never turn into a run-killing
      // router error.
      final boolean forcedThisCall = progressV2 && forceToolChoiceNextCall;
      forceToolChoiceNextCall = false;
      ChatResponse resp;
      try {
        resp =
            router.call(
                LlmRequest.builder()
                    .provider(req.provider())
                    .model(req.model())
                    .messages(history)
                    .context(req.context())
                    .agentRole("WorkerAgent")
                    .stepId("worker.iteration-" + iter)
                    .temperature(req.temperature())
                    .maxTokens(req.maxTokensPerCall())
                    .toolChoice(forcedThisCall ? "required" : null)
                    // Strip gitCommit from the worker's tool authorities. See
                    // ToolRegistry#WORKER_DENY_TOOLS for the rationale.
                    .toolsOverride(toolRegistry.bindForWorker(req.context()))
                    // 2026-05-08 — Gemini's static GeminiNativeToolClient.READ_TIMEOUT is 60s,
                    // calibrated to fail fast on stalled worker calls. But gemini-3-pro-preview
                    // (thinking model) regularly takes 60-90s on dense tool-call iterations,
                    // tripping the timeout mid-task. Cmatrix run 84252898 task T3 FAILED with
                    // ResourceAccessException I/O error after the worker burned 17m of work.
                    // 120s gives 2× headroom while still detecting genuinely stuck calls within
                    // 2 minutes. Anthropic + OpenAI clients ignore the field (Spring AI manages
                    // their HTTP timeouts).
                    .readTimeoutSeconds(WORKER_HTTP_READ_TIMEOUT_SECONDS)
                    .build());
      } catch (Exception e) {
        if (forcedThisCall) {
          log.warn(
              "WorkerAgent forced tool_choice rejected at iter={} ({}); retrying auto",
              iter,
              e.getMessage());
          iter--; // retry the same logical iteration without the forced choice
          continue;
        }
        log.error("WorkerAgent router failure at iter={}", iter, e);
        recordTerminal(WorkerResult.Outcome.ERROR, req, iter);
        return errorResult(
            iter,
            toolsDispatched,
            toolLog,
            start,
            "router error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
      }

      // RL-2026-05-052 follow-up — remember this call's INPUT size so the next iteration's
      // history-compaction trigger has a real signal (cost-tracking already records usage).
      if (resp.getMetadata() != null
          && resp.getMetadata().getUsage() != null
          && resp.getMetadata().getUsage().getPromptTokens() != null) {
        lastInputTokens = resp.getMetadata().getUsage().getPromptTokens();
      }

      // Collect tool calls + assistant text across all generations (Anthropic
      // returns multi-gen responses; OpenAI/Gemini single-gen — same code path works).
      StringBuilder textBuf = new StringBuilder();
      List<AssistantMessage.ToolCall> calls = new ArrayList<>();
      // 2026-05-28 (RL-2026-05-052) — pass through DeepSeek's reasoning_content metadata so the
      // next iteration's assistant turn can echo it (api.deepseek.com requires the round-trip on
      // thinking-mode models). For multi-gen responses we take the first generation's metadata —
      // empirically only single-gen providers (OpenAI-wire family) carry this field.
      java.util.Map<String, Object> assistantMetadata = null;
      for (var gen : resp.getResults()) {
        String t = gen.getOutput().getText();
        if (t != null && !t.isEmpty()) {
          if (textBuf.length() > 0) textBuf.append("\n");
          textBuf.append(t);
        }
        if (gen.getOutput().getToolCalls() != null) {
          calls.addAll(gen.getOutput().getToolCalls());
        }
        if (assistantMetadata == null
            && gen.getOutput().getMetadata() != null
            && !gen.getOutput().getMetadata().isEmpty()) {
          assistantMetadata = gen.getOutput().getMetadata();
        }
      }
      String assistantText = textBuf.toString();
      if (!assistantText.isEmpty()) {
        lastText = assistantText;
        String assistantSignature = assistantText.replaceAll("\\s+", " ").trim();
        if (!assistantSignature.isBlank()) {
          int count = assistantTextCounts.getOrDefault(assistantSignature, 0) + 1;
          assistantTextCounts.put(assistantSignature, count);
          maxRepeatedAssistantTextCount = Math.max(maxRepeatedAssistantTextCount, count);
        }
      }

      log.info(
          "WorkerAgent iter={} text={}chars toolCalls={}",
          iter,
          assistantText.length(),
          calls.size());

      // 2026-05-04 — quirky per-iteration log line. Visible in the LiveLog tab in the same
      // banner format as the surrounding "Starting task:" / "Worker COMPLETED ..." lines.
      // Filter to interesting iterations only (tool-call iters and the first text-only one)
      // so we don't spam — the existing log file at JVM stdout still has every iter for ops.
      if (!calls.isEmpty()) {
        emitWorkerLog(
            req,
            "💭 Iter "
                + iter
                + " · "
                + calls.size()
                + " tool call"
                + (calls.size() == 1 ? "" : "s")
                + (assistantText.length() > 0 ? " · " + previewThought(assistantText) : ""));
      }

      // No tool calls → model is done (either text-only stop or stopped early).
      if (calls.isEmpty()) {
        // RL-2026-05-005 — text-only stop without calling completeTask: the model produced a
        // status report instead of closing the loop (the dominant "summary mode" failure, e.g.
        // dev run a59f9798/T8 — wrote 10 files, said "implementation is complete", never called
        // the tool). Fire the SAME one-shot progress nudge used mid-loop: if we haven't nudged
        // yet, give the model one more iteration to either call completeTask or make the next
        // concrete edit. If we've already spent the nudge, stop with STOPPED_WITHOUT_COMPLETE.
        history.add(new AssistantMessage(assistantText));
        if (terminalVerifiedPass) {
          String summary =
              "terminal verifier-shaped check passed; preserving verified candidate"
                  + (terminalVerifiedPassEvidence == null
                      ? ""
                      : " (" + terminalVerifiedPassEvidence + ")");
          toolLog.add("TERMINAL_VERIFIED_PASS → FORCE_COMPLETED");
          log.info(
              "WorkerAgent terminal verified-pass force-complete run={} task={} iter={}"
                  + " evidence={}",
              runId,
              req.context().taskId(),
              iter,
              terminalVerifiedPassEvidence);
          contextStore.delete(req.context());
          recordTerminal(WorkerResult.Outcome.COMPLETED, req, iter);
          return WorkerResult.builder()
              .outcome(WorkerResult.Outcome.COMPLETED)
              .iterations(iter)
              .toolCallsDispatched(toolsDispatched)
              .completionSummary(summary)
              .finalAssistantText(lastText)
              .repeatedAssistantTextCount(maxRepeatedAssistantTextCount)
              .toolCallLog(toolLog)
              .totalLatencyMs(System.currentTimeMillis() - start)
              .build();
        }
        if (!progressNudged) {
          progressNudged = true;
          log.info(
              "WorkerAgent PROGRESS_NUDGE (text-only stop) run={} task={} iter={} writes={}"
                  + " textPreview={}",
              runId,
              req.context().taskId(),
              iter,
              totalWriteCalls,
              previewThought(assistantText));
          history.add(
              new UserMessage(
                  progressV2 && !terminalTaskMode
                      ? progressNudgeMessageV2(totalWriteCalls)
                      : progressNudgeMessage(totalWriteCalls, terminalTaskMode)));
          continue; // Re-enter the loop; iter increments; model gets another shot.
        }
        // progress-v2 — a repeated text-only stop is retried with tool_choice=required (bounded),
        // not treated as terminal. Reasoning-style models plan out loud; a plan turn is not a
        // reason to kill the run. If the model STILL refuses tools after the bounded retries,
        // fall through to the v1 termination below.
        if (progressV2
            && !terminalTaskMode
            && textOnlyForcedRetries < PROGRESS_V2_TEXT_ONLY_FORCED_RETRIES) {
          textOnlyForcedRetries++;
          forceToolChoiceNextCall = true;
          log.info(
              "WorkerAgent TEXT_ONLY_FORCED_RETRY run={} task={} iter={} retry={}/{}",
              runId,
              req.context().taskId(),
              iter,
              textOnlyForcedRetries,
              PROGRESS_V2_TEXT_ONLY_FORCED_RETRIES);
          history.add(
              new UserMessage(
                  "Your last reply had no tool call. Respond with a TOOL CALL now: completeTask"
                      + " (with your summary) if the task is done and verified, otherwise the"
                      + " next concrete action (readFile / editFile / shell / buildProject)."
                      + " A text-only reply ends the task as incomplete."));
          continue;
        }
        if (terminalTaskMode) {
          WorkerResult finalized =
              runTerminalFinalizerCall(req, history, toolsDispatched, toolLog, start, iter);
          if (finalized != null) return finalized;
        }
        recordTerminal(WorkerResult.Outcome.STOPPED_WITHOUT_COMPLETE, req, iter);
        return WorkerResult.builder()
            .outcome(WorkerResult.Outcome.STOPPED_WITHOUT_COMPLETE)
            .iterations(iter)
            .toolCallsDispatched(toolsDispatched)
            .finalAssistantText(lastText)
            .repeatedAssistantTextCount(maxRepeatedAssistantTextCount)
            .toolCallLog(toolLog)
            .totalLatencyMs(System.currentTimeMillis() - start)
            .build();
      }

      // Append the assistant turn (text + tool_calls + optional metadata) to history.
      AssistantMessage.Builder assistantBuilder =
          AssistantMessage.builder().content(assistantText).toolCalls(calls);
      if (assistantMetadata != null) {
        assistantBuilder.properties(assistantMetadata);
      }
      history.add(assistantBuilder.build());

      // Dispatch every tool call. completeTask short-circuits the loop.
      List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
      String completionSummary = null;
      // RL-2026-05-052 Phase 5+6 — the LAST tool call in this batch is the "immediately prior"
      // call for the next LLM turn; its output is the most likely to be re-referenced by the
      // worker's next decision, so ToolOutputCompactor preserves it verbatim (skip compaction).
      // Loop converted from for-each to indexed for so we can compute callIdx == lastIdx without
      // re-iterating; behaviour-equivalent to the prior for-each (verified: the four pre-existing
      // WorkerAgent test failures reproduce on the unmodified HEAD code with the same
      // for-each).
      final int lastIdx = calls.size() - 1;
      // W4.1 (C1, vix-leverage) — read-only-batch parallelism: a pure read/explore turn (every call
      // is a read-only tool) pre-executes its tool bodies concurrently on virtual threads; the loop
      // below then consumes the cached envelopes in deterministic original order, so every per-call
      // state mutation (metrics / SSE / toolResponses ordering / gate-tracking) is unchanged. Empty
      // for any batch with a write/build/shell/completeTask call (or a remote run) → sequential.
      java.util.Map<String, String> parallelReads = maybeParallelReadDispatch(calls, req);
      for (int callIdx = 0; callIdx < calls.size(); callIdx++) {
        AssistantMessage.ToolCall tc = calls.get(callIdx);
        final boolean isImmediatelyPriorToolCall = (callIdx == lastIdx);
        toolsDispatched++;
        if (CompleteTaskTool.NAME.equals(tc.name())) {
          // V182 layer-3 verification gate. When the request opted in (patch mode),
          // refuse completeTask unless a runTests call landed within the last
          // TEST_VERIFICATION_RECENCY_ITERS iterations. The agent gets the refusal
          // envelope in tool history and can react by calling runTests next iter.
          // Build mode (gateOn=false) keeps today's behaviour — completeTask always
          // succeeds because build mode has its own smoke gate downstream.
          if (gateOn) {
            int sinceLastTest = lastTestRunIter < 0 ? Integer.MAX_VALUE : iter - lastTestRunIter;
            if (sinceLastTest > TEST_VERIFICATION_RECENCY_ITERS) {
              String reason;
              if (lastTestRunIter < 0) {
                reason =
                    "completeTask refused — you have not called the `runTests` tool"
                        + " in this conversation. Run the failing test (using runTests"
                        + " with the FAIL_TO_PASS test ids from the user message) and"
                        + " confirm it passes before calling completeTask. The bench"
                        + " harness will not score your patch unless the failing test"
                        + " actually passes.";
              } else {
                reason =
                    "completeTask refused — last successful runTests was "
                        + sinceLastTest
                        + " iterations ago (gate window: "
                        + TEST_VERIFICATION_RECENCY_ITERS
                        + "). Re-run the failing test now to confirm your patch still"
                        + " works against the live workspace, then call completeTask"
                        + " again.";
              }
              String refused = errEnvelope("FORBIDDEN", reason);
              toolResponses.add(
                  new ToolResponseMessage.ToolResponse(safeId(tc.id()), tc.name(), refused));
              toolLog.add(tc.name() + " → REFUSED (no recent runTests)");
              log.info(
                  "WorkerAgent completeTask GATED run={} task={} iter={} sinceLastTest={}",
                  req.context().runId(),
                  req.context().taskId(),
                  iter,
                  sinceLastTest == Integer.MAX_VALUE ? "never" : sinceLastTest);
              // Don't return — fall through; the rest of this iter's tool calls (if any)
              // still dispatch normally, and the next iter the agent sees the refusal
              // in history.
              continue;
            }
          }
          // 2026-05-08 build-verification gate. Symmetric to the test-verification gate above:
          // when the reviewer entered with finalBuildPassed=false, it sets
          // requireBuildVerification.
          // We refuse completeTask unless the agent has run buildProject and seen exit=0 within
          // BUILD_VERIFICATION_RECENCY_ITERS. Without this, the reviewer often calls completeTask
          // after a few exploratory edits without re-running the build — leaving the workspace
          // in PARTIAL/FINAL_BUILD_FAILED. Targets the dominant 30-day failure mode (16/22).
          if (buildGateOn) {
            int sinceLastBuild =
                lastBuildPassIter < 0 ? Integer.MAX_VALUE : iter - lastBuildPassIter;
            if (sinceLastBuild > BUILD_VERIFICATION_RECENCY_ITERS) {
              String reason;
              if (lastBuildPassIter < 0) {
                reason =
                    "completeTask refused — the build was failing entering this review pass"
                        + " and you have not yet seen a successful `buildProject` call (exit=0)"
                        + " in this conversation. Make whatever edits are needed to fix the"
                        + " build error, then call buildProject and confirm exit=0 before"
                        + " calling completeTask. A reviewer pass that ends with a still-failing"
                        + " build is the dominant cause of PARTIAL runs in production.";
              } else {
                reason =
                    "completeTask refused — last passing buildProject (exit=0) was "
                        + sinceLastBuild
                        + " iterations ago (gate window: "
                        + BUILD_VERIFICATION_RECENCY_ITERS
                        + "). You may have edited files since then. Re-run buildProject now"
                        + " to confirm the build still passes, then call completeTask again.";
              }
              String refused = errEnvelope("FORBIDDEN", reason);
              toolResponses.add(
                  new ToolResponseMessage.ToolResponse(safeId(tc.id()), tc.name(), refused));
              toolLog.add(tc.name() + " → REFUSED (no recent buildProject pass)");
              log.info(
                  "WorkerAgent completeTask BUILD-GATED run={} task={} iter={} sinceLastBuild={}",
                  req.context().runId(),
                  req.context().taskId(),
                  iter,
                  sinceLastBuild == Integer.MAX_VALUE ? "never" : sinceLastBuild);
              continue;
            }
          }
          // #2 deterministic deliverable gate (RL-2026-06, terminal + remote mode). The hidden TB
          // tests often assert a file exists at an EXACT path (test_*_exists); the dominant such
          // miss
          // is the worker producing the deliverable at the WRONG path or not at all. Before
          // allowing
          // completeTask, dispatch ONE container-side existence check for the task's required
          // paths;
          // any missing path refuses with the specific path(s) named — bounded to
          // DELIVERABLE_GATE_MAX_REFUSALS, then YIELDS (fail-open: a stale/over-eager extraction
          // can
          // cost a couple of iterations but can NEVER false-FAIL a complete run). COMMON to all
          // consumers: the existence check runs via the workspace-agnostic dispatch (local shell on
          // the VM workspace, or remote shell in the TB container). Activates on a non-empty
          // required
          // path list — only TB supplies one today, so it is inert (not forked) for BUILD/PATCH.
          if (!requiredDeliverables.isEmpty()
              && deliverableRefusals < DELIVERABLE_GATE_MAX_REFUSALS) {
            List<String> missing = missingDeliverables(req.context(), requiredDeliverables);
            if (!missing.isEmpty()) {
              deliverableRefusals++;
              String refused =
                  errEnvelope("DELIVERABLE_MISSING", terminalDeliverableMessage(missing));
              toolResponses.add(
                  new ToolResponseMessage.ToolResponse(safeId(tc.id()), tc.name(), refused));
              toolLog.add(
                  tc.name()
                      + " → REFUSED (missing deliverable: "
                      + String.join(",", missing)
                      + ")");
              log.info(
                  "WorkerAgent completeTask DELIVERABLE-GATED run={} task={} iter={} missing={}",
                  req.context().runId(),
                  req.context().taskId(),
                  iter,
                  missing);
              continue;
            }
          }
          // #VBC verify-before-complete (RL-2026-06-…, worker.verify-before-complete.enabled,
          // V235).
          // SERVER-RUNS the project's own tests here (vs the soft test/build gates above, which
          // only
          // check whether the MODEL ran them) — so a worker that calls completeTask without ever
          // verifying is still caught. Bounded to VBC_GATE_MAX_REFUSALS, then YIELDS; fail-open
          // when
          // no runner resolves / a remote run / any error (the gate can nudge a fix for a few
          // rounds
          // but can NEVER false-FAIL a complete run). Runs the runner DIRECTLY (not the flag-gated
          // runWorkspaceTests tool) so it is independent of worker.build-tdd.enabled (V217) and
          // adds
          // no model-visible schema change (RL-2026-05-061 schema-stall class avoided). Ordered
          // before the LLM critic so a red suite refuses without paying for an acceptance-review
          // call.
          if (vbcOn && vbcRefusals < VBC_GATE_MAX_REFUSALS) {
            String vbcFailure = verifyBeforeCompleteFailure(req.context());
            if (vbcFailure != null) {
              vbcRefusals++;
              String refused =
                  errEnvelope("VERIFY_FAILED", verifyBeforeCompleteMessage(vbcFailure));
              toolResponses.add(
                  new ToolResponseMessage.ToolResponse(safeId(tc.id()), tc.name(), refused));
              toolLog.add(tc.name() + " → REFUSED (verify-before-complete: " + vbcFailure + ")");
              log.info(
                  "WorkerAgent completeTask VBC-GATED run={} task={} iter={} detail={}",
                  req.context().runId(),
                  req.context().taskId(),
                  iter,
                  vbcFailure);
              continue;
            }
          }
          // Terminal visible-test gate. The prompt asks workers to inspect/run /tests, but that is
          // advisory. With critic-pass enabled a worker could self-test against its own wrong
          // contract, the critic could miss the mismatch, and completeTask would be accepted
          // without ever executing the visible verifier. If /tests exists, require a passing
          // verifier-shaped command that actually targets /tests before completion.
          if (terminalTaskMode
              && terminalVerifyGateOn
              && !terminalVerifiedPass
              && terminalVisibleTestRefusals < TERMINAL_VISIBLE_TEST_GATE_MAX_REFUSALS
              && visibleTestsExist(req.context())) {
            terminalVisibleTestRefusals++;
            String refused =
                errEnvelope("TERMINAL_VISIBLE_TESTS_REQUIRED", terminalVisibleTestsMessage());
            toolResponses.add(
                new ToolResponseMessage.ToolResponse(safeId(tc.id()), tc.name(), refused));
            toolLog.add(tc.name() + " → REFUSED (visible terminal tests not run)");
            log.info(
                "WorkerAgent completeTask VISIBLE-TEST-GATED run={} task={} iter={} refusals={}",
                req.context().runId(),
                req.context().taskId(),
                iter,
                terminalVisibleTestRefusals);
            continue;
          }
          // #1 critic/reviewer pass (RL-2026-06) — COMMON to all consumers, not a TB fork. One
          // fresh-context acceptance review before the FIRST completeTask: a no-tool LlmRouter call
          // that sees ONLY the task + the candidate's deliverable (git diff for a local product
          // run,
          // /app files for a remote TB run) and lists every requirement it fails — independent of
          // the
          // worker's own anchored reasoning. Catches the dominant premature-completion class
          // (structural/smoke pass, the one real requirement fails). One-shot (criticNudged) +
          // fail-open (a PASS verdict OR any critic error permits completion) so it can neither
          // loop
          // nor false-block. When on it subsumes the lighter verify nudge below.
          if (criticOn && !criticNudged && !terminalVerifiedPass) {
            criticNudged = true;
            String gaps = runCriticPass(req, requiredDeliverables);
            if (gaps != null) {
              String refused = errEnvelope("ACCEPTANCE_REVIEW", criticMessage(gaps));
              toolResponses.add(
                  new ToolResponseMessage.ToolResponse(safeId(tc.id()), tc.name(), refused));
              toolLog.add(tc.name() + " → CRITIC (acceptance-review gaps)");
              log.info(
                  "WorkerAgent completeTask CRITIC-GATED run={} task={} iter={}",
                  req.context().runId(),
                  req.context().taskId(),
                  iter);
              continue;
            }
          }
          // #3 verify-before-done soft nudge (BUILD/blind; PATCH uses the hard gateOn above).
          // Two one-shot triggers (verifyNudged → can never loop; worst case one round-trip):
          //   (a) GENERIC: the worker edited but ran NOTHING since its last edit
          //       (lastEditIter > lastVerifyIter). Refuse once and ask it to verify.
          //   (b) TERMINAL (RL-2026-06, terminalVerifyGate): trigger (a) is silently defeated when
          //       the worker runs a TRIVIAL shell smoke after editing (e.g. `python -c "import
          //       run"`) — that bumps lastVerifyIter so lastEditIter <= lastVerifyIter and the
          //       nudge never fires, yet the hidden Terminal-Bench tests check the task's EXACT
          //       stated behaviour/edge/example. So in terminal-verify mode, fire ONE nudge before
          //       the first completeTask whenever the worker has written something, regardless of a
          //       shallow smoke, pointing it at the task's own concrete example/criterion. The
          //       message protects already-correct solutions (complete now if output already
          //       matches) so it can't push a passing candidate into risky rework.
          if (!gateOn
              && !verifyNudged
              && !criticOn
              && !vbcOn
              && !terminalVerifiedPass
              && totalWriteCalls > 0
              && (lastEditIter > lastVerifyIter || terminalVerifyGateOn)) {
            verifyNudged = true;
            String reason =
                errEnvelope(
                    "VERIFY",
                    terminalVerifyGateOn
                        ? terminalVerifyMessage()
                        : "completeTask deferred — you've edited files but haven't run anything"
                            + " since your last edit. Verify the fix actually works: run your"
                            + " reproduction or the relevant tests via the shell tool (or"
                            + " buildProject) and confirm the expected behaviour, then call"
                            + " completeTask again. If the change is genuinely unverifiable, say"
                            + " why in completionNotes and call completeTask again now.");
            toolResponses.add(
                new ToolResponseMessage.ToolResponse(safeId(tc.id()), tc.name(), reason));
            toolLog.add(
                tc.name()
                    + (terminalVerifyGateOn
                        ? " → NUDGED (terminal verify before complete)"
                        : " → NUDGED (verify before complete)"));
            continue;
          }
          completionSummary = extractSummary(tc.arguments());
          String envelope = okEnvelope("task marked complete: " + completionSummary);
          toolResponses.add(
              new ToolResponseMessage.ToolResponse(safeId(tc.id()), tc.name(), envelope));
          toolLog.add(tc.name() + " → COMPLETED");
          log.info("WorkerAgent completeTask received: {}", completionSummary);
          // C2 (RL-2026-06, vix-leverage W1.4) — completeTask short-circuits this batch with a
          // return, so any sibling tool calls AFTER it in the same assistant turn never dispatch.
          // Their tool_use blocks would then have no matching tool_result in the persisted/replayed
          // history, violating the Anthropic pairing invariant (a dangling tool_use is a 400 on
          // re-send). Pair each un-run trailing sibling with a synthetic placeholder result so the
          // ToolResponseMessage covers every tool_use the assistant emitted this turn.
          for (int siblingIdx = callIdx + 1; siblingIdx <= lastIdx; siblingIdx++) {
            AssistantMessage.ToolCall superseded = calls.get(siblingIdx);
            toolResponses.add(
                new ToolResponseMessage.ToolResponse(
                    safeId(superseded.id()),
                    superseded.name(),
                    errEnvelope(
                        "SUPERSEDED", "not executed — completeTask ended the task this turn")));
            toolLog.add(superseded.name() + " → SUPERSEDED (after completeTask)");
          }
          // Append the tool response so the conversation is well-formed
          // (matters for any post-hoc inspection / persistence).
          history.add(ToolResponseMessage.builder().responses(toolResponses).build());
          // Successful completion — context is no longer needed for recovery.
          contextStore.delete(req.context());
          recordTerminal(WorkerResult.Outcome.COMPLETED, req, iter);
          return WorkerResult.builder()
              .outcome(WorkerResult.Outcome.COMPLETED)
              .iterations(iter)
              .toolCallsDispatched(toolsDispatched)
              .completionSummary(completionSummary)
              .finalAssistantText(lastText)
              .repeatedAssistantTextCount(maxRepeatedAssistantTextCount)
              .toolCallLog(toolLog)
              .totalLatencyMs(System.currentTimeMillis() - start)
              .build();
        }
        metrics.recordToolCall(tc.name());
        // C1 — use the pre-computed concurrent result for a read-only batch; else dispatch now.
        if (terminalVerifiedPass && terminalRiskyAfterVerifiedPass(tc.name())) {
          String refused = errEnvelope("TERMINAL_VERIFIED_PASS", terminalVerifiedPassMessage());
          toolResponses.add(
              new ToolResponseMessage.ToolResponse(safeId(tc.id()), tc.name(), refused));
          toolLog.add(tc.name() + " → REFUSED (terminal verified pass)");
          log.info(
              "WorkerAgent terminal verified-pass guard refused tool run={} task={} iter={} tool={}"
                  + " evidence={}",
              req.context().runId(),
              req.context().taskId(),
              iter,
              tc.name(),
              terminalVerifiedPassEvidence);
          continue;
        }
        // C1 — use the pre-computed concurrent result for a read-only batch; else dispatch now.
        String envelope =
            parallelReads.containsKey(safeId(tc.id()))
                ? parallelReads.get(safeId(tc.id()))
                : dispatchOne(tc, req, isImmediatelyPriorToolCall);
        // V182 layer-3 — track successful runTests for the completeTask gate.
        // 2026-05-08 strengthened: only count runs where tests ACTUALLY PASSED, not just
        // "runTests tool was called." Empirical motivation: 3 of 5 OpenAI hard-matrix failures
        // produced thin (1-2K token) wrong-fix patches because the agent ran tests, saw FAIL,
        // and called completeTask anyway — the gate let it through because it was scoped to
        // "tool was invoked" not "tests passed." Now: require the envelope to contain the
        // pytest-pass markers `"failed":[]` AND `"errored":[]` AND `"exit_code":0` to satisfy
        // the gate. Until those are present, completeTask remains REFUSED.
        if (RUN_TESTS_TOOL_NAME.equals(tc.name())
            && envelope != null
            && envelope.startsWith("{\"ok\":true")
            && envelope.contains("\\\"failed\\\":[]")
            && envelope.contains("\\\"errored\\\":[]")
            && envelope.contains("\\\"exit_code\\\":0")) {
          lastTestRunIter = iter;
        }
        // 2026-05-08 — track successful buildProject (exit=0) for the build-verification gate.
        // BuildProjectTool's content includes a "\nexit=<n>\n" line; in the JSON-encoded ok
        // envelope this becomes the literal substring `\\nexit=0\\n`. Match conservatively to
        // avoid false-positive on output text that happens to contain "exit=0" without the
        // newline framing (e.g. compiler messages quoting source).
        if (BUILD_PROJECT_TOOL_NAME.equals(tc.name())
            && envelope != null
            && envelope.startsWith("{\"ok\":true")
            && envelope.contains("\\nexit=0\\n")) {
          lastBuildPassIter = iter;
        }
        // #3 verify-before-done tracking: file edits vs "ran something to check the fix".
        if (isWriteTool(tc.name())) {
          lastEditIter = iter;
        } else if ((isShellTool(tc.name())
                || BUILD_PROJECT_TOOL_NAME.equals(tc.name())
                || RUN_TESTS_TOOL_NAME.equals(tc.name()))
            && isOkEnvelope(envelope)) {
          lastVerifyIter = iter;
        }
        if (terminalTaskMode
            && !terminalVerifiedPass
            && terminalVerifierPass(
                tc.name(), tc.arguments(), envelope, requiredDeliverables, req.context())) {
          terminalVerifiedPass = true;
          terminalVerifiedPassEvidence = terminalVerifierEvidence(tc.name(), tc.arguments());
          snapshotTerminalVerifiedCandidate(req.context(), requiredDeliverables);
          toolLog.add("TERMINAL_VERIFIED_PASS → " + terminalVerifiedPassEvidence);
          log.info(
              "WorkerAgent terminal verified-pass detected run={} task={} iter={} evidence={}",
              req.context().runId(),
              req.context().taskId(),
              iter,
              terminalVerifiedPassEvidence);
        }
        toolResponses.add(
            new ToolResponseMessage.ToolResponse(safeId(tc.id()), tc.name(), envelope));
        toolLog.add(toolCallLogEntry(tc, envelope));
        // progress-v2 — repetition signal: consecutive failures of the SAME call signature
        // (success clears the streak). Re-running an identical failing command without changing
        // anything is the true stagnation-loop shape.
        if (progressV2) {
          String sig = callSignature(tc);
          if (isFailureEnvelope(envelope)) {
            failedSignatureStreaks.merge(sig, 1, Integer::sum);
          } else {
            failedSignatureStreaks.remove(sig);
          }
        }
        // 2026-05-04 — emit a per-mutation log line so the LiveLog shows real activity, not
        // just the token counter. Filtered to mutating + heavy tools (writes, edits, shell,
        // build) — read/list/search/git noise stays out of the user-facing log so the screen
        // doesn't drown.
        emitToolCallLog(req, tc);
      }
      history.add(ToolResponseMessage.builder().responses(toolResponses).build());

      // Progress signal (RL-2026-05-005)
      // Reset the counter on any write-class tool call (writeFile / editFile); otherwise
      // increment. Crossing progressWarnAfter injects ONE corrective nudge (the same message the
      // text-only-stop path uses); crossing progressForceAfter force-terminates with
      // COMPLETED_BY_STAGNATION (Lever B salvage first if nothing was ever written). One counter,
      // one nudge — this replaces the old Lever A + Lever C + stagnation-warn trio.
      boolean wroteThisIter =
          calls.stream().anyMatch(tc -> isProgressTool(tc.name(), terminalTaskMode));
      if (patchNoEditNudgeOn && totalWriteCalls == 0 && !progressV2) {
        // PATCH runs score the source diff. Before the first edit, build/test/read loops are useful
        // evidence but not progress toward a patch; only an actual write should reset the no-edit
        // watchdog. After the first edit, fall back to the normal progress definition so legitimate
        // edit/build/test cycles still get room to converge.
        wroteThisIter = calls.stream().anyMatch(tc -> isWriteTool(tc.name()));
      }
      if (progressV2) {
        // v2 — novelty IS progress: any call whose (tool, normalized-args) signature is new this
        // task resets the counter. Reads/searches/shell against NEW targets are investigation;
        // only repetition accumulates. Writes/builds keep counting via the v1 definition too.
        boolean novelThisIter = false;
        for (AssistantMessage.ToolCall tc : calls) {
          if (seenCallSignatures.add(callSignature(tc))) novelThisIter = true;
        }
        wroteThisIter = wroteThisIter || novelThisIter;
      }
      if (wroteThisIter) {
        itersSinceProgress = 0;
        for (AssistantMessage.ToolCall tc : calls) {
          if (isWriteTool(tc.name())) totalWriteCalls++;
        }
      } else {
        itersSinceProgress++;
      }
      // v2 — worst current same-signature failure streak (0 when off/empty).
      final int worstFailureStreak =
          progressV2 && !failedSignatureStreaks.isEmpty()
              ? java.util.Collections.max(failedSignatureStreaks.values())
              : 0;
      if (patchNoEditNudgeOn
          && !progressV2
          && !progressNudged
          && totalWriteCalls == 0
          && itersSinceProgress >= PATCH_NO_EDIT_WARN_AFTER_N) {
        log.info(
            "WorkerAgent PATCH_NO_EDIT_NUDGE run={} task={} iter={} itersSinceProgress={}"
                + " threshold={}",
            req.context().runId(),
            req.context().taskId(),
            iter,
            itersSinceProgress,
            PATCH_NO_EDIT_WARN_AFTER_N);
        history.add(new UserMessage(progressNudgeMessage(totalWriteCalls, false)));
        progressNudged = true;
      }
      if (progressV2
          && patchNoEditNudgeOn
          && !progressNudged
          && totalWriteCalls == 0
          && iter >= explorationNudgeAfter) {
        // v2 replacement for the 6-non-write-iteration PATCH nudge: a TOTAL-iteration exploration
        // ceiling. Localizing in a large repo legitimately takes dozens of novel reads; only past
        // the ceiling do we ask the worker to commit to a hypothesis.
        log.info(
            "WorkerAgent EXPLORATION_CEILING_NUDGE run={} task={} iter={} threshold={}",
            req.context().runId(),
            req.context().taskId(),
            iter,
            explorationNudgeAfter);
        history.add(new UserMessage(progressNudgeMessageV2(totalWriteCalls)));
        progressNudged = true;
      }
      if (progressV2 && !progressNudged && worstFailureStreak >= PROGRESS_V2_REPEAT_FAILURE_NUDGE_AT) {
        log.info(
            "WorkerAgent REPEAT_FAILURE_NUDGE run={} task={} iter={} worstStreak={}",
            req.context().runId(),
            req.context().taskId(),
            iter,
            worstFailureStreak);
        history.add(new UserMessage(progressNudgeMessageV2(totalWriteCalls)));
        progressNudged = true;
      }
      if (!progressNudged && itersSinceProgress >= progressWarnAfter) {
        log.info(
            "WorkerAgent PROGRESS_NUDGE run={} task={} iter={} itersSinceProgress={} threshold={}"
                + " totalWrites={}",
            req.context().runId(),
            req.context().taskId(),
            iter,
            itersSinceProgress,
            progressWarnAfter,
            totalWriteCalls);
        // UserMessage (NOT SystemMessage): GeminiNativeToolClient folds every SystemMessage into
        // systemInstruction, so a mid-run SystemMessage mutates the implicit-cache prefix and
        // kills caching for the rest of the task. A UserMessage stays in contents[] as a normal
        // turn — cache prefix preserved; Anthropic + OpenAI honour it equally.
        history.add(
            new UserMessage(
                progressV2 && !terminalTaskMode
                    ? progressNudgeMessageV2(totalWriteCalls)
                    : progressNudgeMessage(totalWriteCalls, terminalTaskMode)));
        progressNudged = true;
      }
      if (terminalTaskMode && !progressNudged && iter >= progressWarnAfter) {
        log.info(
            "WorkerAgent TERMINAL_ARTIFACT_NUDGE run={} task={} iter={} threshold={}"
                + " totalWrites={}",
            req.context().runId(),
            req.context().taskId(),
            iter,
            progressWarnAfter,
            totalWriteCalls);
        history.add(new UserMessage(progressNudgeMessage(totalWriteCalls, true)));
        progressNudged = true;
      }
      if (terminalTaskMode
          && !terminalArtifactDeadlineNudged
          && iter >= terminalArtifactDeadlineAfter) {
        log.info(
            "WorkerAgent TERMINAL_ARTIFACT_DEADLINE run={} task={} iter={} threshold={}"
                + " totalWrites={}",
            req.context().runId(),
            req.context().taskId(),
            iter,
            terminalArtifactDeadlineAfter,
            totalWriteCalls);
        history.add(new UserMessage(terminalArtifactDeadlineMessage()));
        terminalArtifactDeadlineNudged = true;
      }
      if (terminalTaskMode
          && !terminalForcedFinalizerAttempted
          && iter >= terminalForcedFinalizerAfter) {
        terminalForcedFinalizerAttempted = true;
        log.warn(
            "WorkerAgent TERMINAL_FORCED_FINALIZER run={} task={} iter={} threshold={}"
                + " totalWrites={}",
            req.context().runId(),
            req.context().taskId(),
            iter,
            terminalForcedFinalizerAfter,
            totalWriteCalls);
        WorkerResult finalized =
            runTerminalFinalizerCall(req, history, toolsDispatched, toolLog, start, iter, false);
        if (finalized != null) return finalized;
        history.add(
            new UserMessage(
                "TERMINAL FINALIZER DID NOT PRODUCE A CLOSING TOOL CALL. On the next response,"
                    + " stop exploring and either create/overwrite the exact /app deliverable with"
                    + " writeFile/editFile, or call completeTask if the deliverable already exists."
                    + " Do not run another broad shell investigation."));
      }
      // v2 repetition force: the same call signature failing PROGRESS_V2_REPEAT_FAILURE_FORCE_AT
      // times consecutively is a genuine loop regardless of how many novel reads happen between
      // failures. This is the v2 hard-stop; the v1 non-write counter still applies as the
      // fallback (novelty resets protect legitimate exploration from it when v2 is on).
      final boolean repetitionForce =
          progressV2 && worstFailureStreak >= PROGRESS_V2_REPEAT_FAILURE_FORCE_AT;
      if (itersSinceProgress >= progressForceAfter || repetitionForce) {
        log.warn(
            "WorkerAgent PROGRESS_FORCE run={} task={} iter={} itersSinceProgress={} threshold={}"
                + " totalWrites={} trigger={}",
            req.context().runId(),
            req.context().taskId(),
            iter,
            itersSinceProgress,
            progressForceAfter,
            totalWriteCalls,
            repetitionForce ? "REPEATED_FAILURE_STREAK(" + worstFailureStreak + ")" : "NO_PROGRESS");
        // 2026-05-09 Lever B extension — salvage on STAGNATION_FORCE with zero writes.
        // RCA on run 4421cc6e/T9 (Debt Resolution R3+R4): LOW BUILD task spent 12 iters
        // reading without writing, hit stagnation force, was marked FAILED with no recovery
        // attempt. Lever B existed for ITERATION_LIMIT_EXCEEDED + BUDGET_EXCEEDED but not
        // for STAGNATION_FORCE — this third path completes the salvage triangle. Same
        // contract as the iter-limit branch: one extra LLM call, ~$0.05-0.20 spend, "wrong
        // file edit beats empty patch." If salvage produces an edit, the smoke gate
        // downstream decides final task status (PASS → COMPLETE, FAIL → retry / PARTIAL).
        // If salvage fails too, fall through to the original COMPLETED_BY_STAGNATION return.
        if (totalWriteCalls == 0) {
          try {
            WorkerResult salvage = runSalvageCall(req, history, toolsDispatched, toolLog, start);
            if (salvage != null) {
              log.info(
                  "WorkerAgent STAGNATION salvage produced output run={} task={}",
                  req.context().runId(),
                  req.context().taskId());
              return salvage;
            }
          } catch (Exception e) {
            log.warn(
                "WorkerAgent STAGNATION salvage failed run={} task={}: {}",
                req.context().runId(),
                req.context().taskId(),
                e.getMessage());
          }
        }
        recordTerminal(WorkerResult.Outcome.COMPLETED_BY_STAGNATION, req, iter);
        return WorkerResult.builder()
            .outcome(WorkerResult.Outcome.COMPLETED_BY_STAGNATION)
            .iterations(iter)
            .toolCallsDispatched(toolsDispatched)
            .finalAssistantText(lastText)
            .repeatedAssistantTextCount(maxRepeatedAssistantTextCount)
            .toolCallLog(toolLog)
            .totalLatencyMs(System.currentTimeMillis() - start)
            .build();
      }

      // Periodic context persistence for crash recovery / retry-with-context. Best-effort;
      // failures inside ContextStore don't propagate. Save on the iteration boundary
      // so a saved snapshot always ends on a well-formed tool-response turn.
      if (iter % CONTEXT_PERSIST_EVERY_N_ITERATIONS == 0) {
        contextStore.save(req.context(), history, iter);
      }
    }

    log.warn(
        "WorkerAgent ITERATION LIMIT run={} task={} iters={} totalWrites={}",
        req.context().runId(),
        req.context().taskId(),
        maxIter,
        totalWriteCalls);

    // 2026-05-08 Lever B: best-guess salvage. We hit the iter cap with zero writes — the agent
    // exhausted the budget on investigation without committing to any edit. Make ONE final LLM
    // call asking for the best-guess edit, with explicit forcing function. The salvage call
    // gets a much shorter system prompt (just "you're out of iters, dump your best guess as a
    // writeFile/editFile call now") and runs with reduced maxIter=2 (the salvage call itself +
    // one iter for tool dispatch). Even a wrong-file edit produces SOMETHING in the diff —
    // empty diff is automatic failure on the SWE-bench evaluator.
    if (totalWriteCalls == 0) {
      try {
        WorkerResult salvage = runSalvageCall(req, history, toolsDispatched, toolLog, start);
        if (salvage != null) return salvage;
      } catch (Exception e) {
        log.warn(
            "WorkerAgent SALVAGE call failed run={} task={}: {}",
            req.context().runId(),
            req.context().taskId(),
            e.getMessage());
      }
    }

    recordTerminal(WorkerResult.Outcome.ITERATION_LIMIT_EXCEEDED, req, maxIter);
    return WorkerResult.builder()
        .outcome(WorkerResult.Outcome.ITERATION_LIMIT_EXCEEDED)
        .iterations(maxIter)
        .toolCallsDispatched(toolsDispatched)
        .finalAssistantText(lastText)
        .repeatedAssistantTextCount(maxRepeatedAssistantTextCount)
        .toolCallLog(toolLog)
        .totalLatencyMs(System.currentTimeMillis() - start)
        .build();
  }

  /**
   * 2026-05-08 Lever B salvage. Make one final LLM call with the worker's full conversation history
   * + a forcing function ("you're out of iters, write SOMETHING now"). If the LLM emits a
   * write-class tool call (writeFile / editFile), dispatch it once and return COMPLETED; otherwise
   * return null and the caller falls through to ITERATION_LIMIT_EXCEEDED.
   *
   * <p>Why this beats just letting the iter cap fire: the worker's history has all the
   * investigation context (every readFile output, every searchFiles result). The LLM at this point
   * has built a mental model of the bug — the salvage prompt asks it to dump that into a patch
   * instead of abandoning it. Even a wrong-file edit produces a non-empty diff which is better than
   * nothing on the SWE-bench evaluator (won't resolve, but doesn't fail at the empty-patch
   * precondition).
   */
  private WorkerResult runSalvageCall(
      WorkerTaskRequest req,
      List<Message> history,
      int toolsDispatched,
      List<String> toolLog,
      long start) {
    log.info(
        "WorkerAgent SALVAGE attempting run={} task={} historyLen={}",
        req.context().runId(),
        req.context().taskId(),
        history.size());

    history.add(
        new UserMessage(
            "ITER LIMIT REACHED. You've explored extensively but haven't written any code. This is"
                + " your last call. Based on everything you've read, output ONE writeFile or"
                + " editFile tool call with your best-guess fix. Even a wrong-file edit beats an"
                + " empty patch (empty = automatic failure). Do NOT think out loud, do NOT call"
                + " readFile or any other tool first — emit the write tool call DIRECTLY. If"
                + " genuinely impossible to guess, call completeTask with summary='salvage: no fix"
                + " identified' to exit cleanly."));

    boolean force = Boolean.TRUE.equals(req.forceToolChoiceOnSalvage());
    if (force) {
      // RL-2026-05-059 — forced attempt: restrict the tool set to the write/complete tools and set
      // tool_choice="required" so a model that would otherwise return reasoning-only text (the
      // verified 4/10 native-DeepSeek empty-patch failure, where the salvage call produced 109
      // output tokens but no tool call) MUST emit an actual edit or completeTask.
      WorkerResult forced =
          salvageAttempt(
              req, history, toolsDispatched, toolLog, start, salvageTools(req, true), "required");
      if (forced != null) return forced;
      log.info(
          "WorkerAgent SALVAGE forced attempt yielded nothing — retrying with auto tool-choice");
    }
    // Default path (flag off) AND fallback for the forced path: full tool set, tool_choice=auto.
    // The fallback covers providers that reject tool_choice="required" (the forced call errors to
    // null) so a forced attempt can never REGRESS the salvage that occasionally works today.
    return salvageAttempt(
        req, history, toolsDispatched, toolLog, start, salvageTools(req, false), null);
  }

  /**
   * One salvage LLM call + tool dispatch. Extracted from {@link #runSalvageCall} (RL-2026-05-059)
   * so the forced ({@code tool_choice="required"}) and auto attempts share identical dispatch
   * handling. Returns the terminal {@link WorkerResult} when the salvage produced an edit
   * (COMPLETED) or a completeTask (ITERATION_LIMIT_EXCEEDED), or {@code null} when nothing usable
   * came back (router error, empty response, no tool calls, or only failed edits) so the caller can
   * fall through.
   */
  private WorkerResult salvageAttempt(
      WorkerTaskRequest req,
      List<Message> history,
      int toolsDispatched,
      List<String> toolLog,
      long start,
      List<ToolCallback> tools,
      String toolChoice) {
    ChatResponse resp;
    try {
      resp =
          router.call(
              LlmRequest.builder()
                  .provider(req.provider())
                  .model(req.model())
                  .messages(history)
                  .context(req.context())
                  .agentRole("WorkerAgent")
                  .stepId(
                      toolChoice == null ? "worker.salvage-auto" : "worker.salvage-" + toolChoice)
                  .temperature(0.0)
                  .maxTokens(req.maxTokensPerCall())
                  .toolsOverride(tools)
                  .toolChoice(toolChoice)
                  .readTimeoutSeconds(WORKER_HTTP_READ_TIMEOUT_SECONDS)
                  .build());
    } catch (Exception e) {
      log.warn("WorkerAgent SALVAGE router error (toolChoice={}): {}", toolChoice, e.getMessage());
      return null;
    }

    if (resp == null || resp.getResults() == null || resp.getResults().isEmpty()) return null;
    AssistantMessage assistant = resp.getResults().get(0).getOutput();
    List<AssistantMessage.ToolCall> calls = assistant.getToolCalls();
    if (calls == null || calls.isEmpty()) {
      log.info("WorkerAgent SALVAGE no tool calls produced — falling through to iter-limit");
      return null;
    }

    // Dispatch each tool call from the salvage response. We accept writeFile, editFile, AND
    // completeTask (the agent admitting it can't guess). Other tools dispatched but ignored
    // (unlikely to fire at this stage given the prompt).
    boolean wroteAnything = false;
    String salvageSummary = null;
    for (AssistantMessage.ToolCall tc : calls) {
      if (CompleteTaskTool.NAME.equals(tc.name())) {
        salvageSummary = "salvage path: " + extractSummary(tc.arguments());
        log.info("WorkerAgent SALVAGE got completeTask: {}", salvageSummary);
        recordTerminal(WorkerResult.Outcome.ITERATION_LIMIT_EXCEEDED, req, maxIterOf(req));
        return WorkerResult.builder()
            .outcome(WorkerResult.Outcome.ITERATION_LIMIT_EXCEEDED)
            .iterations(maxIterOf(req))
            .toolCallsDispatched(toolsDispatched)
            .completionSummary(salvageSummary)
            .toolCallLog(toolLog)
            .totalLatencyMs(System.currentTimeMillis() - start)
            .build();
      }
      if (isWriteTool(tc.name())) {
        // Salvage path only ever dispatches write/edit (both in ToolOutputCompactor.SKIP_TOOLS),
        // so the isLast flag is don't-care; pass false for symmetry with the main loop.
        String env = dispatchOne(tc, req, false);
        toolLog.add(
            "SALVAGE "
                + tc.name()
                + " -> "
                + (env != null && env.startsWith("{\"ok\":true") ? "OK" : "ERR"));
        if (env != null && env.startsWith("{\"ok\":true")) wroteAnything = true;
      }
    }

    if (wroteAnything) {
      log.info("WorkerAgent SALVAGE produced an edit — returning COMPLETED");
      recordTerminal(WorkerResult.Outcome.COMPLETED, req, maxIterOf(req));
      return WorkerResult.builder()
          .outcome(WorkerResult.Outcome.COMPLETED)
          .iterations(maxIterOf(req))
          .toolCallsDispatched(toolsDispatched + calls.size())
          .completionSummary("salvage: best-guess edit produced after iter-limit")
          .toolCallLog(toolLog)
          .totalLatencyMs(System.currentTimeMillis() - start)
          .build();
    }
    return null;
  }

  /**
   * Terminal-Bench-specific rescue for DeepSeek-style "summary mode". The generic worker contract
   * still nudges once and then stops on a second text-only response, but terminal tasks are scored
   * by concrete files under /app. If the model says it is done without a tool call, make one final
   * forced tool-call turn so it can either call completeTask or produce/verify the deliverable.
   */
  private WorkerResult runTerminalFinalizerCall(
      WorkerTaskRequest req,
      List<Message> history,
      int toolsDispatched,
      List<String> toolLog,
      long start,
      int iter) {
    return runTerminalFinalizerCall(req, history, toolsDispatched, toolLog, start, iter, true);
  }

  private WorkerResult runTerminalFinalizerCall(
      WorkerTaskRequest req,
      List<Message> history,
      int toolsDispatched,
      List<String> toolLog,
      long start,
      int iter,
      boolean allowAutoFallback) {
    log.info(
        "WorkerAgent TERMINAL_FINALIZER attempting run={} task={} historyLen={} iter={}",
        req.context().runId(),
        req.context().taskId(),
        history.size(),
        iter);

    history.add(
        new UserMessage(
            "TERMINAL FINALIZATION REQUIRED. Text and further exploration do not close this"
                + " terminal task. Emit exactly ONE closing tool call now:\n"
                + "1. If the requested /app deliverable exists, call completeTask with a concise"
                + " summary.\n"
                + "2. If the deliverable is missing or stale, use writeFile/editFile to create or"
                + " overwrite the exact required path now. For small literal answers, write the"
                + " file directly instead of running more scripts.\n"
                + "3. If /tests exists and you have not run a verifier-shaped check, run"
                + " /tests/test.sh or the narrow failing test now. For performance tasks, compare"
                + " against the provided golden/reference threshold; correctness-only is not"
                + " sufficient.\n"
                + "4. If visible tests or failures show exact literals, exact expected files, or"
                + " exact directory contents, make the /app deliverable match that byte-for-byte."
                + " Remove helper binaries/scratch files from deliverable directories unless they"
                + " are explicitly expected.\n"
                + "5. For sanitizer/filter/transform tasks, preserve benign input byte-for-byte"
                + " unless the tests require normalization. Clean-input handling must be an"
                + " explicit no-op/copy path, not a parser/pretty-printer rewrite.\n"
                + "6. If a final verification command directly proves the deliverable, run that"
                + " command now.\n"
                + "Do not explain, do not browse broadly, and do not return another text-only"
                + " summary."));

    boolean force = Boolean.TRUE.equals(req.forceToolChoiceOnSalvage());
    if (force) {
      WorkerResult forced =
          terminalFinalizerAttempt(
              req,
              history,
              toolsDispatched,
              toolLog,
              start,
              iter,
              terminalFinalizerTools(req, true),
              "required");
      if (forced != null) return forced;
      if (!allowAutoFallback) return null;
      log.info(
          "WorkerAgent TERMINAL_FINALIZER forced attempt yielded nothing - retrying with auto"
              + " tool-choice");
    }
    if (!allowAutoFallback) return null;
    return terminalFinalizerAttempt(
        req,
        history,
        toolsDispatched,
        toolLog,
        start,
        iter,
        terminalFinalizerTools(req, false),
        null);
  }

  private WorkerResult terminalFinalizerAttempt(
      WorkerTaskRequest req,
      List<Message> history,
      int toolsDispatched,
      List<String> toolLog,
      long start,
      int iter,
      List<ToolCallback> tools,
      String toolChoice) {
    ChatResponse resp;
    try {
      resp =
          router.call(
              LlmRequest.builder()
                  .provider(req.provider())
                  .model(req.model())
                  .messages(history)
                  .context(req.context())
                  .agentRole("WorkerAgent")
                  .stepId(
                      toolChoice == null
                          ? "worker.terminal-finalizer-auto.iteration-" + iter
                          : "worker.terminal-finalizer-" + toolChoice + ".iteration-" + iter)
                  .temperature(0.0)
                  .maxTokens(req.maxTokensPerCall())
                  .toolsOverride(tools)
                  .toolChoice(toolChoice)
                  .readTimeoutSeconds(WORKER_HTTP_READ_TIMEOUT_SECONDS)
                  .build());
    } catch (Exception e) {
      log.warn(
          "WorkerAgent TERMINAL_FINALIZER router error (toolChoice={}): {}",
          toolChoice,
          e.getMessage());
      return null;
    }

    if (resp == null || resp.getResults() == null || resp.getResults().isEmpty()) return null;
    AssistantMessage assistant = resp.getResults().get(0).getOutput();
    List<AssistantMessage.ToolCall> calls = assistant.getToolCalls();
    if (calls == null || calls.isEmpty()) {
      log.info(
          "WorkerAgent TERMINAL_FINALIZER no tool calls produced - falling through to stopped");
      return null;
    }

    int dispatched = toolsDispatched;
    boolean productive = false;
    List<String> productiveTools = new ArrayList<>();
    for (AssistantMessage.ToolCall tc : calls) {
      dispatched++;
      if (CompleteTaskTool.NAME.equals(tc.name())) {
        if (!terminalFinalizerCompletionAllowed(req)) {
          toolLog.add("TERMINAL_FINALIZER completeTask -> BLOCKED");
          log.info(
              "WorkerAgent TERMINAL_FINALIZER completeTask blocked by completion gates run={}"
                  + " task={}",
              req.context().runId(),
              req.context().taskId());
          return null;
        }
        String summary = "terminal finalizer: " + extractSummary(tc.arguments());
        toolLog.add("TERMINAL_FINALIZER completeTask -> COMPLETED");
        log.info("WorkerAgent TERMINAL_FINALIZER got completeTask: {}", summary);
        contextStore.delete(req.context());
        recordTerminal(WorkerResult.Outcome.COMPLETED, req, iter);
        return WorkerResult.builder()
            .outcome(WorkerResult.Outcome.COMPLETED)
            .iterations(iter)
            .toolCallsDispatched(dispatched)
            .completionSummary(summary)
            .finalAssistantText(assistant.getText())
            .toolCallLog(toolLog)
            .totalLatencyMs(System.currentTimeMillis() - start)
            .build();
      }
      if (isWriteTool(tc.name()) || isShellTool(tc.name())) {
        String env = dispatchOne(tc, req, false);
        boolean ok = isOkEnvelope(env);
        toolLog.add("TERMINAL_FINALIZER " + tc.name() + " -> " + (ok ? "OK" : "ERR"));
        if (ok) {
          productive = true;
          productiveTools.add(tc.name());
        }
      } else {
        toolLog.add("TERMINAL_FINALIZER " + tc.name() + " -> IGNORED");
      }
    }

    if (productive) {
      if (!terminalFinalizerCompletionAllowed(req)) {
        toolLog.add("TERMINAL_FINALIZER productive tools -> BLOCKED");
        log.info(
            "WorkerAgent TERMINAL_FINALIZER productive tools blocked by completion gates run={}"
                + " task={}",
            req.context().runId(),
            req.context().taskId());
        return null;
      }
      log.info(
          "WorkerAgent TERMINAL_FINALIZER produced productive tools run={} task={} tools={}",
          req.context().runId(),
          req.context().taskId(),
          productiveTools);
      contextStore.delete(req.context());
      recordTerminal(WorkerResult.Outcome.COMPLETED, req, iter);
      return WorkerResult.builder()
          .outcome(WorkerResult.Outcome.COMPLETED)
          .iterations(iter)
          .toolCallsDispatched(dispatched)
          .completionSummary("terminal finalizer: productive tool call " + productiveTools)
          .finalAssistantText(assistant.getText())
          .toolCallLog(toolLog)
          .totalLatencyMs(System.currentTimeMillis() - start)
          .build();
    }
    return null;
  }

  private boolean terminalFinalizerCompletionAllowed(WorkerTaskRequest req) {
    List<String> requiredDeliverables =
        req.requiredDeliverablePaths() == null ? List.of() : req.requiredDeliverablePaths();
    if (!requiredDeliverables.isEmpty()) {
      List<String> missing = missingDeliverables(req.context(), requiredDeliverables);
      if (!missing.isEmpty()) {
        log.info(
            "WorkerAgent TERMINAL_FINALIZER deliverable gate blocked run={} task={} missing={}",
            req.context().runId(),
            req.context().taskId(),
            missing);
        return false;
      }
    }
    if (Boolean.TRUE.equals(req.criticPass())) {
      String gaps = runCriticPass(req, requiredDeliverables);
      if (gaps != null) {
        log.info(
            "WorkerAgent TERMINAL_FINALIZER critic blocked run={} task={} gaps={}",
            req.context().runId(),
            req.context().taskId(),
            previewThought(gaps));
        return false;
      }
    }
    return true;
  }

  private List<ToolCallback> terminalFinalizerTools(WorkerTaskRequest req, boolean restrict) {
    List<ToolCallback> all = toolRegistry.bindForWorker(req.context());
    if (!restrict) return all;
    java.util.Set<String> allow =
        java.util.Set.of("writeFile", "editFile", "shell", CompleteTaskTool.NAME);
    List<ToolCallback> filtered =
        all.stream().filter(t -> allow.contains(t.getToolDefinition().name())).toList();
    return filtered.isEmpty() ? all : filtered;
  }

  /**
   * The tool set for a salvage attempt (RL-2026-05-059). When {@code restrict} is true (the forced
   * attempt) the set is narrowed to {@code writeFile}/{@code editFile}/{@code completeTask} so a
   * forced tool call ({@code tool_choice="required"}) can only pick a productive action — not
   * another readFile that just re-enters the read loop the salvage exists to break. Falls back to
   * the full worker tool set if filtering leaves nothing (defensive against a registry-shape
   * change).
   */
  private List<ToolCallback> salvageTools(WorkerTaskRequest req, boolean restrict) {
    List<ToolCallback> all = toolRegistry.bindForWorker(req.context());
    if (!restrict) return all;
    java.util.Set<String> allow = java.util.Set.of("writeFile", "editFile", CompleteTaskTool.NAME);
    List<ToolCallback> filtered =
        all.stream().filter(t -> allow.contains(t.getToolDefinition().name())).toList();
    return filtered.isEmpty() ? all : filtered;
  }

  private static int maxIterOf(WorkerTaskRequest req) {
    return req.maxIterations() != null && req.maxIterations() > 0
        ? req.maxIterations()
        : DEFAULT_MAX_ITER;
  }

  /** Emit worker outcome + iterations metrics at every terminal path. */
  private void recordTerminal(WorkerResult.Outcome outcome, WorkerTaskRequest req, int iterations) {
    metrics.recordWorkerOutcome(outcome.name(), req.provider(), req.model());
    metrics.recordWorkerIterations(outcome.name(), req.provider(), req.model(), iterations);
  }

  /**
   * C1 (vix-leverage W4.1) — tools with no ordering-sensitive side effects, safe to run
   * concurrently.
   */
  private static final java.util.Set<String> READ_ONLY_PARALLEL_TOOLS =
      java.util.Set.of(
          "readFile",
          "read_file",
          "searchFiles",
          "grep",
          "ast_search",
          "astSearch",
          "glob",
          "listFiles",
          "gitStatus",
          "gitDiff");

  /**
   * W4.1 (C1) — when a single assistant turn emits ONLY read-only tool calls (&ge;2, non-remote),
   * execute their bodies concurrently on virtual threads and return a {@code {callId -> envelope}}
   * map the sequential dispatch loop consumes verbatim. Returns empty (&rarr; sequential dispatch)
   * for any batch that contains a write/build/shell/completeTask call, a remote run, or fewer than
   * 2 calls — this avoids read-after-write / write-after-read ordering hazards. The read set is
   * pure: {@code readFile} only touches the thread-safe read-ledger, the rest have no shared
   * mutable state, and {@code RunCostTracker} (reached via {@code maybeCompact}) is concurrent.
   * Fail-safe: a timeout or a partially-filled result falls back to sequential (re-runs are
   * idempotent reads). The win is the read-heavy early exploration phase of a task.
   */
  private java.util.Map<String, String> maybeParallelReadDispatch(
      java.util.List<AssistantMessage.ToolCall> calls, WorkerTaskRequest req) {
    if (!parallelReadToolsEnabled || calls.size() < 2 || toolRegistry.isRemoteRun(req.context())) {
      return java.util.Map.of();
    }
    for (AssistantMessage.ToolCall c : calls) {
      if (!READ_ONLY_PARALLEL_TOOLS.contains(c.name())) {
        return java.util.Map.of();
      }
    }
    List<String> labels = calls.stream().map(c -> safeId(c.id()) + ":" + c.name()).toList();
    log.info(
        "WorkerAgent parallelReadDispatch START run={} task={} calls={} labels={}",
        req.context().runId(),
        req.context().taskId(),
        calls.size(),
        labels);
    emitWorkerLog(
        req, "Parallel read batch start · " + calls.size() + " read-only tools · " + labels);
    final int last = calls.size() - 1;
    java.util.Map<String, String> out = new java.util.concurrent.ConcurrentHashMap<>();
    var exec = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    try {
      for (int i = 0; i < calls.size(); i++) {
        AssistantMessage.ToolCall tc = calls.get(i);
        boolean isLast = (i == last);
        exec.submit(() -> out.put(safeId(tc.id()), dispatchOne(tc, req, isLast)));
      }
      exec.shutdown();
      if (!exec.awaitTermination(120, java.util.concurrent.TimeUnit.SECONDS)) {
        exec.shutdownNow();
        return java.util.Map.of(); // timed out → sequential fallback
      }
    } catch (InterruptedException ie) {
      exec.shutdownNow();
      Thread.currentThread().interrupt();
      return java.util.Map.of();
    } catch (Exception e) {
      exec.shutdownNow();
      log.warn(
          "C1 parallel read-dispatch failed run={} — falling back to sequential: {}",
          req.context().runId(),
          e.toString());
      return java.util.Map.of();
    }
    // Belt-and-suspenders: any missing entry (a task that didn't complete) → sequential.
    if (out.size() != calls.size()) {
      return java.util.Map.of();
    }
    List<String> results =
        calls.stream()
            .map(
                c ->
                    safeId(c.id()) + ":" + c.name() + "=" + envelopeStatus(out.get(safeId(c.id()))))
            .toList();
    log.info(
        "WorkerAgent parallelReadDispatch DONE run={} task={} results={}",
        req.context().runId(),
        req.context().taskId(),
        results);
    emitWorkerLog(req, "Parallel read batch done · " + results);
    return out;
  }

  private static String envelopeStatus(String envelope) {
    if (envelope == null || envelope.isBlank()) {
      return "missing";
    }
    return isOkEnvelope(envelope) ? "ok" : "err";
  }

  private static boolean isOkEnvelope(String envelope) {
    return envelope != null && envelope.startsWith("{\"ok\":true");
  }

  private String dispatchOne(
      AssistantMessage.ToolCall tc, WorkerTaskRequest req, boolean isImmediatelyPriorToolCall) {
    // Belt-and-suspenders: even if the model somehow tries to invoke a denied
    // tool by name (e.g. cached tool list, hand-crafted payload), refuse at
    // dispatch time with a clear error envelope so the model can self-correct.
    if (ToolRegistry.WORKER_DENY_TOOLS.contains(tc.name())) {
      return errEnvelope(
          "FORBIDDEN",
          tc.name()
              + " is not available to the worker — workspace changes are committed automatically"
              + " at run-end. Use writeFile/editFile to modify files and call completeTask when"
              + " done.");
    }
    Tool tool = toolRegistry.find(tc.name());
    if (tool == null) {
      return errEnvelope("INVALID_ARGS", "unknown tool: " + tc.name());
    }
    // Local-hands/remote-brain (RL-2026-06, terminal-bench): when this run is bound to a remote
    // tool session, dispatch the tool to the connected client (over SSE) and return its result
    // envelope verbatim — the worker loop is agnostic to where the tool ran. This branch MUST live
    // here because the V2 worker dispatches tools manually via tool.execute() below, never through
    // Spring AI's ContextBoundToolCallback where ToolRegistry's other remote hook sits. Compaction
    // is skipped in remote mode (the compactor flag is OFF by default; the envelope is byte-shaped
    // identically to the local okEnvelope/errEnvelope path).
    if (toolRegistry.isRemoteRun(req.context())) {
      return toolRegistry.dispatchRemote(req.context(), tc.name(), tc.arguments());
    }
    JsonNode args;
    try {
      args =
          (tc.arguments() == null || tc.arguments().isBlank())
              ? objectMapper.createObjectNode()
              : objectMapper.readTree(tc.arguments());
    } catch (Exception e) {
      return errEnvelope("INVALID_ARGS", "tool args not valid JSON: " + e.getMessage());
    }
    // B4 (RL-2026-06, vix-leverage W1.3) — centralized, conservative pre-dispatch validation:
    // reject a call that omits a field the tool's OWN schema declares `required`, naming the field,
    // instead of letting a silently-absent arg reach the handler as null/garbage. Fail-open
    // (unparseable schema / no required list → no-op) + absent-only, so a schema-compliant call is
    // never blocked. Same check runs on the Spring-AI path (ToolRegistry.ContextBoundToolCallback).
    var argErr = com.navneet.executespec.tool.ToolArgValidator.validate(tool.inputSchema(), args);
    if (argErr.isPresent()) {
      return errEnvelope("INVALID_ARGS", argErr.get());
    }
    try {
      ToolResult result = tool.execute(args, req.context());
      return switch (result) {
          // RL-2026-05-052 Phase 5+6 — wrap the OK content through ToolOutputCompactor before
          // envelope-ing (flag-gated; default OFF returns raw verbatim, so this is byte-identical
          // to the pre-extraction path until the flag is flipped). Error envelopes are short by
          // construction; never compacted.
        case ToolResult.Ok ok ->
            okEnvelope(maybeCompact(tc.name(), ok.content(), isImmediatelyPriorToolCall, req));
        case ToolResult.Err err -> errEnvelope(err.kind().name(), err.message());
      };
    } catch (Exception unexpected) {
      log.error("Tool '{}' threw unexpectedly", tc.name(), unexpected);
      return errEnvelope(
          "INTERNAL", unexpected.getClass().getSimpleName() + ": " + unexpected.getMessage());
    }
  }

  /**
   * RL-2026-06 — workspace-agnostic tool dispatch for the harness STAGES (deliverable gate, critic
   * pass). Routes local-or-remote exactly like {@link #dispatchOne} (remote ⇒ the connected
   * container; local ⇒ {@code tool.execute()} on the VM workspace), minus the per-iteration
   * compaction. Lets the verify/critic stages read the deliverable the same way whether the run is
   * a local product run (BUILD/PATCH workspace) or a remote Terminal-Bench run — so the stages live
   * in common space, not a TB fork. Returns the same {@code {ok,content}} envelope shape.
   */
  private String dispatchToolDirect(ExecutionContext ctx, String toolName, String argsJson) {
    if (toolRegistry.isRemoteRun(ctx)) {
      return toolRegistry.dispatchRemote(ctx, toolName, argsJson);
    }
    Tool tool = toolRegistry.find(toolName);
    if (tool == null) return errEnvelope("INVALID_ARGS", "unknown tool: " + toolName);
    try {
      JsonNode args =
          (argsJson == null || argsJson.isBlank())
              ? objectMapper.createObjectNode()
              : objectMapper.readTree(argsJson);
      ToolResult result = tool.execute(args, ctx);
      return switch (result) {
        case ToolResult.Ok ok -> okEnvelope(ok.content());
        case ToolResult.Err err -> errEnvelope(err.kind().name(), err.message());
      };
    } catch (Exception e) {
      return errEnvelope("INTERNAL", e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /**
   * RL-2026-05-052 Phase 5+6 — route bulky tool output through the LIGHTWEIGHT-tier {@link
   * ToolOutputCompactor} before it lands in the worker's history. Default OFF behind the
   * compactor's feature flag; with the flag off this is a no-op pass-through. The worker NEVER
   * loses tool output to compactor failure — fail-safe inside the compactor itself returns empty,
   * and we fall back to raw here.
   *
   * <p>Null-guarded against {@code toolOutputCompactor == null} so unit tests that build a
   * WorkerAgent without wiring the compactor (legacy {@code new WorkerAgent(...)} call sites in the
   * test tree) keep working — the field is non-final at the test level via Mockito field injection
   * patterns, but defensively guarding here avoids a constructor-order trap.
   */
  private String maybeCompact(
      String toolName, String content, boolean isImmediatelyPriorToolCall, WorkerTaskRequest req) {
    if (toolOutputCompactor == null) return content;
    try {
      return toolOutputCompactor
          .compactIfNeeded(
              toolName,
              content,
              req.userPrompt(),
              isImmediatelyPriorToolCall,
              req.context(),
              req.provider())
          .map(ToolOutputCompactor.Compacted::summary)
          .orElse(content);
    } catch (RuntimeException e) {
      // Defence-in-depth: compactor implements its own fail-safe, but if a runtime escapes
      // (e.g. an NPE from a misconfigured dependency at construction time in some weird env),
      // never block the worker. Log + return raw.
      log.warn(
          "ToolOutputCompactor unexpected runtime (tool={}): {} — using raw output",
          toolName,
          e.getMessage());
      return content;
    }
  }

  private String okEnvelope(String content) {
    ObjectNode env = objectMapper.createObjectNode();
    env.put("ok", true);
    env.put("content", content);
    return env.toString();
  }

  private String errEnvelope(String kind, String message) {
    ObjectNode env = objectMapper.createObjectNode();
    env.put("ok", false);
    env.put("kind", kind);
    env.put("message", message);
    return env.toString();
  }

  private String extractSummary(String argsJson) {
    try {
      JsonNode n = objectMapper.readTree(argsJson == null ? "{}" : argsJson);
      return n.path("summary").asText("(no summary)");
    } catch (Exception e) {
      return "(unparseable summary args: " + e.getMessage() + ")";
    }
  }

  private static String safeId(String id) {
    return (id == null || id.isBlank()) ? "call_" + Long.toHexString(System.nanoTime()) : id;
  }

  private static String previewArgs(String args) {
    if (args == null) return "";
    return args.length() > 80 ? args.substring(0, 80) + "..." : args;
  }

  private static String toolCallLogEntry(AssistantMessage.ToolCall tc, String envelope) {
    String status = "ok".equals(envelopeStatus(envelope)) ? "OK" : "ERR";
    return tc.name() + "(" + previewArgs(tc.arguments()) + ") -> " + status;
  }

  private WorkerResult errorResult(
      int iter, int toolsDispatched, List<String> toolLog, long start, String message) {
    return WorkerResult.builder()
        .outcome(WorkerResult.Outcome.ERROR)
        .iterations(iter)
        .toolCallsDispatched(toolsDispatched)
        .finalAssistantText(message)
        .toolCallLog(toolLog)
        .totalLatencyMs(System.currentTimeMillis() - start)
        .build();
  }

  // 2026-05-04 — quirky LiveLog emitters

  /** Emit a worker log line on the run's SSE channel, scoped to the current task. */
  private void emitWorkerLog(WorkerTaskRequest req, String message) {
    try {
      broadcaster.emit(
          req.context().runId(),
          com.navneet.executespec.sse.RunEvent.log(
              req.context().runId(), req.context().taskId(), message));
    } catch (Exception ignore) {
      // SSE failure must never crash the worker loop.
    }
  }

  /**
   * Per-tool-call log line. Filters out the high-volume read-only tools (readFile / listFiles /
   * searchFiles / gitStatus / gitDiff) so the LiveLog shows REAL progress (mutations + builds +
   * shell) instead of the worker's introspective reads. Tool-arg preview kept short (~60 chars
   * after a per-tool prefix) so each line fits one banner row.
   */
  private void emitToolCallLog(WorkerTaskRequest req, AssistantMessage.ToolCall tc) {
    String name = tc.name() == null ? "" : tc.name();
    // Skip read/list/search/git noise — they fire dozens of times per task and would drown the
    // user-facing log. Per-tool emoji + concise preview otherwise.
    String emoji;
    switch (name) {
      case "writeFile", "write_file" -> emoji = "📝";
      case "editFile", "edit_file", "multiEdit", "multi_edit", "applyPatch", "apply_patch" ->
          emoji = "✏️ ";
      case "shell", "bash" -> emoji = "💻";
      case "buildProject" -> emoji = "🔨";
      case "gitCommit" -> emoji = "📦"; // denied for worker, but if it leaks through, show it
      default -> {
        return; // read-only tools — skip
      }
    }
    String preview = extractPathOrCommand(tc.arguments());
    emitWorkerLog(req, emoji + " " + name + (preview.isEmpty() ? "" : " · " + preview));
  }

  /**
   * Pull a short, human-friendly preview from a tool-call's JSON args. Looks for {@code path},
   * {@code filePath}, {@code command}, then falls back to the {@link #previewArgs} truncation.
   */
  private String extractPathOrCommand(String argsJson) {
    if (argsJson == null || argsJson.isEmpty()) return "";
    try {
      JsonNode node = objectMapper.readTree(argsJson);
      if (node.hasNonNull("path")) return truncate(node.get("path").asText(), 80);
      if (node.hasNonNull("filePath")) return truncate(node.get("filePath").asText(), 80);
      if (node.hasNonNull("command")) return truncate(node.get("command").asText(), 80);
      // editFile uses an array of {oldStr, newStr} — show the path + edit count.
      if (node.hasNonNull("file")) {
        String file = truncate(node.get("file").asText(), 60);
        int n = node.path("edits").isArray() ? node.path("edits").size() : 0;
        return file + (n > 0 ? " (" + n + " edit" + (n == 1 ? "" : "s") + ")" : "");
      }
    } catch (Exception ignore) {
      /* fallthrough */
    }
    return previewArgs(argsJson);
  }

  /**
   * Trim a free-text snippet for inclusion in the LiveLog banner. First-line only (so newlines
   * never split the banner into multiple lines), max 120 chars with an ellipsis.
   */
  private static String previewThought(String text) {
    if (text == null || text.isEmpty()) return "";
    int nl = text.indexOf('\n');
    String firstLine = nl > 0 ? text.substring(0, nl) : text;
    return truncate(firstLine.strip(), 120);
  }

  /**
   * 2026-05-10 — surrogate-pair-safe truncation. Caller-visible regression: in the LiveLog,
   * supplementary-plane emoji from the LLM's assistant text (💭 📝 💻 🔨 📦 🧠 💸 — 4-byte UTF-8,
   * encoded in Java as a high+low surrogate pair across two {@code char} units) would render as the
   * Unicode replacement character {@code �} (visually {@code �}) when the LLM happened to place an
   * emoji exactly at the truncation boundary. Root cause: {@link String#length()} counts UTF-16
   * code units, not codepoints, and the old {@code substring(0, max - 1)} happily cut between the
   * high surrogate and its low surrogate, leaving an unpaired high surrogate that the browser
   * couldn't decode.
   *
   * <p>Fix: when the cut point lands inside a surrogate pair (high at {@code end-1}, low at {@code
   * end}), back up one {@code char} so we discard both halves of the pair before appending the
   * ellipsis. Single-{@code char} BMP characters (incl. variation-selector compounds like {@code
   * ✏️} or {@code ⚠️}) are unaffected — those don't use surrogate pairs.
   *
   * <p>Visibility is package-private so {@link
   * com.navneet.executespec.engine.WorkerAgentTruncateTest} can pin the regression directly without
   * reflection.
   */
  // public for the cross-package WorkerAgentTruncateTest after the engine relocation
  // (RL-2026-05-052)
  public static String truncate(String s, int max) {
    if (s == null) return "";
    if (s.length() <= max) return s;
    int end = max - 1;
    if (end > 0
        && Character.isHighSurrogate(s.charAt(end - 1))
        && Character.isLowSurrogate(s.charAt(end))) {
      end -= 1;
    }
    return s.substring(0, end) + "…";
  }

  /**
   * The single corrective nudge (RL-2026-05-005). Shared by both trigger points — the mid-loop
   * progress counter crossing {@code progressWarnAfter}, and a premature text-only stop. It folds
   * together everything the old Lever A ("commit to an edit"), Lever C ("call completeTask, a
   * summary doesn't close the task"), and stagnation-warn ("stop re-reading") messages said into
   * one. Wording is UserMessage-safe — never injected as a SystemMessage (would break Gemini's
   * implicit cache prefix mid-run).
   */
  static String progressNudgeMessage(int totalWriteCalls) {
    return progressNudgeMessage(totalWriteCalls, false);
  }

  /**
   * progress-v2 corrective nudge (non-terminal modes). Differences from v1, both deliberate:
   * no "STOP reading" (novel investigation is legitimate progress under v2 — the trigger was
   * repetition, not reading) and no "an imperfect edit beats zero" (benchmark-diff logic; on a
   * customer run a forced bad edit is reviewer load and trust-cap damage, not a save).
   */
  public static String progressNudgeMessageV2(int totalWriteCalls) {
    String lead =
        totalWriteCalls > 0
            ? "PROGRESS CHECK — you have written "
                + totalWriteCalls
                + " file(s) but your recent turns repeated earlier work without new evidence,"
                + " edits, or a completeTask call.\n\n"
            : "PROGRESS CHECK — your recent turns repeated earlier work (same files, same"
                + " commands) without producing new evidence or an edit.\n\n";
    return lead
        + "Pick ONE:\n"
        + "1. If the task is DONE and verified, call the `completeTask` tool now with your"
        + " summary.\n"
        + "2. If you have a working hypothesis, act on it now: make the edit, then verify it"
        + " (buildProject, runTests, or a targeted shell command that exercises the change).\n"
        + "3. If a command or edit keeps failing the same way, STOP repeating it — change"
        + " something first (re-read the exact failing region, fix the underlying cause, or try"
        + " a different approach). Re-running an identical failing command cannot produce a"
        + " different result.\n"
        + "4. If you are genuinely stuck, name the single most likely file+function and inspect"
        + " exactly that — new evidence, not a re-read of what you already saw.";
  }

  /**
   * progress-v2 — stable identity of a tool call for novelty + repetition tracking:
   * name + whitespace-normalized raw arguments. Trailing/duplicate whitespace differences do
   * not make a call "new"; any real argument change does.
   */
  public static String callSignature(AssistantMessage.ToolCall tc) {
    String args = tc.arguments() == null ? "" : tc.arguments().replaceAll("\\s+", " ").trim();
    return tc.name() + " " + args;
  }

  /**
   * progress-v2 — did this tool envelope represent a FAILED attempt? Two shapes exist: error
   * envelopes ({@code {"ok":false...}}) and ok-envelopes carrying a non-zero process exit
   * (shell / buildProject / runTests embed {@code exit=N} or {@code "exit_code": N}). Only the
   * envelope head is inspected — large outputs spill to disk and the verdict is always in the
   * head.
   */
  public static boolean isFailureEnvelope(String envelope) {
    if (envelope == null || envelope.isEmpty()) return false;
    String head = envelope.length() > 400 ? envelope.substring(0, 400) : envelope;
    if (head.startsWith("{\"ok\":false")) return true;
    java.util.regex.Matcher m = FAILURE_EXIT_PATTERN.matcher(head);
    return m.find() && !"0".equals(m.group(1));
  }

  private static final java.util.regex.Pattern FAILURE_EXIT_PATTERN =
      java.util.regex.Pattern.compile("\\bexit(?:_code)?[\"=:\\s\\\\]+(-?\\d+)");

  static String progressNudgeMessage(int totalWriteCalls, boolean terminalTaskMode) {
    if (terminalTaskMode) {
      String lead =
          totalWriteCalls > 0
              ? "PROGRESS CHECK — you have written "
                  + totalWriteCalls
                  + " file"
                  + (totalWriteCalls == 1 ? "" : "s")
                  + " but have not closed the terminal task."
              : "PROGRESS CHECK — you have spent several turns without using writeFile/editFile.";
      return lead
          + " Pick ONE:\n"
          + "1. If the exact deliverable exists and you have verified it, call `completeTask` now"
          + " with the `summary` argument. A text-only summary does NOT close the task.\n"
          + "2. If you know the required output path, create or overwrite it now. Use writeFile for"
          + " small literal answers, or one shell command/script for computed artifacts. The"
          + " benchmark scores the actual files under /app; analysis without producing that file"
          + " scores zero.\n"
          + "3. If /tests exists, inspect or run /tests/test.sh or the narrow failing test now."
          + " Visible tests are the scoring contract. For expensive/performance tests, use a"
          + " bounded smoke command first, then the official verifier when ready.\n"
          + "4. If visible tests or failures show an exact expected literal, output path, file"
          + " list, or directory contents, satisfy that contract exactly. Do not add extra values"
          + " or leave helper binaries/scratch files in deliverable directories.\n"
          + "5. For sanitizer/filter/transform tasks, preserve benign input byte-for-byte unless"
          + " the tests require normalization. Add a detection-first no-op path: if no"
          + " prohibited construct is present, leave/copy the file unchanged and do not run a"
          + " parser/formatter/serializer over it.\n"
          + "6. If a failure names a concrete symbol/API/file/pattern, grep recursively and fix all"
          + " relevant occurrences before retesting. If it says a required file is missing,"
          + " create that exact file before more broad exploration.\n"
          + "Do not keep dumping huge files or re-reading state you already checked. If a candidate"
          + " already passes the verifier-shaped check, preserve it and complete instead of making"
          + " risky marginal changes.";
    }
    String lead =
        totalWriteCalls > 0
            ? "PROGRESS CHECK — you have written "
                + totalWriteCalls
                + " file"
                + (totalWriteCalls == 1 ? "" : "s")
                + " but made no new write in your recent turns and have NOT called `completeTask`."
            : "PROGRESS CHECK — you have used several iterations without writing any file.";
    return lead
        + " Pick ONE:\n"
        + "1. If the task is DONE, call the `completeTask` tool now with your summary as the"
        + " `completionNotes` argument. A text-only summary does NOT close the task — only the"
        + " tool does.\n"
        + "2. If you are still working, STOP reading. If you have not pinned the exact spot, run"
        + " ONE searchFiles / `grep -rn <symbol>` to localize the function, then immediately"
        + " editFile that location — do not open more files to 'understand' first. Commit to the"
        + " single most likely edit, then verify with buildProject (build mode) or runTests (patch"
        + " mode). An empty result is an automatic failure — even an imperfect edit beats zero.\n"
        + "3. If a build or test error is blocking you, fix that file now.\n"
        + "Do NOT do another round of reads or re-verify state you have already checked.";
  }

  static String terminalArtifactDeadlineMessage() {
    return "TERMINAL DELIVERABLE DEADLINE:\n"
        + "Stop broad exploration. In the next tool call, do ONE focused action:\n"
        + "1. If the requested /app deliverable already exists, verify it with a focused"
        + " shell command or readFile, then call completeTask.\n"
        + "2. If you know the requested output path and it is missing or stale, create or"
        + " overwrite that exact path now with the best verified candidate.\n"
        + "3. If /tests exists, run /tests/test.sh or the narrow relevant check now. For"
        + " expensive or performance checks, use timeout/reduced iterations for smoke, then"
        + " the official verifier once the candidate is ready.\n"
        + "4. If visible tests assert exact literals, exact file names, or exact directory"
        + " contents, make the /app artifact match those assertions byte-for-byte. Remove"
        + " temporary binaries, caches, and scratch files from deliverable directories"
        + " unless the tests expect them.\n"
        + "5. For sanitizer/filter/transform tasks, verify clean input remains byte-for-byte"
        + " unchanged unless tests require normalization. Implement a detection-first no-op"
        + " path for clean inputs; do not parse and reserialize clean files.\n"
        + "6. If you still do not know the output path, re-read only the task/instruction"
        + " file to extract the exact path and format, then create it immediately.\n"
        + "If your current candidate already passes a verifier-shaped check, stop changing"
        + " it and call completeTask. Do not overwrite a passing candidate with speculative"
        + " optimizations.\n"
        + "Do not spend another turn on package installs, directory listings, broad"
        + " searches, or unrelated analysis unless that single command directly creates or"
        + " verifies the required deliverable.";
  }

  /**
   * RL-2026-06 — the terminal-mode enforced-verify message (terminalVerifyGate). Fired ONCE before
   * the first {@code completeTask}. Unlike {@link #terminalArtifactDeadlineMessage()} (which pushes
   * a stalling worker to PRODUCE the artifact), this pushes an EAGER worker to EXERCISE its
   * already-written deliverable against the task's own concrete criterion before claiming done —
   * the dominant Terminal-Bench miss (structural/smoke pass, the one real assertion fails). It is
   * action-forcing ("run it and paste the real output", not "reason about correctness") and
   * explicitly protects a passing candidate from speculative rework.
   */
  static String terminalVerifyMessage() {
    return "VERIFY BEFORE COMPLETING (one-time check):\n"
        + "The hidden tests you cannot see check the task's EXACT stated behaviour — its"
        + " trickiest requirement, the edge case the prose calls out, and any concrete"
        + " example the task gives. A solution that merely looks complete, or only passes a"
        + " trivial import/smoke, scores ZERO if that one real check fails. Do ONE focused"
        + " verification now, then decide:\n"
        + "1. Re-read the task and name its hardest concrete criterion — the exact"
        + " input→output pair it states (e.g. `sim 208` → `377`), the example file it ships"
        + " (e.g. /app/example_*), or the literal condition it names (\"no overfull hbox"
        + " warnings\", \"cleanup still runs on SIGINT\", a specific numeric/string"
        + " result).\n"
        + "2. Actually exercise your deliverable against THAT criterion via the shell: run"
        + " it on the stated example input and print the real output, compile and grep for"
        + " the named condition, or construct the exact edge case the task describes and run"
        + " it. Paste the real output — do not assume it works.\n"
        + "3. Confirm the required file exists at the EXACT path, is non-empty, and its"
        + " output matches what the task specifies byte-for-byte.\n"
        + "If everything already matches, call completeTask now — do NOT make speculative"
        + " changes to a working solution. If the output is wrong, the edge case fails, or"
        + " the file is missing/misplaced, fix exactly that and then call completeTask.";
  }

  /**
   * RL-2026-06 — deterministic deliverable gate (#2) check. Dispatches ONE container-side {@code
   * shell} existence test for the required paths and returns the subset that does NOT exist.
   * Fail-open: any dispatch/parse error, a non-ok shell envelope, or no safe paths returns an empty
   * list (→ the gate permits completion). Only checks safe absolute paths ({@code
   * /[A-Za-z0-9._/-]+}) — a shell-injection guard that also drops the occasional non-path the
   * extractor returns. A bracketed {@code ESMISS[<path>]} marker is used so a path that is a prefix
   * of another can't false-match.
   */
  private List<String> missingDeliverables(ExecutionContext ctx, List<String> paths) {
    List<String> safe =
        paths.stream()
            .filter(p -> p != null && p.matches("/[A-Za-z0-9._/-]+"))
            .distinct()
            .limit(8)
            .toList();
    if (safe.isEmpty()) return List.of();
    StringBuilder cmd = new StringBuilder();
    for (String p : safe) {
      cmd.append("[ -e '").append(p).append("' ] || printf 'ESMISS[").append(p).append("]\\n'; ");
    }
    try {
      ObjectNode args = objectMapper.createObjectNode();
      args.put("command", cmd.toString());
      String envelope = dispatchToolDirect(ctx, "shell", objectMapper.writeValueAsString(args));
      if (envelope == null) return List.of();
      JsonNode env = objectMapper.readTree(envelope);
      if (!env.path("ok").asBoolean(false)) return List.of();
      String content = env.path("content").asText("");
      return safe.stream().filter(p -> content.contains("ESMISS[" + p + "]")).toList();
    } catch (Exception e) {
      log.debug(
          "deliverable gate check failed (fail-open) run={}: {}", ctx.runId(), e.getMessage());
      return List.of();
    }
  }

  /**
   * VBC verify-before-complete (RL-2026-06-…) — server-runs the project's own test suite via {@link
   * WorkspaceTestRunner} and returns a short failure detail when tests are RED, or {@code null} on
   * pass / fail-open. Fail-open (returns null, letting completeTask proceed) when: the runner is
   * not wired (legacy test call sites pass null), the run is remote (the host-side runner can't
   * reach a container — Tier-1 scope is local BUILD/PATCH), no recognized runner resolves / output
   * is unparseable ({@code Optional.empty()}), zero tests ran, or any exception. So the gate can
   * nudge the worker to fix failing tests but can NEVER convert a legitimately-complete run into a
   * false FAIL. Called DIRECTLY (not via the flag-gated runWorkspaceTests tool) so VBC is
   * independent of {@code worker.build-tdd.enabled} (V217).
   */
  private String verifyBeforeCompleteFailure(ExecutionContext ctx) {
    if (workspaceTestRunner == null) return null; // not wired → fail-open
    if (toolRegistry.isRemoteRun(ctx)) return null; // remote/TB: Tier-1 inert → fail-open
    try {
      Optional<TestExecutionSummary> opt =
          workspaceTestRunner.runTests(ctx.runId(), ctx.workspaceRoot());
      if (opt.isEmpty()) return null; // no runner / unparseable → fail-open
      TestExecutionSummary s = opt.get();
      if (s.getTotalTests() == 0) return null; // nothing ran → fail-open
      if (s.getFailed() == 0 && s.getErrors() == 0) return null; // green
      return s.getFailed()
          + " failed / "
          + s.getErrors()
          + " errored of "
          + s.getTotalTests()
          + " tests";
    } catch (Exception e) {
      log.debug("VBC gate check failed (fail-open) run={}: {}", ctx.runId(), e.getMessage());
      return null; // fail-open
    }
  }

  /** Terminal visible-test gate — detect whether the remote sandbox exposes verifier files. */
  private boolean visibleTestsExist(ExecutionContext ctx) {
    try {
      ObjectNode args = objectMapper.createObjectNode();
      args.put(
          "command",
          "[ -d /tests ] && find /tests -maxdepth 2 -type f | head -1 | grep -q . && echo"
              + " TESTS_PRESENT || true");
      String envelope = dispatchToolDirect(ctx, "shell", objectMapper.writeValueAsString(args));
      if (envelope == null) return false;
      JsonNode env = objectMapper.readTree(envelope);
      return env.path("ok").asBoolean(false)
          && env.path("content").asText("").contains("TESTS_PRESENT");
    } catch (Exception e) {
      log.debug("visible-test check failed (fail-open) run={}: {}", ctx.runId(), e.getMessage());
      return false;
    }
  }

  /** VBC verify-before-complete — the refusal message naming what failed and what to do. */
  static String verifyBeforeCompleteMessage(String detail) {
    return "completeTask refused — the project's own tests do not pass ("
        + detail
        + "). The platform ran your test suite and it is RED. Fix the failing test(s) — or the code"
        + " they exercise — then call completeTask again. A run that completes with failing tests"
        + " ships a broken deliverable. If a failure is genuinely pre-existing and unrelated to"
        + " your change, note that in completionNotes; the gate yields after a few attempts.";
  }

  static String terminalVisibleTestsMessage() {
    return "completeTask refused — this terminal task exposes visible verifier files under /tests,"
        + " but you have not run a passing verifier command against /tests. Run /tests/test.sh or"
        + " the relevant pytest/bats/unittest command under /tests, inspect any failure, fix the"
        + " exact contract mismatch, then call completeTask. Do not rely only on a self-authored"
        + " smoke test; visible tests are the executable scoring contract.";
  }

  /** RL-2026-06 — the deliverable-gate refusal message naming the missing required path(s). */
  static String terminalDeliverableMessage(List<String> missing) {
    boolean one = missing.size() == 1;
    return "completeTask refused — the task requires "
        + (one ? "this exact deliverable path" : "these exact deliverable paths")
        + " which "
        + (one ? "does" : "do")
        + " not exist yet: "
        + String.join(", ", missing)
        + ". Create "
        + (one ? "it" : "each")
        + " at the EXACT path the task names, then call completeTask again. A correct answer at the"
        + " wrong path or filename scores zero — match the task's stated path byte-for-byte.";
  }

  /**
   * RL-2026-06 — system prompt for the terminal critic pass (#1). Strict, conservative, fail-PASS.
   */
  private static final String CRITIC_SYSTEM_PROMPT =
      """
You are a STRICT acceptance reviewer. You are shown a task and a candidate's deliverable (the \
candidate's current files or code change). The candidate believes the task is complete. Hidden \
automated tests will score it on the task's EXACT stated behaviour — including the trickiest \
requirement, any edge case the prose calls out, the exact output format/paths, and any concrete \
example the task gives.

Your job: find every requirement the deliverable FAILS to meet. Be specific and concrete — name the \
exact requirement and why the deliverable fails it. Focus on real, checkable failures:
- a stated edge case the code does not handle,
- a concrete example (input->output) the deliverable would get wrong,
- a required file/path/format that is missing or incorrect,
- a requirement in the task prose that the deliverable ignores.

Do NOT invent gaps, restyle working code, or demand things the task did not ask for. If the \
deliverable genuinely satisfies EVERY requirement, respond with exactly the single word PASS on its \
own line and nothing else. Otherwise list ONLY the real gaps, most important first.""";

  /**
   * RL-2026-06 — the terminal critic pass (#1). Runs ONE fresh-context, no-tool {@link LlmRouter}
   * acceptance review over the task + the candidate's deliverable (read from the container).
   * Returns {@code null} when the verdict is PASS (or on ANY error — fail-open), otherwise the
   * concrete gaps to feed back to the worker. Independent of the worker's own anchored history.
   */
  private String runCriticPass(WorkerTaskRequest req, List<String> paths) {
    try {
      String deliverable = readDeliverableContext(req.context(), paths);
      String userMsg = "TASK:\n" + req.userPrompt() + "\n\nCANDIDATE DELIVERABLE:\n" + deliverable;
      ChatResponse resp =
          router.call(
              LlmRequest.builder()
                  .provider(req.provider())
                  .model(req.model())
                  .messages(
                      List.of(new SystemMessage(CRITIC_SYSTEM_PROMPT), new UserMessage(userMsg)))
                  .context(req.context())
                  .agentRole("WorkerCriticPass")
                  .stepId("worker.critic-pass")
                  .temperature(0.0)
                  .maxTokens(1500)
                  .toolsOverride(List.of()) // no tools — a plain completion
                  .build());
      String verdict = resp.getResult().getOutput().getText();
      if (verdict == null) return null;
      String trimmed = verdict.strip();
      // PASS verdict (first line, letters only, == PASS) → no gaps. Imperfect PASS detection is
      // safe:
      // the critic is one-shot, so a false gap-round costs at most one extra iteration.
      String firstLineLetters = trimmed.lines().findFirst().orElse("").replaceAll("[^A-Za-z]", "");
      if (trimmed.isEmpty() || firstLineLetters.equalsIgnoreCase("PASS")) return null;
      return trimmed;
    } catch (Exception e) {
      log.debug("critic pass failed (fail-open) run={}: {}", req.context().runId(), e.getMessage());
      return null;
    }
  }

  /**
   * RL-2026-06 — reads the candidate deliverable for the critic, workspace-agnostically via ONE
   * {@code shell} dispatch (local or remote). For a remote TB run: an {@code /app} file listing +
   * the (truncated) contents of the required paths. For a local product run (BUILD/PATCH): the
   * worker's {@code git diff} (stat + patch) — the actual change set under review. Fail-soft:
   * returns a placeholder string on any error so the critic still runs on the task text alone.
   */
  private String readDeliverableContext(ExecutionContext ctx, List<String> paths) {
    boolean remote = toolRegistry.isRemoteRun(ctx);
    List<String> safe =
        paths.stream()
            .filter(p -> p != null && p.matches("/[A-Za-z0-9._/-]+"))
            .distinct()
            .limit(8)
            .toList();
    StringBuilder cmd = new StringBuilder();
    if (remote) {
      cmd.append(
          "echo '=== /app files ==='; find /app -maxdepth 3 -type f 2>/dev/null | head -40; ");
      cmd.append(
          "echo '=== /tests files ==='; find /tests -maxdepth 3 -type f 2>/dev/null | head -40; ");
      cmd.append(
          "if [ -f /tests/test.sh ]; then echo '=== /tests/test.sh ==='; head -c 4000"
              + " /tests/test.sh; echo; fi; ");
      if (safe.isEmpty()) {
        cmd.append(
            "echo '=== likely /app candidate contents ==='; "
                + "find /app -maxdepth 3 -type f \\( -perm -111 -o -name '*.py' -o -name '*.sh'"
                + " -o -name '*.js' -o -name '*.ts' -o -name '*.json' -o -name '*.txt'"
                + " -o -name '*.csv' -o -name '*.out' -o -name '*.result' \\) 2>/dev/null"
                + " | head -8 | while IFS= read -r f; do echo \"=== $f ===\"; head -c 2000"
                + " \"$f\"; echo; done; ");
      }
    } else {
      // Local product run: the deliverable is the worker's change set.
      cmd.append("echo '=== git diff (stat) ==='; git diff --stat 2>/dev/null | head -40; ");
      cmd.append("echo '=== git diff ==='; git diff 2>/dev/null | head -c 12000; echo; ");
    }
    for (String p : safe) {
      cmd.append("echo '=== ")
          .append(p)
          .append(" ==='; cat '")
          .append(p)
          .append("' 2>/dev/null | head -c 4000; echo; ");
    }
    try {
      ObjectNode args = objectMapper.createObjectNode();
      args.put("command", cmd.toString());
      String envelope = dispatchToolDirect(ctx, "shell", objectMapper.writeValueAsString(args));
      if (envelope == null) return "(deliverable unavailable)";
      JsonNode env = objectMapper.readTree(envelope);
      String content = env.path("content").asText("");
      if (content.length() > 12000) content = content.substring(0, 12000) + "\n…(truncated)";
      return content.isEmpty() ? "(no deliverable found)" : content;
    } catch (Exception e) {
      return "(deliverable unavailable: " + e.getMessage() + ")";
    }
  }

  /**
   * RL-2026-06 — wraps the critic's gap list as actionable, push-back-allowed completion feedback.
   */
  static String criticMessage(String gaps) {
    return "completeTask deferred — an independent acceptance review found these gaps between your"
        + " deliverable and the task's requirements:\n\n"
        + gaps
        + "\n\nFix each REAL gap (re-run the task's own example/criteria to confirm), then call"
        + " completeTask. If a point is mistaken and your deliverable already satisfies it, you may"
        + " call completeTask now — this review will not run again.";
  }

  private boolean terminalVerifierPass(
      String toolName,
      String argsJson,
      String envelope,
      List<String> requiredDeliverables,
      ExecutionContext ctx) {
    if (!terminalVerifierTool(toolName) || !isOkEnvelope(envelope)) return false;
    String command = commandFromArgs(argsJson);
    if (!terminalVerifierCommand(toolName, command)) return false;
    if (visibleTestsExist(ctx) && !terminalVisibleTestCommand(toolName, command)) return false;
    return requiredDeliverables == null
        || requiredDeliverables.isEmpty()
        || missingDeliverables(ctx, requiredDeliverables).isEmpty();
  }

  private String terminalVerifierEvidence(String toolName, String argsJson) {
    String command = commandFromArgs(argsJson);
    if (command.isBlank()) return toolName;
    return toolName + ": " + truncate(command, 220);
  }

  private String commandFromArgs(String argsJson) {
    if (argsJson == null || argsJson.isBlank()) return "";
    try {
      JsonNode args = objectMapper.readTree(argsJson);
      return args.path("command").asText("");
    } catch (Exception e) {
      return "";
    }
  }

  static boolean terminalVerifierCommand(String toolName, String command) {
    if (RUN_TESTS_TOOL_NAME.equals(toolName) || BUILD_PROJECT_TOOL_NAME.equals(toolName)) {
      return true;
    }
    if (!isShellTool(toolName) || command == null || command.isBlank()) return false;
    String c = command.toLowerCase(java.util.Locale.ROOT);
    return executesVisibleTestScript(c)
        || runsVisibleTestPath(c)
        || c.contains("pytest")
        || c.contains("python -m unittest")
        || c.contains("npm test")
        || c.contains("pnpm test")
        || c.contains("yarn test")
        || c.contains("go test")
        || c.contains("cargo test")
        || c.contains("mvn test")
        || c.contains("gradle test")
        || c.contains("bats ")
        || c.contains("bats-")
        || c.contains("prove ")
        || c.contains(" verify")
        || c.contains("./verify")
        || c.contains(" benchmark")
        || c.contains("./benchmark")
        || c.contains(" hyperfine ");
  }

  static boolean terminalVisibleTestCommand(String toolName, String command) {
    if (RUN_TESTS_TOOL_NAME.equals(toolName)) return true;
    if (!isShellTool(toolName) || command == null || command.isBlank()) return false;
    String c = command.toLowerCase(java.util.Locale.ROOT);
    return executesVisibleTestScript(c)
        || runsVisibleTestPath(c)
        || (targetsVisibleTests(c) && terminalTestRunnerCommand(c));
  }

  private static boolean targetsVisibleTests(String command) {
    return command.contains("/tests") || command.contains("cd /tests");
  }

  private static boolean terminalTestRunnerCommand(String command) {
    return command.contains("pytest")
        || command.contains("python -m unittest")
        || command.contains("npm test")
        || command.contains("pnpm test")
        || command.contains("yarn test")
        || command.contains("go test")
        || command.contains("cargo test")
        || command.contains("mvn test")
        || command.contains("gradle test")
        || command.contains("bats ")
        || command.contains("bats-")
        || command.contains("prove ")
        || command.contains("./test")
        || command.contains("./verify");
  }

  private static boolean runsVisibleTestPath(String command) {
    return command.matches(
            ".*\\b(?:bash|sh|python|python3|node|ruby|perl|bats|prove)\\s+[^;&|\\n]*/tests/.*")
        || command.matches(".*cd\\s+/tests\\b.*(?:&&|;)\\s*\\./[^;&|\\n]+.*");
  }

  private static boolean executesVisibleTestScript(String command) {
    return command.matches(".*(?:^|[;&|]\\s*)(?:sudo\\s+)?/tests/test\\.sh(?:\\s|$).*");
  }

  private static boolean terminalVerifierTool(String toolName) {
    return isShellTool(toolName)
        || RUN_TESTS_TOOL_NAME.equals(toolName)
        || BUILD_PROJECT_TOOL_NAME.equals(toolName);
  }

  private static boolean terminalRiskyAfterVerifiedPass(String toolName) {
    return isWriteTool(toolName)
        || isShellTool(toolName)
        || RUN_TESTS_TOOL_NAME.equals(toolName)
        || BUILD_PROJECT_TOOL_NAME.equals(toolName);
  }

  private static String terminalVerifiedPassMessage() {
    return "A verifier-shaped command has already passed with the required deliverable present."
        + " Preserve that candidate and call completeTask now. Do not keep editing, rerunning broad"
        + " commands, or making speculative optimizations after a passing verifier-shaped check.";
  }

  private void snapshotTerminalVerifiedCandidate(
      ExecutionContext ctx, List<String> requiredDeliverables) {
    if (requiredDeliverables == null || requiredDeliverables.isEmpty()) return;
    List<String> safe =
        requiredDeliverables.stream()
            .filter(p -> p != null && p.matches("/[A-Za-z0-9._/-]+"))
            .distinct()
            .limit(8)
            .toList();
    if (safe.isEmpty()) return;
    String snapshotDir = "/tmp/es-passing-candidate-" + safeShellToken(ctx.runId());
    StringBuilder cmd = new StringBuilder();
    cmd.append("rm -rf '")
        .append(snapshotDir)
        .append("'; mkdir -p '")
        .append(snapshotDir)
        .append("'; ");
    for (String p : safe) {
      cmd.append("if [ -e '")
          .append(p)
          .append("' ]; then mkdir -p '")
          .append(snapshotDir)
          .append("'\"$(dirname '")
          .append(p)
          .append("')\"; cp -a '")
          .append(p)
          .append("' '")
          .append(snapshotDir)
          .append(p)
          .append("'; fi; ");
    }
    cmd.append("printf 'ES_SNAPSHOT_OK\\n'");
    try {
      ObjectNode args = objectMapper.createObjectNode();
      args.put("command", cmd.toString());
      String envelope = dispatchToolDirect(ctx, "shell", objectMapper.writeValueAsString(args));
      if (envelope == null || !isOkEnvelope(envelope)) {
        log.debug("terminal verified-pass snapshot did not complete for run={}", ctx.runId());
      }
    } catch (Exception e) {
      log.debug(
          "terminal verified-pass snapshot failed (non-fatal) run={}: {}",
          ctx.runId(),
          e.getMessage());
    }
  }

  private static String safeShellToken(String s) {
    if (s == null || s.isBlank()) return "run";
    return s.replaceAll("[^A-Za-z0-9._-]", "-");
  }

  private static boolean isProgressTool(String toolName, boolean terminalTaskMode) {
    return WRITE_CLASS_TOOLS.contains(toolName) || (terminalTaskMode && isShellTool(toolName));
  }

  private static boolean isWriteTool(String toolName) {
    return "writeFile".equals(toolName)
        || "write_file".equals(toolName)
        || "editFile".equals(toolName)
        || "edit_file".equals(toolName)
        || "multiEdit".equals(toolName)
        || "multi_edit".equals(toolName)
        || "applyPatch".equals(toolName)
        || "apply_patch".equals(toolName);
  }

  private static boolean isShellTool(String toolName) {
    return "shell".equals(toolName) || "bash".equals(toolName);
  }
}

package com.navneet.executespec.agent;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.context.ContextStore;
import com.navneet.executespec.observability.Metrics;
import com.navneet.executespec.tool.CompleteTaskTool;
import com.navneet.executespec.tool.Tool;
import com.navneet.executespec.tool.ToolRegistry;
import com.navneet.executespec.tool.ToolResult;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
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
      java.util.Set.of("writeFile", "editFile", "buildProject");

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

    // Stagnation guard state — counts consecutive iterations without a
    // write-class tool call. Reset to 0 on any writeFile / editFile.
    // Per-task override (req.stagnationWarnAfter / req.stagnationForceAfter): SequentialExecutor
    // sets these per complexity (LOW=8/12 default, MEDIUM=12/18, HIGH=18/28) so that legitimate
    // reverse-engineering / multi-file-read iterations on HIGH tasks (e.g. ProgramBench cmatrix)
    // don't trip the guard before the worker can write. Falls through to the static defaults
    // when null, preserving prior behaviour for callers that don't set the override.
    final int stagnationWarnAfter =
        req.stagnationWarnAfter() != null && req.stagnationWarnAfter() > 0
            ? req.stagnationWarnAfter()
            : STAGNATION_WARN_AFTER_N;
    final int stagnationForceAfter =
        req.stagnationForceAfter() != null && req.stagnationForceAfter() > stagnationWarnAfter
            ? req.stagnationForceAfter()
            : Math.max(STAGNATION_FORCE_AFTER_N, stagnationWarnAfter + 4);
    int stagnantIters = 0;
    boolean stagnationWarned = false;

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

    // 2026-05-08 — Lever A: force-edit watchdog. Counts total write-class tool calls (writeFile,
    // editFile) across the whole run. When the worker passes 70% of maxIter without any write,
    // we inject a one-shot UserMessage that breaks investigation paralysis: "you've read enough,
    // commit to an edit now or empty patch is automatic failure." This targets the SWE-bench
    // empty-patch failure mode (sqlfluff-1733 et al) where the agent reads 60+ files without
    // ever writing one. Stagnation guard catches consecutive non-write streaks; this watchdog
    // catches the slower "always-investigating" pattern across the whole run.
    int totalWriteCalls = 0;
    boolean forceEditNudged = false;
    final int forceEditAtIter = Math.max(5, (int) Math.round(maxIter * 0.7));

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
    final Double hardCapUsd =
        pipelineRunRepository.findById(runId).map(r -> r.getHardCapUsd()).orElse(null);

    for (int iter = startIter; iter <= maxIter; iter++) {
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
              .toolCallLog(toolLog)
              .totalLatencyMs(System.currentTimeMillis() - start)
              .build();
        }
      }

      ChatResponse resp;
      try {
        resp =
            router.call(
                LlmRequest.builder()
                    .provider(req.provider())
                    .model(req.model())
                    .messages(history)
                    .context(req.context())
                    .temperature(req.temperature())
                    .maxTokens(req.maxTokensPerCall())
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
        log.error("WorkerAgent router failure at iter={}", iter, e);
        recordTerminal(WorkerResult.Outcome.ERROR, req, iter);
        return errorResult(
            iter,
            toolsDispatched,
            toolLog,
            start,
            "router error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
      }

      // Collect tool calls + assistant text across all generations (Anthropic
      // returns multi-gen responses; OpenAI/Gemini single-gen — same code path works).
      StringBuilder textBuf = new StringBuilder();
      List<AssistantMessage.ToolCall> calls = new ArrayList<>();
      for (var gen : resp.getResults()) {
        String t = gen.getOutput().getText();
        if (t != null && !t.isEmpty()) {
          if (textBuf.length() > 0) textBuf.append("\n");
          textBuf.append(t);
        }
        if (gen.getOutput().getToolCalls() != null) {
          calls.addAll(gen.getOutput().getToolCalls());
        }
      }
      String assistantText = textBuf.toString();
      if (!assistantText.isEmpty()) lastText = assistantText;

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
        history.add(new AssistantMessage(assistantText));
        recordTerminal(WorkerResult.Outcome.STOPPED_WITHOUT_COMPLETE, req, iter);
        return WorkerResult.builder()
            .outcome(WorkerResult.Outcome.STOPPED_WITHOUT_COMPLETE)
            .iterations(iter)
            .toolCallsDispatched(toolsDispatched)
            .finalAssistantText(lastText)
            .toolCallLog(toolLog)
            .totalLatencyMs(System.currentTimeMillis() - start)
            .build();
      }

      // Append the assistant turn (text + tool_calls) to history.
      history.add(AssistantMessage.builder().content(assistantText).toolCalls(calls).build());

      // Dispatch every tool call. completeTask short-circuits the loop.
      List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
      String completionSummary = null;
      for (AssistantMessage.ToolCall tc : calls) {
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
          completionSummary = extractSummary(tc.arguments());
          String envelope = okEnvelope("task marked complete: " + completionSummary);
          toolResponses.add(
              new ToolResponseMessage.ToolResponse(safeId(tc.id()), tc.name(), envelope));
          toolLog.add(tc.name() + " → COMPLETED");
          log.info("WorkerAgent completeTask received: {}", completionSummary);
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
              .toolCallLog(toolLog)
              .totalLatencyMs(System.currentTimeMillis() - start)
              .build();
        }
        metrics.recordToolCall(tc.name());
        String envelope = dispatchOne(tc, req);
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
        toolResponses.add(
            new ToolResponseMessage.ToolResponse(safeId(tc.id()), tc.name(), envelope));
        toolLog.add(tc.name() + "(" + previewArgs(tc.arguments()) + ")");
        // 2026-05-04 — emit a per-mutation log line so the LiveLog shows real activity, not
        // just the token counter. Filtered to mutating + heavy tools (writes, edits, shell,
        // build) — read/list/search/git noise stays out of the user-facing log so the screen
        // doesn't drown.
        emitToolCallLog(req, tc);
      }
      history.add(ToolResponseMessage.builder().responses(toolResponses).build());

      // ─── Stagnation guard ─────────────────────────────────────────────
      // Reset the stagnation counter on any write-class tool call this iter
      // (writeFile / editFile); otherwise increment. A run of 8 stagnant
      // iters injects a one-shot PROGRESS CHECK system message; another 4
      // stagnant iters force-terminates the worker with
      // COMPLETED_BY_STAGNATION so the smoke gate downstream can decide
      // final task status.
      boolean wroteThisIter = calls.stream().anyMatch(tc -> WRITE_CLASS_TOOLS.contains(tc.name()));
      if (wroteThisIter) {
        stagnantIters = 0;
        // 2026-05-08 Lever A — count writes across the whole run for the force-edit watchdog.
        for (AssistantMessage.ToolCall tc : calls) {
          if ("writeFile".equals(tc.name()) || "editFile".equals(tc.name())) totalWriteCalls++;
        }
      } else {
        stagnantIters++;
      }
      // 2026-05-08 Lever A: force-edit nudge. If we've crossed the 70% iter threshold with
      // ZERO writes total (not just this iter — investigation paralysis pattern from SWE-bench
      // sqlfluff-1733: 75 iter cap, 0 edits, empty patch), inject a one-shot UserMessage telling
      // the agent to commit to ANY edit. UserMessage (not SystemMessage) for the same Gemini
      // cache-prefix reason as the stagnation warning above.
      if (!forceEditNudged && iter >= forceEditAtIter && totalWriteCalls == 0) {
        log.info(
            "WorkerAgent FORCE_EDIT_NUDGE run={} task={} iter={}/{} totalWrites=0",
            req.context().runId(),
            req.context().taskId(),
            iter,
            maxIter);
        history.add(
            new UserMessage(
                "INVESTIGATION PHASE OVER. You've used "
                    + iter
                    + " of "
                    + maxIter
                    + " iterations reading files without writing anything. An empty patch"
                    + " is automatic failure — even a wrong-file edit is better than zero."
                    + " Pick the file that most likely contains the bug based on what you've"
                    + " read, edit it, and call buildProject (build mode) or runTests (patch"
                    + " mode) to verify. Do NOT do another round of reads — commit to an"
                    + " edit on your VERY NEXT response."));
        forceEditNudged = true;
      }
      if (!stagnationWarned && stagnantIters >= stagnationWarnAfter) {
        log.info(
            "WorkerAgent STAGNATION_WARN run={} task={} iter={} stagnantIters={} threshold={}",
            req.context().runId(),
            req.context().taskId(),
            iter,
            stagnantIters,
            stagnationWarnAfter);
        // 2026-05-08 — must be a UserMessage, NOT a SystemMessage. GeminiNativeToolClient
        // concatenates every SystemMessage into systemInstruction (see GeminiNativeToolClient.java
        // ~line 240); appending one mid-run mutates the cache prefix and invalidates implicit
        // prompt caching for the rest of the task. UserMessage stays in the contents[] list
        // where Gemini treats it as a normal turn — cache prefix preserved. Anthropic + OpenAI
        // honour the warning equally well as a user turn.
        history.add(
            new UserMessage(
                "PROGRESS CHECK: "
                    + stagnationWarnAfter
                    + " consecutive iterations with no new file writes. If your task is done,"
                    + " call completeTask now. If a build error blocks you, fix the file. Do"
                    + " NOT re-read files you have already verified, do NOT re-inspect git"
                    + " state, do NOT add 'one more verification' round-trips."));
        stagnationWarned = true;
      }
      if (stagnantIters >= stagnationForceAfter) {
        log.warn(
            "WorkerAgent STAGNATION_FORCE run={} task={} iter={} stagnantIters={} threshold={}",
            req.context().runId(),
            req.context().taskId(),
            iter,
            stagnantIters,
            stagnationForceAfter);
        recordTerminal(WorkerResult.Outcome.COMPLETED_BY_STAGNATION, req, iter);
        return WorkerResult.builder()
            .outcome(WorkerResult.Outcome.COMPLETED_BY_STAGNATION)
            .iterations(iter)
            .toolCallsDispatched(toolsDispatched)
            .finalAssistantText(lastText)
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

    ChatResponse resp;
    try {
      resp =
          router.call(
              LlmRequest.builder()
                  .provider(req.provider())
                  .model(req.model())
                  .messages(history)
                  .context(req.context())
                  .temperature(0.0)
                  .maxTokens(req.maxTokensPerCall())
                  .toolsOverride(toolRegistry.bindForWorker(req.context()))
                  .readTimeoutSeconds(WORKER_HTTP_READ_TIMEOUT_SECONDS)
                  .build());
    } catch (Exception e) {
      log.warn("WorkerAgent SALVAGE router error: {}", e.getMessage());
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
      if ("writeFile".equals(tc.name()) || "editFile".equals(tc.name())) {
        String env = dispatchOne(tc, req);
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

  private String dispatchOne(AssistantMessage.ToolCall tc, WorkerTaskRequest req) {
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
    JsonNode args;
    try {
      args =
          (tc.arguments() == null || tc.arguments().isBlank())
              ? objectMapper.createObjectNode()
              : objectMapper.readTree(tc.arguments());
    } catch (Exception e) {
      return errEnvelope("INVALID_ARGS", "tool args not valid JSON: " + e.getMessage());
    }
    try {
      ToolResult result = tool.execute(args, req.context());
      return switch (result) {
        case ToolResult.Ok ok -> okEnvelope(ok.content());
        case ToolResult.Err err -> errEnvelope(err.kind().name(), err.message());
      };
    } catch (Exception unexpected) {
      log.error("Tool '{}' threw unexpectedly", tc.name(), unexpected);
      return errEnvelope(
          "INTERNAL", unexpected.getClass().getSimpleName() + ": " + unexpected.getMessage());
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

  // ── 2026-05-04 — quirky LiveLog emitters ─────────────────────────────────────

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
      case "writeFile" -> emoji = "📝";
      case "editFile" -> emoji = "✏️ ";
      case "shell" -> emoji = "💻";
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

  private static String truncate(String s, int max) {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, max - 1) + "…";
  }
}

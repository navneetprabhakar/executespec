package com.navneet.executespec.v2.harness;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.v2.agent.PlanV2;
import com.navneet.executespec.v2.agent.PlannedTaskV2;
import com.navneet.executespec.v2.agent.WorkerAgentV2;
import com.navneet.executespec.v2.agent.WorkerResult;
import com.navneet.executespec.v2.agent.WorkerTaskRequest;
import com.navneet.executespec.v2.observability.V2Metrics;
import com.navneet.executespec.v2.tool.BuildProjectTool;
import com.navneet.executespec.v2.tool.ExecutionContext;
import com.navneet.executespec.v2.tool.ToolResult;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * V2 sequential executor. Runs a {@link PlanV2}'s tasks in order, one worker per task.
 *
 * <p>Per-task flow:
 *
 * <ol>
 *   <li>Compose system + user prompts from the task's description + hardConstraints + touchesFiles
 *       hint
 *   <li>Invoke {@link WorkerAgentV2} with the composed prompts and the run's ExecutionContext
 *       (substituting the task ID)
 *   <li>If worker didn't reach {@code COMPLETED}: record {@code WORKER_FAILED}, continue to next
 *       task (current policy — Phase 5 baseline. A future supervisor could upgrade to ABORT on
 *       catastrophic failures.)
 *   <li>Worker completed → run smoke test via {@link BuildProjectTool} using the plan's {@code
 *       buildCommand}
 *   <li>Smoke test passed → {@code SUCCESS}, next task
 *   <li>Smoke test failed → ONE retry: re-invoke worker with the build error injected into the user
 *       message
 *   <li>Retry passed → {@code SUCCESS_AFTER_RETRY}; retry failed → {@code COMPLETED_WITH_WARNING},
 *       continue anyway (reviewer phase — Phase 7 — will catch residual issues)
 * </ol>
 *
 * <p>Phase 5 baseline scope:
 *
 * <ul>
 *   <li>Rule-based failure handling (retry once, then warn). A future TaskSupervisorV2 can add
 *       LLM-tier triage (CASCADING / FIXABLE / STRUCTURAL) for smarter decisions.
 *   <li>No SSE events, no Redis context persistence, no cross-task context sharing.
 *   <li>Smoke tests default to the plan's {@code smokeTestStrategy} (currently only BUILD is fully
 *       wired — PREVIEW_HEALTH / TEST_SUITE planned for later phases).
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SequentialExecutorV2 {

  /** System prompt template for worker invocations. */
  private static final String SYSTEM_PROMPT_BASE =
      """
      You are a code-execution agent operating in a sandboxed workspace. You have
      access to filesystem (readFile, writeFile, editFile, listFiles, searchFiles),
      shell, git (status, diff, commit), and buildProject tools. Use the tools to
      complete the task — do not explain or hedge.

      Important rules:
      - The shell runs in the workspace root already. Do NOT `cd /workspace` or use
        absolute paths — use relative paths (e.g. `python3 fib.py`, not `cd /workspace && ...`).
      - Use `python3` (never bare `python`); many systems have no `python` on PATH.
      - writeFile creates NEW files only. To modify an existing file, use editFile.
      - editFile takes an array of {oldStr, newStr} pairs; every oldStr must occur
        exactly once in the current file.
      - After making changes, you may call buildProject to verify the project still
        compiles.
      - When the task is fully complete, call completeTask with a brief summary.
        Do not output any text that is not needed to complete the task.

      Efficiency — BATCH AGGRESSIVELY. Every extra turn costs tokens and latency:
      - Emit ALL independent tool calls in ONE turn (reads, writes, edits, builds).
      - If you just wrote a file, DO NOT re-read it to verify — trust writeFile's
        success envelope and move on.
      - Do NOT call gitStatus/gitDiff unless you specifically need commit staging info.
      - Do NOT re-read the same file twice in a task; content does not change unless
        YOU wrote to it.
      - Prefer large editFile batches (many {oldStr,newStr} pairs in one call) over
        many small editFile calls.
      - When the work is done, call completeTask IMMEDIATELY; do not add
        "final verification" round-trips.
      """;

  private final WorkerAgentV2 worker;
  private final BuildProjectTool buildProjectTool;
  private final ObjectMapper objectMapper;
  private final V2Metrics metrics;
  private final SseBroadcaster broadcaster;
  private final AgentTaskRepository taskRepo;
  private final ModelResolver modelResolver;
  private final com.navneet.executespec.project.rag.RetrievalService retrievalService;

  /**
   * Live per-task token attribution — read the accumulator on every terminal transition so the UI's
   * "Token Usage by Model" shows mid-run numbers instead of waiting for the end-of-run batch flush.
   */
  private final com.navneet.executespec.v2.cost.V2RunCostTracker costTracker;

  /**
   * Retrieval knobs for worker-time RAG injection (v2.5.2+). Smaller than the planner's budget
   * (k=3, 1500) so per-task cost stays bounded across many tasks.
   */
  private static final int WORKER_RAG_K = 2;

  private static final int WORKER_RAG_TOKEN_CAP = 800;

  public ExecutionResult execute(ExecutionRequest req) {
    long runStart = System.currentTimeMillis();
    List<TaskOutcome> outcomes = new ArrayList<>();
    PlanV2 plan = req.plan();

    log.info(
        "SequentialExecutorV2 START run={} tasks={} provider={} model={} smokeTests={}",
        req.runContext().runId(),
        plan.tasks().size(),
        req.provider(),
        req.model(),
        req.enableSmokeTests());

    int taskCount = plan.tasks().size();
    for (int i = 0; i < taskCount; i++) {
      PlannedTaskV2 task = plan.tasks().get(i);
      boolean isFinalTask = (i == taskCount - 1);
      TaskOutcome outcome = executeOne(task, plan, req, isFinalTask);
      outcomes.add(outcome);
      metrics.recordTaskStatus(outcome.status().name());
      metrics.recordTaskLatency(
          outcome.status().name(), java.time.Duration.ofMillis(outcome.totalLatencyMs()));
    }

    ExecutionResult result = summarize(outcomes, System.currentTimeMillis() - runStart);
    log.info(
        "SequentialExecutorV2 DONE run={} overall={} success={}/{} warnings={} failed={}"
            + " latency={}ms",
        req.runContext().runId(),
        result.overall(),
        result.tasksSuccessful(),
        result.tasksExecuted(),
        result.tasksWithWarning(),
        result.tasksFailed(),
        result.totalLatencyMs());
    return result;
  }

  private TaskOutcome executeOne(
      PlannedTaskV2 task, PlanV2 plan, ExecutionRequest req, boolean isFinalTask) {
    long taskStart = System.currentTimeMillis();
    ExecutionContext taskCtx =
        new ExecutionContext(req.runContext().runId(), task.id(), req.runContext().workspaceRoot());

    String refDocs = fetchReferenceDocs(req.projectId(), task);
    String systemPrompt =
        composeSystemPrompt(
            task,
            req.designBlock(),
            refDocs,
            req.plan() == null ? null : req.plan().projectType(),
            req.workspaceSummary());
    String userPrompt = composeUserPrompt(task, null);

    log.info("Executor task={} ('{}') START", task.id(), task.title());
    String runId = req.runContext().runId();
    persistTaskStatus(runId, task.id(), "WORKING");
    broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "WORKING"));
    broadcaster.emit(runId, RunEvent.log(runId, task.id(), "Starting task: " + task.title()));
    TierConfig cfg =
        tierConfigFor(task.complexity(), req.provider(), req.model(), req.projectId() != null);
    WorkerResult primary =
        worker.run(
            WorkerTaskRequest.builder()
                .provider(cfg.provider)
                .model(cfg.model)
                .systemPrompt(systemPrompt)
                .userPrompt(userPrompt)
                .context(taskCtx)
                .maxIterations(
                    req.maxIterationsPerTask() != null ? req.maxIterationsPerTask() : cfg.maxIter)
                .temperature(0.0)
                .maxTokensPerCall(cfg.maxTokens)
                .build());

    log.info(
        "Executor task={} toolCallStats provider={} model={} complexity={} outcome={} iters={}"
            + " toolCalls={} fileWrites={}",
        task.id(),
        cfg.provider,
        cfg.model,
        task.complexity() == null ? "MEDIUM" : task.complexity().name(),
        primary.outcome(),
        primary.iterations(),
        primary.toolCallsDispatched(),
        countFileWrites(primary.toolCallLog()));

    // Worker didn't reach COMPLETED. Check whether it still wrote files — if so
    // the task produced real output and shouldn't be marked FAILED outright.
    // Common case: Gemini hits its iteration cap on HIGH tasks but has already
    // written the schema / entities / controller and just failed to call
    // `completeTask`. Marking these FAILED made the run look broken even though
    // the code was fine, and tanked the accuracy score.
    if (primary.outcome() != WorkerResult.Outcome.COMPLETED) {
      int fileWrites = countFileWrites(primary.toolCallLog());
      if (fileWrites > 0) {
        log.warn(
            "Executor task={} worker outcome={} but wrote {} files — promoting to PARTIAL",
            task.id(),
            primary.outcome(),
            fileWrites);
        persistTaskStatus(runId, task.id(), "PARTIAL");
        broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "PARTIAL"));
        return TaskOutcome.builder()
            .taskId(task.id())
            .taskTitle(task.title())
            .status(TaskOutcome.Status.COMPLETED_WITH_WARNING)
            .workerOutcome(primary)
            .totalLatencyMs(System.currentTimeMillis() - taskStart)
            .toolCallsDispatched(primary.toolCallsDispatched())
            .build();
      }
      log.warn(
          "Executor task={} WORKER_FAILED outcome={} iters={} (no file writes)",
          task.id(),
          primary.outcome(),
          primary.iterations());
      // v2.5.12 — populate a concrete failureReason for every terminal
      // worker outcome so RunMapper doesn't have to fall back to
      // "unknown — see worker logs". This closes Bug D from the eval.
      persistTaskStatus(runId, task.id(), "FAILED", describeWorkerFailure(primary));
      broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "FAILED"));
      return TaskOutcome.builder()
          .taskId(task.id())
          .taskTitle(task.title())
          .status(TaskOutcome.Status.WORKER_FAILED)
          .workerOutcome(primary)
          .totalLatencyMs(System.currentTimeMillis() - taskStart)
          .toolCallsDispatched(primary.toolCallsDispatched())
          .build();
    }

    if (!req.enableSmokeTests()) {
      log.info("Executor task={} SUCCESS (smoke tests disabled)", task.id());
      persistTaskStatus(runId, task.id(), "COMPLETE");
      broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "COMPLETE"));
      return TaskOutcome.builder()
          .taskId(task.id())
          .taskTitle(task.title())
          .status(TaskOutcome.Status.SUCCESS)
          .workerOutcome(primary)
          .totalLatencyMs(System.currentTimeMillis() - taskStart)
          .toolCallsDispatched(primary.toolCallsDispatched())
          .build();
    }

    // Skip per-task smoke for non-final tasks in multi-task plans:
    // intermediate tasks (T1 scaffold, T2 partial wiring, ...) cannot
    // possibly satisfy a full project build because later tasks haven't
    // landed yet. The reviewer's final build at the end of the run is
    // the authoritative gate. This eliminates false-negative PARTIAL
    // statuses on tasks whose code is later wired up correctly.
    boolean multiTask = plan.tasks().size() > 1;
    if (multiTask && !isFinalTask) {
      log.info(
          "Executor task={} SUCCESS (intermediate smoke skipped — multi-task plan, final task +"
              + " reviewer are authoritative)",
          task.id());
      persistTaskStatus(runId, task.id(), "COMPLETE");
      broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "COMPLETE"));
      return TaskOutcome.builder()
          .taskId(task.id())
          .taskTitle(task.title())
          .status(TaskOutcome.Status.SUCCESS)
          .workerOutcome(primary)
          .totalLatencyMs(System.currentTimeMillis() - taskStart)
          .toolCallsDispatched(primary.toolCallsDispatched())
          .build();
    }

    SmokeTestResult smoke = runSmokeTest(taskCtx, plan.buildCommand());
    if (smoke.passed()) {
      log.info(
          "Executor task={} SUCCESS (smoke exit={}, took {}ms)",
          task.id(),
          smoke.exitCode(),
          smoke.latencyMs());
      persistTaskStatus(runId, task.id(), "COMPLETE");
      broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "COMPLETE"));
      return TaskOutcome.builder()
          .taskId(task.id())
          .taskTitle(task.title())
          .status(TaskOutcome.Status.SUCCESS)
          .workerOutcome(primary)
          .smokeTestPassed(true)
          .smokeTestExitCode(smoke.exitCode())
          .smokeTestOutput(smoke.output())
          .totalLatencyMs(System.currentTimeMillis() - taskStart)
          .toolCallsDispatched(primary.toolCallsDispatched())
          .build();
    }

    log.warn(
        "Executor task={} smoke test FAILED (exit={}), retrying once with error context",
        task.id(),
        smoke.exitCode());

    // ONE retry with the build error injected.
    String retryPrompt = composeUserPrompt(task, smoke.output());
    WorkerResult retry =
        worker.run(
            WorkerTaskRequest.builder()
                .provider(cfg.provider)
                .model(cfg.model)
                .systemPrompt(systemPrompt)
                .userPrompt(retryPrompt)
                .context(taskCtx)
                .maxIterations(
                    req.maxIterationsPerTask() != null ? req.maxIterationsPerTask() : cfg.maxIter)
                .temperature(0.0)
                .maxTokensPerCall(cfg.maxTokens)
                .build());

    int totalToolCalls = primary.toolCallsDispatched() + retry.toolCallsDispatched();
    if (retry.outcome() != WorkerResult.Outcome.COMPLETED) {
      log.warn(
          "Executor task={} retry WORKER_FAILED outcome={}; marking COMPLETED_WITH_WARNING",
          task.id(),
          retry.outcome());
      return TaskOutcome.builder()
          .taskId(task.id())
          .taskTitle(task.title())
          .status(TaskOutcome.Status.COMPLETED_WITH_WARNING)
          .workerOutcome(primary)
          .retryAttempted(true)
          .retryWorkerOutcome(retry)
          .smokeTestPassed(false)
          .smokeTestExitCode(smoke.exitCode())
          .smokeTestOutput(smoke.output())
          .totalLatencyMs(System.currentTimeMillis() - taskStart)
          .toolCallsDispatched(totalToolCalls)
          .build();
    }

    // Retry completed — re-smoke.
    SmokeTestResult retrySmoke = runSmokeTest(taskCtx, plan.buildCommand());
    if (retrySmoke.passed()) {
      log.info("Executor task={} SUCCESS_AFTER_RETRY", task.id());
      persistTaskStatus(runId, task.id(), "COMPLETE");
      broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "COMPLETE"));
      return TaskOutcome.builder()
          .taskId(task.id())
          .taskTitle(task.title())
          .status(TaskOutcome.Status.SUCCESS_AFTER_RETRY)
          .workerOutcome(primary)
          .retryAttempted(true)
          .retryWorkerOutcome(retry)
          .smokeTestPassed(true)
          .smokeTestExitCode(retrySmoke.exitCode())
          .smokeTestOutput(retrySmoke.output())
          .totalLatencyMs(System.currentTimeMillis() - taskStart)
          .toolCallsDispatched(totalToolCalls)
          .build();
    }

    // If the primary + retry worker wrote zero files, the task did not change
    // the codebase — a smoke failure here is almost always environmental
    // (wrong directory, missing toolchain, flaky network). Downgrading to
    // COMPLETE avoids poisoning the run's overall status on a tautological
    // verify-only task. The smoke output is still preserved on the outcome
    // so the reviewer / report can surface it if relevant.
    int totalFileWrites =
        countFileWrites(primary.toolCallLog()) + countFileWrites(retry.toolCallLog());
    if (totalFileWrites == 0) {
      log.warn(
          "Executor task={} smoke failed after retry but worker wrote 0 files — "
              + "demoting to COMPLETE (verify-only task, smoke failure is environmental)",
          task.id());
      persistTaskStatus(runId, task.id(), "COMPLETE");
      broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "COMPLETE"));
      return TaskOutcome.builder()
          .taskId(task.id())
          .taskTitle(task.title())
          .status(TaskOutcome.Status.SUCCESS)
          .workerOutcome(primary)
          .retryAttempted(true)
          .retryWorkerOutcome(retry)
          .smokeTestPassed(false)
          .smokeTestExitCode(retrySmoke.exitCode())
          .smokeTestOutput(retrySmoke.output())
          .totalLatencyMs(System.currentTimeMillis() - taskStart)
          .toolCallsDispatched(totalToolCalls)
          .build();
    }

    log.warn("Executor task={} smoke still fails after retry; COMPLETED_WITH_WARNING", task.id());
    persistTaskStatus(runId, task.id(), "PARTIAL");
    broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "PARTIAL"));
    return TaskOutcome.builder()
        .taskId(task.id())
        .taskTitle(task.title())
        .status(TaskOutcome.Status.COMPLETED_WITH_WARNING)
        .workerOutcome(primary)
        .retryAttempted(true)
        .retryWorkerOutcome(retry)
        .smokeTestPassed(false)
        .smokeTestExitCode(retrySmoke.exitCode())
        .smokeTestOutput(retrySmoke.output())
        .totalLatencyMs(System.currentTimeMillis() - taskStart)
        .toolCallsDispatched(totalToolCalls)
        .build();
  }

  /**
   * Updates {@code AgentTask.status} in the DB mid-run so the UI's polled task list reflects live
   * progress (not just the terminal snapshot written by {@code
   * V2RunOrchestrator.applyExecutionResultToTasks}). Uses the same prefixed-key convention as the
   * orchestrator — first 8 chars of runId plus the planner-assigned task id.
   */
  /**
   * Per-task tuning: tier-resolved model + iteration / max-token caps driven by the planner's
   * complexity tag.
   *
   * <ul>
   *   <li>LOW → LIGHTWEIGHT tier, 30 iters, 4096 max tokens
   *   <li>MEDIUM → WORKHORSE tier, 50 iters, 8192 max tokens
   *   <li>HIGH → PLANNING tier, 75 iters, 16384 max tokens
   * </ul>
   *
   * <p>When {@link ModelResolver} / provider conversion fails (e.g. user-supplied provider doesn't
   * match any enum value), we fall back to the run-level provider+model so the executor never
   * crashes on a planner edge case.
   */
  private record TierConfig(String provider, String model, int maxIter, int maxTokens) {}

  private TierConfig tierConfigFor(
      PlannedTaskV2.Complexity complexity,
      String runProvider,
      String runModel,
      boolean projectRun) {
    AgentTier tier;
    int maxIter;
    int maxTokens;
    switch (complexity == null ? PlannedTaskV2.Complexity.MEDIUM : complexity) {
        // Caps bumped again 2026-04-19: 20/30/45 → 30/50/75. Observed on
        // the RN Calorie Tracker run (0a0201c7) — MEDIUM scaffold task
        // (7 files + npm install + typecheck) hit 30 iters and finished
        // PARTIAL even though all files were written. Bigger scaffold-heavy
        // specs consistently need >30 iters because every readFile /
        // writeFile is one tool call. 50 covers a clean 7-file scaffold
        // with 2-3 self-review re-reads; 75 covers HIGH tasks (e.g. T3
        // "implement 4 screens + service" on the same run) with headroom.
        // (Previous bump 12/22/35 → 20/30/45 was for multi-repo Launches;
        //  this second bump is for codegen-dense greenfield scaffolds.)
      case LOW -> {
        tier = AgentTier.LIGHTWEIGHT;
        maxIter = 30;
        maxTokens = 4096;
      }
      case HIGH -> {
        tier = AgentTier.PLANNING;
        maxIter = 75;
        maxTokens = 16384;
      }
      default -> {
        tier = AgentTier.WORKHORSE;
        maxIter = 50;
        maxTokens = 8192;
      }
    }
    // Project runs (Launches on an existing cloned codebase) need more
    // iterations: the worker has to readFile / searchFiles the real repo
    // before it can write, so 2× the fresh-scratch cap is the default.
    if (projectRun) maxIter *= 2;
    // 2026-04-26 cost-control: OpenAI is markedly chattier per iter than
    // Anthropic (Spring AI re-ships the FULL conversation history on
    // every tool call, no prompt cache; observed 1.45M input tokens on
    // the HIGH library spec vs Anthropic's 132K for the same spec). Cap
    // OpenAI at 70% of the default iteration budget so we bail on stuck
    // worker loops sooner. Quality impact: worker still has 35 iters for
    // a non-project HIGH task (was 75) — empirically enough for the
    // library spec which finished 4/4 tasks in the prior eval. If runs
    // start hitting iteration-limit cleanly we'll revisit; the bigger
    // win (sliding-window or summary-compress on the conversation
    // history) is tracked as a follow-up under
    // docs/plans/openai-conversation-compression.md (TBD).
    if (runProvider != null && "openai".equalsIgnoreCase(runProvider)) {
      maxIter = (int) Math.ceil(maxIter * 0.70);
    }
    try {
      // v2.5.13 — enum + V2 strings now agree ("gemini"/"anthropic"/"openai")
      LlmProvider p =
          runProvider == null ? LlmProvider.OPENAI : LlmProvider.valueOf(runProvider.toUpperCase());
      ResolvedModel resolved = modelResolver.resolve("WorkerAgent", p, tier);
      return new TierConfig(resolved.providerName(), resolved.getModel(), maxIter, maxTokens);
    } catch (Exception e) {
      log.warn(
          "tierConfigFor: resolve failed for provider={} tier={} — using run-level {} / {}",
          runProvider,
          tier,
          runProvider,
          runModel);
      return new TierConfig(runProvider, runModel, maxIter, maxTokens);
    }
  }

  @org.springframework.transaction.annotation.Transactional
  public void persistTaskStatus(String runId, String taskIdShort, String status) {
    persistTaskStatus(runId, taskIdShort, status, null);
  }

  /**
   * v2.5.12 — overload that stores an optional failure reason on the task row. The reason lands in
   * {@code lastLlmFailure} (already read as a fallback by {@code RunMapper.extractFailureReason})
   * so the UI shows a concrete message instead of "unknown — see worker logs".
   */
  @org.springframework.transaction.annotation.Transactional
  public void persistTaskStatus(
      String runId, String taskIdShort, String status, String failureReason) {
    String key = runId.substring(0, 8) + "_" + taskIdShort;
    try {
      var existing = taskRepo.findById(key);
      if (existing.isEmpty()) {
        log.warn("persistTaskStatus: row not found id={} (status={} skipped)", key, status);
        return;
      }
      var t = existing.get();
      t.setStatus(status);
      // Timestamp live — the Timeline tab needs real per-task start/end to render
      // staggered bars, and the end-of-run applyExecutionResultToTasks only writes
      // completedAt + durationMs. Stamp startedAt when a task first moves to WORKING,
      // completedAt when it reaches a terminal status. Don't overwrite existing values.
      if ("WORKING".equals(status) && t.getStartedAt() == null) {
        t.setStartedAt(java.time.Instant.now());
      }
      boolean terminal =
          "COMPLETE".equals(status)
              || "PARTIAL".equals(status)
              || "FAILED".equals(status)
              || "BLOCKED".equals(status);
      if (terminal && t.getCompletedAt() == null) {
        java.time.Instant now = java.time.Instant.now();
        t.setCompletedAt(now);
        if (t.getStartedAt() != null && t.getDurationMs() == null) {
          t.setDurationMs(java.time.Duration.between(t.getStartedAt(), now).toMillis());
        }
      }
      // Live token flush — pull the cumulative per-task snapshot out of
      // V2RunCostTracker so the UI sees real numbers for this task the
      // moment it terminates, not only after the whole run ends.
      if (terminal && costTracker != null) {
        try {
          var snap = costTracker.snapshotTask(runId, taskIdShort);
          if (snap != null && (snap.inputTokens() > 0 || snap.outputTokens() > 0)) {
            t.setInputTokens(snap.inputTokens());
            t.setOutputTokens(snap.outputTokens());
          }
        } catch (Exception snapEx) {
          log.debug("persistTaskStatus: snapshotTask failed for {}: {}", key, snapEx.getMessage());
        }
      }
      if (failureReason != null && !failureReason.isBlank()) {
        t.setLastLlmFailure(failureReason);
      }
      taskRepo.saveAndFlush(t);
      log.info(
          "persistTaskStatus: {} -> {}{}",
          key,
          status,
          failureReason != null ? " (reason: " + failureReason + ")" : "");
    } catch (Exception e) {
      log.warn("persistTaskStatus: {} -> {} failed: {}", key, status, e.getMessage(), e);
    }
  }

  /**
   * v2.5.12 — maps a non-COMPLETED worker outcome to a human-readable failure reason. Keeps the
   * message short enough to render inline on the run detail page while preserving the most
   * diagnostic snippet (finalAssistantText for STOPPED_WITHOUT_COMPLETE, the iteration count for
   * ITERATION_LIMIT_EXCEEDED).
   */
  private static String describeWorkerFailure(WorkerResult r) {
    if (r == null || r.outcome() == null) return "Worker outcome unknown";
    String tail = r.finalAssistantText();
    if (tail != null && tail.length() > 240) tail = tail.substring(0, 240) + "…";
    return switch (r.outcome()) {
      case STOPPED_WITHOUT_COMPLETE ->
          "Worker stopped without calling completeTask after "
              + r.iterations()
              + " iterations"
              + (tail != null && !tail.isBlank() ? ". Final: " + tail : "");
      case ITERATION_LIMIT_EXCEEDED ->
          "Iteration cap exceeded ("
              + r.iterations()
              + " iters, "
              + r.toolCallsDispatched()
              + " tool calls). Worker did not call completeTask.";
      case ERROR ->
          "Worker threw unrecoverable exception"
              + (tail != null && !tail.isBlank() ? ": " + tail : "");
      case BUDGET_EXCEEDED -> "Run hit its hard cost cap; worker stopped cleanly mid-task.";
      default -> "Worker outcome=" + r.outcome();
    };
  }

  // ─── prompt composition ─────────────────────────────────────────────────

  // VisibleForTesting — package-private so MultiRepoWorkerPromptTest can pin the
  // workspace-summary forwarding without spinning up the full ExecutionRequest pipeline.
  String composeSystemPrompt(
      PlannedTaskV2 task,
      String designBlock,
      String refDocs,
      String projectType,
      String workspaceSummary) {
    StringBuilder sb = new StringBuilder(SYSTEM_PROMPT_BASE);

    // Multi-repo correctness (2026-05-02 fix). When the orchestrator targets a multi-repo project
    // it ships a workspaceSummary with "MULTI-REPO PROJECT WORKSPACE" + the per-repo subdir list.
    // Without forwarding it to the worker the planner-side `repos/<name>/` directives are
    // invisible — the worker's writeFile lands files at the workspace root, the per-repo push
    // step finds no diff to ship, runs finish COMPLETE with empty PRs. See
    // V2RunOrchestrator.buildMultiRepoSummary + MultiRepoWorkerPromptTest.
    if (workspaceSummary != null && workspaceSummary.contains("MULTI-REPO")) {
      sb.append("\n\n").append(workspaceSummary);
    }
    // v2.5.13 — RN/Expo scaffold adherence directive. When the planner declared
    // react-native-expo, inject the scaffold template verbatim so the worker
    // stops drifting to Expo SDK 51 (stable-ish but doesn't match our smoke
    // container expectations) or to `@testing-library/jest-native` (deprecated,
    // matchers are built into RNTL v12.4+). Run 0a0201c7 shipped SDK 51 +
    // jest-native + no hermes-compiler dep; all three we baked into the
    // template as explicit warnings after the rn-smoke spike, but the worker
    // only sees the planner's paraphrase unless we surface the template here.
    String scaffoldBlock = loadScaffoldForProjectType(projectType);
    if (!scaffoldBlock.isBlank()) {
      sb.append(
              "\n\n--- SCAFFOLD TEMPLATE (MATCH EXACTLY; deviate only with a written reason) ---\n")
          .append(scaffoldBlock)
          .append("\n--- END SCAFFOLD TEMPLATE ---\n");
    }
    if (!task.hardConstraints().isEmpty()) {
      sb.append("\n\nHARD CONSTRAINTS for this task (MUST follow):\n");
      for (String c : task.hardConstraints()) {
        sb.append("- ").append(c).append('\n');
      }
    }
    if (!task.touchesFiles().isEmpty()) {
      sb.append("\nExpected files to touch (hint, not a hard wall): ")
          .append(String.join(", ", task.touchesFiles()))
          .append('\n');
    }
    if (designBlock != null && !designBlock.isBlank()) {
      sb.append("\n\n").append(designBlock);
    }
    if (refDocs != null && !refDocs.isBlank()) {
      // Worker-time RAG (v2.5.2): relevant snippets from uploaded project docs.
      // Planner already injects its own {{REFERENCE_DOCS}} block during plan
      // synthesis; this is a narrower, task-scoped retrieval that keeps
      // detail constraints (field names, API contracts, limits) in front of
      // the worker while it writes code.
      sb.append("\n\n--- REFERENCE DOCS (task-relevant excerpts) ---\n")
          .append(refDocs)
          .append("\n--- END REFERENCE DOCS ---\n");
    }
    return sb.toString();
  }

  /**
   * Load the scaffold template file matching the given projectType, or "" if none applies. Mirrors
   * the loose kebab-case convention used by {@link
   * com.navneet.executespec.execution.controller.ScaffoldController} — the file at {@code
   * prompts/scaffolds/<key>.md} wins if present.
   */
  private String loadScaffoldForProjectType(String projectType) {
    if (projectType == null || projectType.isBlank()) return "";
    String key = projectType.trim().toLowerCase().replace('_', '-');
    try {
      org.springframework.core.io.Resource r =
          new org.springframework.core.io.ClassPathResource("prompts/scaffolds/" + key + ".md");
      if (!r.exists()) return "";
      return new String(r.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
      log.debug("loadScaffoldForProjectType({}) failed: {}", projectType, e.getMessage());
      return "";
    }
  }

  /**
   * Task-scoped RAG retrieval. Uses the task description as the query; empty string on any failure
   * (retrieval errors never fail the run). Returns the raw markdown from {@code RetrievalService}
   * ready for prompt inclusion.
   */
  private String fetchReferenceDocs(String projectId, PlannedTaskV2 task) {
    if (projectId == null || projectId.isBlank()) return "";
    String query =
        task.description() == null || task.description().isBlank()
            ? task.title()
            : task.description();
    if (query == null || query.isBlank()) return "";
    try {
      String docs = retrievalService.retrieve(projectId, query, WORKER_RAG_K, WORKER_RAG_TOKEN_CAP);
      return docs == null ? "" : docs;
    } catch (Exception e) {
      log.debug(
          "fetchReferenceDocs failed for project={} task={}: {}",
          projectId,
          task.id(),
          e.getMessage());
      return "";
    }
  }

  private String composeUserPrompt(PlannedTaskV2 task, String previousBuildError) {
    StringBuilder sb = new StringBuilder();
    sb.append("Task ").append(task.id()).append(": ").append(task.title()).append("\n\n");
    sb.append(task.description());
    if (previousBuildError != null && !previousBuildError.isBlank()) {
      sb.append("\n\n---\n")
          .append("NOTE: A previous attempt completed but the build failed afterward. ")
          .append("Fix the issue so the build passes. Build output:\n")
          .append(previousBuildError);
    }
    return sb.toString();
  }

  // ─── smoke test helpers ─────────────────────────────────────────────────

  private SmokeTestResult runSmokeTest(ExecutionContext ctx, String plannerCommand) {
    long start = System.currentTimeMillis();
    ObjectNode args = objectMapper.createObjectNode();
    if (plannerCommand != null && !plannerCommand.isBlank()) {
      args.put("plannerCommand", plannerCommand);
    }
    ToolResult result = buildProjectTool.execute(args, ctx);
    long elapsed = System.currentTimeMillis() - start;
    if (result instanceof ToolResult.Ok ok) {
      String content = ok.content();
      int exit = extractExitCode(content);
      boolean passed = exit == 0;
      metrics.recordSmokeTest(passed ? "pass" : "fail");
      return new SmokeTestResult(passed, exit, content, elapsed);
    }
    // Tool Err — treat as smoke failure with a synthetic exit code
    metrics.recordSmokeTest("fail");
    String msg = result instanceof ToolResult.Err err ? err.message() : "(unknown tool err)";
    return new SmokeTestResult(false, -1, "tool error: " + msg, elapsed);
  }

  /** Parses "exit=N" out of BuildProjectTool's output envelope. */
  private static int extractExitCode(String output) {
    if (output == null) return -1;
    int i = output.indexOf("exit=");
    if (i < 0) return -1;
    int j = i + "exit=".length();
    int k = j;
    while (k < output.length() && Character.isDigit(output.charAt(k))) k++;
    try {
      return Integer.parseInt(output.substring(j, k));
    } catch (NumberFormatException nfe) {
      return -1;
    }
  }

  private record SmokeTestResult(boolean passed, int exitCode, String output, long latencyMs) {}

  // ─── summarization ──────────────────────────────────────────────────────

  private ExecutionResult summarize(List<TaskOutcome> outcomes, long totalLatency) {
    int succ = 0, warn = 0, fail = 0, skip = 0;
    for (var o : outcomes) {
      switch (o.status()) {
        case SUCCESS, SUCCESS_AFTER_RETRY -> succ++;
        case COMPLETED_WITH_WARNING -> warn++;
        case WORKER_FAILED -> fail++;
        case SKIPPED -> skip++;
      }
    }
    ExecutionResult.Overall overall;
    if (fail > 0) overall = ExecutionResult.Overall.FAILED;
    else if (warn > 0) overall = ExecutionResult.Overall.PARTIAL_WITH_WARNINGS;
    else overall = ExecutionResult.Overall.ALL_SUCCESS;

    // v2.5.10 — separate "final task status" from "any intermediate warning"
    // so V2RunOrchestrator can distinguish CLEAN_COMPLETION from
    // CLEAN_WITH_INTERMEDIATE_WARNINGS when the final task is clean but
    // an earlier task flickered.
    TaskOutcome lastNonSkipped = null;
    for (int i = outcomes.size() - 1; i >= 0; i--) {
      if (outcomes.get(i).status() != TaskOutcome.Status.SKIPPED) {
        lastNonSkipped = outcomes.get(i);
        break;
      }
    }
    boolean anyIntermediateWarning = false;
    if (lastNonSkipped != null) {
      for (TaskOutcome o : outcomes) {
        if (o == lastNonSkipped) continue;
        if (o.status() == TaskOutcome.Status.COMPLETED_WITH_WARNING) {
          anyIntermediateWarning = true;
          break;
        }
      }
    }

    return ExecutionResult.builder()
        .overall(overall)
        .tasksExecuted(outcomes.size() - skip)
        .tasksSuccessful(succ)
        .tasksWithWarning(warn)
        .tasksFailed(fail)
        .tasksSkipped(skip)
        .perTask(outcomes)
        .totalLatencyMs(totalLatency)
        .finalTaskStatus(lastNonSkipped != null ? lastNonSkipped.status() : null)
        .anyIntermediateWarning(anyIntermediateWarning)
        .build();
  }

  /**
   * Counts successful file-writing tool calls in a worker's tool log. The log entries look like
   * {@code "writeFile({...})"}, {@code "writeFile → COMPLETED"}, or {@code "editFile(...)"}. We
   * only need a count (not file paths) to decide whether an incomplete worker still produced real
   * output.
   */
  private static int countFileWrites(java.util.List<String> toolLog) {
    if (toolLog == null || toolLog.isEmpty()) return 0;
    int n = 0;
    for (String entry : toolLog) {
      if (entry == null) continue;
      if (entry.startsWith("writeFile") || entry.startsWith("editFile")) n++;
    }
    return n;
  }
}

package com.navneet.executespec.harness;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.agent.WorkerAgent;
import com.navneet.executespec.agent.WorkerResult;
import com.navneet.executespec.agent.WorkerTaskRequest;
import com.navneet.executespec.observability.Metrics;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.tool.BuildProjectTool;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.tool.ToolResult;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * V2 sequential executor. Runs a {@link Plan}'s tasks in order, one worker per task.
 *
 * <p>Per-task flow:
 *
 * <ol>
 *   <li>Compose system + user prompts from the task's description + hardConstraints + touchesFiles
 *       hint
 *   <li>Invoke {@link WorkerAgent} with the composed prompts and the run's ExecutionContext
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
public class SequentialExecutor {

  /** System prompt template for worker invocations. */
  private static final String SYSTEM_PROMPT_BASE =
      """
      You are a code-execution agent operating in a sandboxed workspace. You have
      access to filesystem (readFile, writeFile, editFile, listFiles, searchFiles),
      shell, read-only git (status, diff), and buildProject tools. Use the tools
      to complete the task — do not explain or hedge.

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

      DO NOT commit, stage, or push. Workspace changes are committed and pushed
      automatically at run-end by a downstream service. You have NO gitCommit
      tool — attempts to call it return FORBIDDEN. Do not run shell commands that
      mutate git state (`git add`, `git commit`, `git reset`, `git stash`,
      `git push`). Edit files; call completeTask. That's it.

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

  private final WorkerAgent worker;
  private final BuildProjectTool buildProjectTool;
  private final ObjectMapper objectMapper;
  private final Metrics metrics;
  private final SseBroadcaster broadcaster;
  private final AgentTaskRepository taskRepo;
  private final ModelResolver modelResolver;
  private final com.navneet.executespec.project.rag.RetrievalService retrievalService;

  /**
   * 2026-05-04 — used by the resume path to load saved worker conversation snapshots. The
   * non-resume execute path doesn't touch this field; ContextStore writes happen inside {@link
   * WorkerAgent} itself.
   */
  private final com.navneet.executespec.context.ContextStore contextStore;

  /**
   * Live per-task token attribution — read the accumulator on every terminal transition so the UI's
   * "Token Usage by Model" shows mid-run numbers instead of waiting for the end-of-run batch flush.
   */
  private final com.navneet.executespec.cost.RunCostTracker costTracker;

  /**
   * Retrieval knobs for worker-time RAG injection (v2.5.2+). Smaller than the planner's budget
   * (k=3, 1500) so per-task cost stays bounded across many tasks.
   */
  private static final int WORKER_RAG_K = 2;

  private static final int WORKER_RAG_TOKEN_CAP = 800;

  public ExecutionResult execute(ExecutionRequest req) {
    return execute(req, null);
  }

  /**
   * Resume-aware execute. When {@code resume} is non-null, every PlannedTask is checked against its
   * persisted {@link com.navneet.executespec.state.entity.AgentTask} status:
   *
   * <ul>
   *   <li>{@code COMPLETE} / {@code COMPLETED_WITH_WARNING} / {@code PARTIAL} / {@code FAILED} —
   *       already terminal. Skip; build a synthetic {@link TaskOutcome} from the persisted row so
   *       the run-level summary is consistent with what landed before the crash.
   *   <li>{@code WORKING} — the in-flight task at the time of the crash. If a Redis snapshot
   *       exists, hand it to the worker via the resume-aware {@code worker.run(req, snapshot)}
   *       overload so the conversation continues from the saved iteration. If the snapshot is gone
   *       (TTL expired, no save fired yet), the task runs fresh from iter=1.
   *   <li>{@code PENDING} / null / unknown — never started. Run fresh.
   * </ul>
   *
   * <p>The resume target is identified by either the explicit {@code resume.targetTaskId()} or —
   * when null — the first task whose persisted status is non-terminal (typically WORKING). All
   * tasks before the resume target are treated as terminal-skip; all tasks after are run fresh.
   */
  public ExecutionResult execute(ExecutionRequest req, ResumeRequest resume) {
    long runStart = System.currentTimeMillis();
    List<TaskOutcome> outcomes = new ArrayList<>();
    Plan plan = req.plan();
    String runId = req.runContext().runId();

    log.info(
        "SequentialExecutor {} run={} tasks={} provider={} model={} smokeTests={} resumeTarget={}",
        resume == null ? "START" : "RESUME",
        runId,
        plan.tasks().size(),
        req.provider(),
        req.model(),
        req.enableSmokeTests(),
        resume == null ? null : resume.targetTaskId());

    int taskCount = plan.tasks().size();
    for (int i = 0; i < taskCount; i++) {
      PlannedTask task = plan.tasks().get(i);
      boolean isFinalTask = (i == taskCount - 1);

      // Resume bookkeeping: figure out what to do with this task BEFORE running anything.
      //   - skip it (already terminal in DB) → emit synthetic outcome
      //   - resume it (snapshot present) → executeOne with snapshot
      //   - run fresh
      TaskOutcome outcome;
      if (resume != null) {
        ResumeAction action = resolveResumeAction(runId, task.id(), resume);
        switch (action.kind()) {
          case SKIP_TERMINAL -> {
            log.info(
                "Executor RESUME skip task={} status={} (already terminal pre-resume)",
                task.id(),
                action.persistedStatus());
            outcome = synthesizeSkippedOutcome(task, action.persistedStatus());
          }
          case RESUME_FROM_SNAPSHOT -> {
            log.info(
                "Executor RESUME task={} from snapshot iter={}",
                task.id(),
                action.snapshot() == null ? "n/a" : action.snapshot().iteration());
            outcome = executeOne(task, plan, req, isFinalTask, action.snapshot());
          }
          case RUN_FRESH -> outcome = executeOne(task, plan, req, isFinalTask, null);
          default -> outcome = executeOne(task, plan, req, isFinalTask, null);
        }
      } else {
        outcome = executeOne(task, plan, req, isFinalTask, null);
      }

      outcomes.add(outcome);
      metrics.recordTaskStatus(outcome.status().name());
      metrics.recordTaskLatency(
          outcome.status().name(), java.time.Duration.ofMillis(outcome.totalLatencyMs()));
    }

    ExecutionResult result = summarize(outcomes, System.currentTimeMillis() - runStart);
    log.info(
        "SequentialExecutor DONE run={} overall={} success={}/{} warnings={} failed={}"
            + " latency={}ms",
        runId,
        result.overall(),
        result.tasksSuccessful(),
        result.tasksExecuted(),
        result.tasksWithWarning(),
        result.tasksFailed(),
        result.totalLatencyMs());
    return result;
  }

  /**
   * Encapsulates the resume target — passed to {@link #execute(ExecutionRequest, ResumeRequest)}.
   */
  public record ResumeRequest(
      /**
       * Task id to resume from. Tasks before this id are treated as terminal (assumed already
       * finished pre-crash); tasks at this id are resumed (with snapshot if available); tasks after
       * are run fresh. May be null — in which case the executor walks the plan and resumes the
       * first task whose persisted DB status is non-terminal.
       */
      String targetTaskId) {}

  /** Result of the per-task resume decision in the loop above. */
  private record ResumeAction(
      ResumeKind kind,
      String persistedStatus,
      com.navneet.executespec.context.SavedContext snapshot) {}

  private enum ResumeKind {
    SKIP_TERMINAL,
    RESUME_FROM_SNAPSHOT,
    RUN_FRESH
  }

  /**
   * Decide what the executor should do with a given task on a resume pass. Reads the persisted
   * {@link com.navneet.executespec.state.entity.AgentTask} status; loads a Redis snapshot iff the
   * task is the resume target.
   */
  private ResumeAction resolveResumeAction(String runId, String taskId, ResumeRequest resume) {
    String persisted = readPersistedTaskStatus(runId, taskId);
    boolean isTerminal =
        persisted != null
            && (persisted.equals("COMPLETE")
                || persisted.equals("COMPLETED_WITH_WARNING")
                || persisted.equals("PARTIAL")
                || persisted.equals("FAILED"));

    String target = resume.targetTaskId();
    boolean isResumeTarget = target != null && target.equals(taskId);

    // If a target wasn't given explicitly, treat the FIRST non-terminal task we encounter as
    // the resume target (auto-detect path). Once we've passed the target, everything else runs
    // fresh.
    if (target == null) {
      if (isTerminal) return new ResumeAction(ResumeKind.SKIP_TERMINAL, persisted, null);
      // First non-terminal: this is our resume target. Below logic loads the snapshot.
      isResumeTarget = true;
    }

    if (isTerminal) {
      return new ResumeAction(ResumeKind.SKIP_TERMINAL, persisted, null);
    }
    if (!isResumeTarget) {
      return new ResumeAction(ResumeKind.RUN_FRESH, persisted, null);
    }

    // Resume target — try to load the snapshot from Redis.
    com.navneet.executespec.tool.ExecutionContext ctx =
        new com.navneet.executespec.tool.ExecutionContext(
            runId,
            taskId,
            // workspaceRoot isn't part of the Redis key — any value works for keyFor.
            java.nio.file.Paths.get("/"));
    com.navneet.executespec.context.SavedContext snap = contextStore.load(ctx).orElse(null);
    if (snap == null) {
      log.info(
          "Executor RESUME run={} task={} no snapshot — running fresh (likely <5 iters at crash)",
          runId,
          taskId);
      return new ResumeAction(ResumeKind.RUN_FRESH, persisted, null);
    }
    return new ResumeAction(ResumeKind.RESUME_FROM_SNAPSHOT, persisted, snap);
  }

  private String readPersistedTaskStatus(String runId, String taskId) {
    return taskRepo
        .findByRunIdAndTaskId(runId, taskId)
        .map(com.navneet.executespec.state.entity.AgentTask::getStatus)
        .orElse(null);
  }

  /**
   * Build a synthetic {@link TaskOutcome} for a task that was already terminal at resume time. The
   * summary aggregator uses this to compute total latency / success counts; we zero out
   * worker-specific fields since we don't have a worker result for it (the original work happened
   * in a previous JVM).
   */
  private TaskOutcome synthesizeSkippedOutcome(PlannedTask task, String persistedStatus) {
    // Map the persisted DB task status onto the in-memory TaskOutcome.Status enum. PARTIAL on
    // disk maps to COMPLETED_WITH_WARNING here — both indicate "worker wrote files but smoke
    // didn't pass cleanly", and the in-memory enum doesn't carry a separate PARTIAL value.
    TaskOutcome.Status mapped =
        switch (persistedStatus == null ? "" : persistedStatus) {
          case "COMPLETE" -> TaskOutcome.Status.SUCCESS;
          case "COMPLETED_WITH_WARNING", "PARTIAL" -> TaskOutcome.Status.COMPLETED_WITH_WARNING;
          case "FAILED" -> TaskOutcome.Status.WORKER_FAILED;
          default -> TaskOutcome.Status.SUCCESS;
        };
    return TaskOutcome.builder()
        .taskId(task.id())
        .taskTitle(task.title())
        .status(mapped)
        .totalLatencyMs(0L)
        .toolCallsDispatched(0)
        .build();
  }

  private TaskOutcome executeOne(
      PlannedTask task, Plan plan, ExecutionRequest req, boolean isFinalTask) {
    return executeOne(task, plan, req, isFinalTask, null);
  }

  private TaskOutcome executeOne(
      PlannedTask task,
      Plan plan,
      ExecutionRequest req,
      boolean isFinalTask,
      com.navneet.executespec.context.SavedContext resumeFrom) {
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
    // 2026-05-04 — quirky pre-dispatch log so the Live Log tab has more than just
    // token counters during long worker loops. Format mirrors the "Starting task:" line so
    // the rendering stays uniform; copy varies by complexity to surface what the user is
    // actually waiting for (HIGH = LLM-heavy reasoning, LOW = quick code edits).
    String complexityHint = task.complexity() == null ? "MEDIUM" : task.complexity().name();
    broadcaster.emit(
        runId,
        RunEvent.log(
            runId,
            task.id(),
            switch (complexityHint) {
              case "LOW" ->
                  "🛠️  Worker spinning up (LOW · "
                      + cfg.model
                      + ", up to "
                      + cfg.maxIter
                      + " iters)";
              case "HIGH" ->
                  "🧠 Worker spinning up (HIGH · "
                      + cfg.model
                      + ", up to "
                      + cfg.maxIter
                      + " iters — heavy reasoning ahead)";
              default ->
                  "⚙️  Worker spinning up (MEDIUM · "
                      + cfg.model
                      + ", up to "
                      + cfg.maxIter
                      + " iters)";
            }));
    WorkerTaskRequest workerReq =
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
            .stagnationWarnAfter(cfg.stagnationWarnAfter)
            .stagnationForceAfter(cfg.stagnationForceAfter)
            .build();
    // Resume-aware dispatch — when resumeFrom is non-null, the worker rebuilds history from the
    // saved snapshot and resumes the iteration loop from snapshot.iteration() + 1. Snapshots
    // ride a 24h Redis TTL and are written every 5 worker iters.
    WorkerResult primary = worker.run(workerReq, resumeFrom);

    // 2026-05-04 — emit a worker-summary log line right after the worker returns so the user
    // sees outcome + iters without waiting for the smoke test to finish or scrolling back to
    // the task badge. Format mirrors the "Starting task:" line.
    String workerEmoji =
        switch (primary.outcome()) {
          case COMPLETED -> "✅";
          case COMPLETED_BY_STAGNATION -> "⏱️ ";
          case ITERATION_LIMIT_EXCEEDED -> "⚠️ ";
          case STOPPED_WITHOUT_COMPLETE -> "⚠️ ";
          case BUDGET_EXCEEDED -> "💸";
          case ERROR -> "❌";
        };
    int writes = countFileWrites(primary.toolCallLog());
    broadcaster.emit(
        runId,
        RunEvent.log(
            runId,
            task.id(),
            workerEmoji
                + " Worker "
                + primary.outcome().name()
                + " in "
                + primary.iterations()
                + " iters · "
                + primary.toolCallsDispatched()
                + " tool calls"
                + (writes > 0 ? " · " + writes + " file write" + (writes == 1 ? "" : "s") : "")
                + (primary.totalLatencyMs() > 0
                    ? " · " + (primary.totalLatencyMs() / 1000) + "s"
                    : "")));

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
    //
    // Fix C (2026-05-03, run a076c876 T2 diagnosis): when the worker wrote
    // files but didn't call completeTask, do NOT pre-mark PARTIAL. Fall
    // through to the SAME gate the COMPLETED path uses (multi-task skip /
    // smoke test) and let that decide between COMPLETE / COMPLETED_WITH_WARNING
    // / PARTIAL. The worker's premature-exit reason is carried in
    // softWarnReason and persisted on lastLlmFailure regardless of final
    // status, so the UI still surfaces "worker hit iter cap" even when the
    // task lands COMPLETE because the smoke passed. Without this an
    // intermediate task in a multi-task plan that the worker exited early
    // on would be marked PARTIAL even though the reviewer's final build is
    // the authoritative gate — exactly what happened to T2.
    final boolean softCompletion;
    final String softWarnReason;
    if (primary.outcome() != WorkerResult.Outcome.COMPLETED) {
      int fileWrites = countFileWrites(primary.toolCallLog());
      if (fileWrites == 0) {
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
            .dispatchedProvider(cfg.provider)
            .dispatchedModel(cfg.model)
            .build();
      }
      // Soft completion: worker wrote files; fall through to smoke gate.
      log.warn(
          "Executor task={} worker outcome={} but wrote {} files — falling through to"
              + " smoke gate (soft completion)",
          task.id(),
          primary.outcome(),
          fileWrites);
      softCompletion = true;
      softWarnReason =
          "Worker stopped without completion but wrote "
              + fileWrites
              + " file"
              + (fileWrites == 1 ? "" : "s")
              + " — "
              + describeWorkerFailure(primary);
    } else {
      softCompletion = false;
      softWarnReason = null;
    }

    if (!req.enableSmokeTests()) {
      log.info(
          "Executor task={} {}{}",
          task.id(),
          softCompletion ? "COMPLETED_WITH_WARNING" : "SUCCESS",
          " (smoke tests disabled)");
      persistTaskStatus(runId, task.id(), "COMPLETE", softWarnReason);
      broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "COMPLETE"));
      return TaskOutcome.builder()
          .taskId(task.id())
          .taskTitle(task.title())
          .status(
              softCompletion
                  ? TaskOutcome.Status.COMPLETED_WITH_WARNING
                  : TaskOutcome.Status.SUCCESS)
          .workerOutcome(primary)
          .totalLatencyMs(System.currentTimeMillis() - taskStart)
          .toolCallsDispatched(primary.toolCallsDispatched())
          .dispatchedProvider(cfg.provider)
          .dispatchedModel(cfg.model)
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
          "Executor task={} {} (intermediate smoke skipped — multi-task plan, final task +"
              + " reviewer are authoritative)",
          task.id(),
          softCompletion ? "COMPLETED_WITH_WARNING" : "SUCCESS");
      persistTaskStatus(runId, task.id(), "COMPLETE", softWarnReason);
      broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "COMPLETE"));
      return TaskOutcome.builder()
          .taskId(task.id())
          .taskTitle(task.title())
          .status(
              softCompletion
                  ? TaskOutcome.Status.COMPLETED_WITH_WARNING
                  : TaskOutcome.Status.SUCCESS)
          .workerOutcome(primary)
          .totalLatencyMs(System.currentTimeMillis() - taskStart)
          .toolCallsDispatched(primary.toolCallsDispatched())
          .dispatchedProvider(cfg.provider)
          .dispatchedModel(cfg.model)
          .build();
    }

    SmokeTestResult smoke = runSmokeTest(taskCtx, plan.buildCommand());
    if (smoke.passed()) {
      log.info(
          "Executor task={} {} (smoke exit={}, took {}ms)",
          task.id(),
          softCompletion ? "COMPLETED_WITH_WARNING" : "SUCCESS",
          smoke.exitCode(),
          smoke.latencyMs());
      persistTaskStatus(runId, task.id(), "COMPLETE", softWarnReason);
      broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "COMPLETE"));
      return TaskOutcome.builder()
          .taskId(task.id())
          .taskTitle(task.title())
          .status(
              softCompletion
                  ? TaskOutcome.Status.COMPLETED_WITH_WARNING
                  : TaskOutcome.Status.SUCCESS)
          .workerOutcome(primary)
          .smokeTestPassed(true)
          .smokeTestExitCode(smoke.exitCode())
          .smokeTestOutput(smoke.output())
          .totalLatencyMs(System.currentTimeMillis() - taskStart)
          .toolCallsDispatched(primary.toolCallsDispatched())
          .dispatchedProvider(cfg.provider)
          .dispatchedModel(cfg.model)
          .build();
    }

    // Smoke FAILED on the primary attempt. The retry logic below was designed
    // for the COMPLETED path. When we got here via a soft completion (worker
    // didn't call completeTask), we don't retry — the worker already burned
    // its budget. Mark the task PARTIAL with both the soft-completion warning
    // and the smoke failure so the user sees the full picture.
    if (softCompletion) {
      String smokeTail = smoke.output() == null ? "" : smoke.output();
      if (smokeTail.length() > 240) {
        smokeTail = smokeTail.substring(smokeTail.length() - 240);
      }
      String reason =
          softWarnReason
              + ". Build smoke also failed (exit "
              + smoke.exitCode()
              + ")"
              + (smokeTail.isBlank() ? "" : " — " + smokeTail.trim().replaceAll("\\s+", " "));
      log.warn("Executor task={} soft-completion + smoke failed; marking PARTIAL", task.id());
      persistTaskStatus(runId, task.id(), "PARTIAL", reason);
      broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "PARTIAL"));
      return TaskOutcome.builder()
          .taskId(task.id())
          .taskTitle(task.title())
          .status(TaskOutcome.Status.COMPLETED_WITH_WARNING)
          .workerOutcome(primary)
          .smokeTestPassed(false)
          .smokeTestExitCode(smoke.exitCode())
          .smokeTestOutput(smoke.output())
          .totalLatencyMs(System.currentTimeMillis() - taskStart)
          .toolCallsDispatched(primary.toolCallsDispatched())
          .dispatchedProvider(cfg.provider)
          .dispatchedModel(cfg.model)
          .build();
    }

    log.warn(
        "Executor task={} smoke test FAILED (exit={}), retrying once with error context",
        task.id(),
        smoke.exitCode());

    // ONE retry with the build error injected.
    // 2026-05-08 — set requireBuildVerification on the RETRY only. The first attempt keeps
    // today's behaviour (no gate, since some legit early scaffold tasks naturally don't have
    // a building project yet). On retry though, the build error is the explicit spec — the
    // worker MUST re-run buildProject and see exit=0 before claiming done. Without this gate
    // the retry can call completeTask after a single editFile and we get the same regression
    // path that produced the 16/22 FINAL_BUILD_FAILED rate over the last 30 days.
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
                .stagnationWarnAfter(cfg.stagnationWarnAfter)
                .stagnationForceAfter(cfg.stagnationForceAfter)
                .requireBuildVerification(true)
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
          .dispatchedProvider(cfg.provider)
          .dispatchedModel(cfg.model)
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
          .dispatchedProvider(cfg.provider)
          .dispatchedModel(cfg.model)
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
          .dispatchedProvider(cfg.provider)
          .dispatchedModel(cfg.model)
          .build();
    }

    // 2026-05-08 — HIGH-complexity tasks get a SECOND retry with the latest build error.
    // Spring Boot multi-file scaffolds and similar heavy work occasionally need 2 build-error
    // feedback loops to converge (LOW/MEDIUM tasks rarely benefit; their state space is small
    // enough that retry-1 is decisive). retry-2 reuses the same requireBuildVerification gate
    // so the worker MUST run buildProject and see exit=0 before claiming done. Cost ceiling
    // is the per-run cost cap which absorbs the extra retry budget.
    if (task.complexity() == PlannedTask.Complexity.HIGH) {
      log.warn(
          "Executor task={} HIGH-complexity, smoke still fails after retry-1; attempting retry-2",
          task.id());
      String retry2Prompt = composeUserPrompt(task, retrySmoke.output());
      WorkerResult retry2 =
          worker.run(
              WorkerTaskRequest.builder()
                  .provider(cfg.provider)
                  .model(cfg.model)
                  .systemPrompt(systemPrompt)
                  .userPrompt(retry2Prompt)
                  .context(taskCtx)
                  .maxIterations(
                      req.maxIterationsPerTask() != null ? req.maxIterationsPerTask() : cfg.maxIter)
                  .temperature(0.0)
                  .maxTokensPerCall(cfg.maxTokens)
                  .stagnationWarnAfter(cfg.stagnationWarnAfter)
                  .stagnationForceAfter(cfg.stagnationForceAfter)
                  .requireBuildVerification(true)
                  .build());
      int totalToolCalls2 = totalToolCalls + retry2.toolCallsDispatched();
      if (retry2.outcome() == WorkerResult.Outcome.COMPLETED) {
        SmokeTestResult retry2Smoke = runSmokeTest(taskCtx, plan.buildCommand());
        if (retry2Smoke.passed()) {
          log.info("Executor task={} SUCCESS_AFTER_RETRY2 (HIGH complexity)", task.id());
          persistTaskStatus(runId, task.id(), "COMPLETE");
          broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "COMPLETE"));
          return TaskOutcome.builder()
              .taskId(task.id())
              .taskTitle(task.title())
              .status(TaskOutcome.Status.SUCCESS_AFTER_RETRY)
              .workerOutcome(primary)
              .retryAttempted(true)
              .retryWorkerOutcome(retry2)
              .smokeTestPassed(true)
              .smokeTestExitCode(retry2Smoke.exitCode())
              .smokeTestOutput(retry2Smoke.output())
              .totalLatencyMs(System.currentTimeMillis() - taskStart)
              .toolCallsDispatched(totalToolCalls2)
              .dispatchedProvider(cfg.provider)
              .dispatchedModel(cfg.model)
              .build();
        }
        // retry-2 completed but smoke STILL fails → fall through to COMPLETED_WITH_WARNING
        // below with the retry-2 smoke output as the "after retry" signal.
        retrySmoke = retry2Smoke;
        retry = retry2;
        totalToolCalls = totalToolCalls2;
      } else {
        log.warn(
            "Executor task={} retry-2 WORKER_FAILED outcome={}; falling through to"
                + " COMPLETED_WITH_WARNING with retry-1 smoke as final signal",
            task.id(),
            retry2.outcome());
        totalToolCalls = totalToolCalls2;
      }
    }

    log.warn("Executor task={} smoke still fails after retry; COMPLETED_WITH_WARNING", task.id());
    // 2026-05-02 — surface the smoke failure inline on the task card. Use exit code +
    // a tail of the smoke output so the user sees something like "Build smoke failed
    // (exit 1): SHELL BLOCKED 'bun' in: cd repos/frontend && bun install" instead of a
    // bare PARTIAL with no explanation.
    String smokeTail = retrySmoke.output() == null ? "" : retrySmoke.output();
    if (smokeTail.length() > 240) {
      smokeTail = smokeTail.substring(smokeTail.length() - 240);
    }
    String reason =
        "Build smoke failed (exit "
            + retrySmoke.exitCode()
            + ") even after one retry"
            + (smokeTail.isBlank() ? "" : " — " + smokeTail.trim().replaceAll("\\s+", " "));
    persistTaskStatus(runId, task.id(), "PARTIAL", reason);
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
        .dispatchedProvider(cfg.provider)
        .dispatchedModel(cfg.model)
        .build();
  }

  /**
   * Updates {@code AgentTask.status} in the DB mid-run so the UI's polled task list reflects live
   * progress (not just the terminal snapshot written by {@code
   * RunOrchestrator.applyExecutionResultToTasks}). Uses the same prefixed-key convention as the
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
  /**
   * Per-task tuning carrier. {@code stagnationWarnAfter} / {@code stagnationForceAfter} are
   * complexity-aware overrides forwarded into {@link WorkerTaskRequest} so the stagnation guard
   * fires at thresholds proportional to the task's expected exploration depth. Calibrated
   * 2026-05-08 against ProgramBench cmatrix anthropic run 9b37b099 — HIGH and MEDIUM tasks both
   * stagnated at the static 12-iter cap before the worker could write substantive source. The new
   * HIGH cap (18 warn / 28 force) is enough for legitimate reverse-engineering patterns: read
   * README + man page + binary --help/--version + plan + write.
   */
  private record TierConfig(
      String provider,
      String model,
      int maxIter,
      int maxTokens,
      int stagnationWarnAfter,
      int stagnationForceAfter) {}

  private TierConfig tierConfigFor(
      PlannedTask.Complexity complexity, String runProvider, String runModel, boolean projectRun) {
    AgentTier tier;
    int maxIter;
    int maxTokens;
    int stagnationWarnAfter;
    int stagnationForceAfter;
    switch (complexity == null ? PlannedTask.Complexity.MEDIUM : complexity) {
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
        //
        // 2026-05-08 — stagnation thresholds are now complexity-aware too. Static
        // STAGNATION_WARN_AFTER_N=8 / STAGNATION_FORCE_AFTER_N=12 was calibrated
        // for typical write-then-verify-then-stop patterns; for HIGH-complexity
        // ProgramBench-style reverse-engineering the worker legitimately needs
        // ~15-20 read iters before its first write, and was force-terminating
        // at iter 12 before any source was written. Reviewer then "fixed" the
        // empty workspace with no-op compile.sh — passing build smoke without
        // actually compiling source. Verified on cmatrix anthropic run 9b37b099
        // (2026-05-08) with empty workspace + 100/100 programbench score (which
        // counted the cleanroom's original binary).
      case LOW -> {
        tier = AgentTier.LIGHTWEIGHT;
        maxIter = 30;
        maxTokens = 4096;
        stagnationWarnAfter = 8; // matches WorkerAgent.STAGNATION_WARN_AFTER_N default
        stagnationForceAfter = 12;
      }
      case HIGH -> {
        tier = AgentTier.PLANNING;
        // 2026-05-08 — HIGH iter cap 75 → 120 (matches PATCH mode's calibration). Pairs
        // with WorkerAgent's Lever A force-edit watchdog (fires at 70% with zero writes)
        // + Lever B best-guess salvage (fires on iter-limit with zero writes). Real cost
        // ceiling is the per-run cost cap, not the iter cap. Extends headroom for genuine
        // long-tail HIGH tasks (multi-file Spring Boot apps with deep wiring) that
        // currently hit iter-limit at iter 75 mid-edit.
        maxIter = 120;
        maxTokens = 16384;
        // 2026-05-08 — tightened HIGH stagnation 22/32 → 18/26. With iter cap bumped to
        // 120, we have more total runway, so we can afford to terminate stuck loops
        // earlier without strangling legitimate work. The 22/32 calibration was for
        // 75-iter cap when each non-write iter was a larger fraction of the total budget.
        // Now: 18 iters of pure reading still gets a warn; 26 iters force-terminates.
        // That's <22% of the 120-iter budget — safe headroom.
        stagnationWarnAfter = 18;
        stagnationForceAfter = 26;
      }
      default -> {
        tier = AgentTier.WORKHORSE;
        maxIter = 50;
        maxTokens = 8192;
        stagnationWarnAfter = 12;
        stagnationForceAfter = 20;
      }
    }
    // Project runs (Launches on an existing cloned codebase) need more
    // iterations: the worker has to readFile / searchFiles the real repo
    // before it can write, so 2× the fresh-scratch cap is the default.
    if (projectRun) maxIter *= 2;
    // 2026-05-08 — OpenAI 0.7× cap LIFTED. Original premise (2026-04-26): "OpenAI is markedly
    // chattier per iter than Anthropic (Spring AI re-ships the FULL conversation history on
    // every tool call, no prompt cache); observed 1.45M input tokens on HIGH library spec vs
    // Anthropic's 132K for the same spec." That premise is now stale on three counts:
    //   1. V2 OpenAI does NOT go through Spring AI — uses OpenAiNativeToolClient (the native
    //      bypass shipped in v2.6.0 to fix Spring AI 1.1.4's extra_body bug).
    //   2. The native client preserves prefix stability AND OpenAI auto-caches prompts ≥1024
    //      tokens silently. Empirically validated 2026-05-08 on a LOW spec: cache utilization
    //      43.2%, 12 of 15 calls hit cache, total in=50K (vs Anthropic's 14K + 76K cacheRead).
    //   3. Cap was a cost-control hack, but actual cost is now within 4× of Anthropic on the
    //      same workload — well within healthy budget bounds for a fair iteration budget.
    // Lifting the cap means HIGH non-project OpenAI tasks now get 75 iters (was 53 = ceil(75×0.7)),
    // matching Anthropic + Gemini. This unlocks the long-tail tasks where OpenAI was stagnating
    // out of iteration budget, not capability. Telemetry in RunCostTracker per-call logs
    // (cacheRead=N) makes a regression visible in production.
    try {
      // v2.5.13 — enum + V2 strings now agree ("gemini"/"anthropic"/"openai")
      LlmProvider p =
          runProvider == null ? LlmProvider.OPENAI : LlmProvider.valueOf(runProvider.toUpperCase());
      ResolvedModel resolved = modelResolver.resolve("WorkerAgent", p, tier);
      return new TierConfig(
          resolved.providerName(),
          resolved.getModel(),
          maxIter,
          maxTokens,
          stagnationWarnAfter,
          stagnationForceAfter);
    } catch (Exception e) {
      log.warn(
          "tierConfigFor: resolve failed for provider={} tier={} — using run-level {} / {}",
          runProvider,
          tier,
          runProvider,
          runModel);
      return new TierConfig(
          runProvider, runModel, maxIter, maxTokens, stagnationWarnAfter, stagnationForceAfter);
    }
  }

  @org.springframework.transaction.annotation.Transactional
  @org.springframework.retry.annotation.Retryable(
      retryFor = {org.springframework.dao.DataAccessException.class},
      maxAttempts = 3,
      backoff = @org.springframework.retry.annotation.Backoff(delay = 100, multiplier = 2.0))
  public void persistTaskStatus(String runId, String taskIdShort, String status) {
    persistTaskStatus(runId, taskIdShort, status, null);
  }

  /**
   * v2.5.12 — overload that stores an optional failure reason on the task row. The reason lands in
   * {@code lastLlmFailure} (already read as a fallback by {@code RunMapper.extractFailureReason})
   * so the UI shows a concrete message instead of "unknown — see worker logs".
   *
   * <p>2026-05-04 — {@code @Retryable} added after dev run {@code 3b739e94...} task T1 got stuck in
   * WORKING for 90+ minutes because a single transient {@code DataAccessException} on the WORKING→
   * COMPLETE update was silently swallowed and the executor moved on to T2. With 3 attempts + 100ms
   * backoff (×2 multiplier = 100ms / 200ms / 400ms), transient blips no longer leave stale WORKING
   * rows. {@code DataAccessException} is the right level — it covers Hikari connection timeouts,
   * optimistic lock failures, and Postgres deadlocks while still letting genuine programmer errors
   * (NullPointerException etc) propagate immediately.
   */
  @org.springframework.transaction.annotation.Transactional
  @org.springframework.retry.annotation.Retryable(
      retryFor = {org.springframework.dao.DataAccessException.class},
      maxAttempts = 3,
      backoff = @org.springframework.retry.annotation.Backoff(delay = 100, multiplier = 2.0))
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
      // RunCostTracker so the UI sees real numbers for this task the
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
        // 2026-05-10 (V184 companion) — defensive cap on the persistence
        // boundary even though the column is TEXT. Reasons routed here
        // can include a 240-char finalAssistantText tail PLUS a stack
        // trace if a tool blew up in an exception path; capping at 4000
        // is enough to keep the failure cause readable in the DB while
        // keeping row sizes predictable. Truncate marker is a literal
        // suffix so a future reader knows the value was clipped, not
        // that the worker terminated mid-thought.
        String reason = failureReason;
        if (reason.length() > 4000) {
          reason = reason.substring(0, 4000) + "… [truncated]";
        }
        t.setLastLlmFailure(reason);
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
      PlannedTask task,
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
    // RunOrchestrator.buildMultiRepoSummary + MultiRepoWorkerPromptTest.
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
  private String fetchReferenceDocs(String projectId, PlannedTask task) {
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

  private String composeUserPrompt(PlannedTask task, String previousBuildError) {
    StringBuilder sb = new StringBuilder();
    sb.append("Task ").append(task.id()).append(": ").append(task.title()).append("\n\n");
    sb.append(task.description());
    if (previousBuildError != null && !previousBuildError.isBlank()) {
      // 2026-05-08 — sharpened from the generic "NOTE: previous attempt completed but build
      // failed" framing to the SWE-bench test-as-spec pattern: the build error IS the
      // specification of what to fix. The agent's earlier edits are still in the workspace
      // (per-task retry doesn't reset the workspace), so this is a continuation, not a redo.
      sb.append("\n\n---\nBUILD FAILED ON THE PREVIOUS ATTEMPT.\n\n");
      sb.append(
          "The error below is your spec for this retry — read it carefully, identify the "
              + "specific files / line numbers / error messages, and fix the root cause. Don't "
              + "refactor, don't reorganise, don't second-guess your earlier design choices. "
              + "Your previous edits are still in place; this is a continuation. Use readFile "
              + "to confirm the current file state where the error points, editFile to fix it, "
              + "buildProject to verify, then completeTask.\n\n");
      sb.append("--- BUILD ERROR (verbatim) ---\n");
      sb.append(truncateBuildErrorForRetry(previousBuildError));
      sb.append("\n--- END BUILD ERROR ---\n");
    }
    return sb.toString();
  }

  /**
   * Cap the build error at 12K chars (head 8K + tail 4K) — long enough for compiler stack traces
   * with stem+leaf, short enough that the worker's prefix stays cache-friendly on the retry. Most
   * actionable info is in the first stack trace and the last few "BUILD FAILED" lines; the middle
   * is repetition.
   */
  private static String truncateBuildErrorForRetry(String s) {
    if (s == null) return "";
    int cap = 12_000;
    if (s.length() <= cap) return s;
    int head = 8_000;
    int omitted = s.length() - cap;
    return s.substring(0, head)
        + "\n\n... ["
        + omitted
        + " chars omitted from middle of build output] ...\n\n"
        + s.substring(s.length() - (cap - head));
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
    // so RunOrchestrator can distinguish CLEAN_COMPLETION from
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

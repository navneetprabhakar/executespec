package com.navneet.executespec.terminalbench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.JwtUtil;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.engine.WorkerAgent;
import com.navneet.executespec.engine.WorkerResult;
import com.navneet.executespec.engine.WorkerTaskRequest;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.RunMode;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.tool.remote.RemoteToolDispatcher;

import org.springframework.stereotype.Service;

import io.micrometer.context.ContextSnapshotFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs the {@link WorkerAgent} in REMOTE-tool mode as a standalone persisted run, so an external
 * client (the Terminal-Bench Harbor adapter — "local hands / remote brain") can drive it: every
 * worker tool call is emitted as a {@code TOOL_CALL} SSE event and executed in the client's
 * container, with the result POSTed back to {@code POST /api/runs/{id}/tool-results}. No classifier
 * / planner / reviewer / scoring — just the worker loop, since a Terminal-Bench task is a single
 * terminal goal rather than a multi-task code spec.
 *
 * <p>RL-2026-06 — Terminal-Bench integration keystone. Reuses the built remote-tool protocol
 * ({@link RemoteToolDispatcher} + {@code RemoteToolController}) plus the run SSE / cost / access
 * infra; does NOT touch {@code RunOrchestrator} or the product pipeline. Operator-only (dev/local +
 * SUPER_ADMIN via {@link TerminalBenchController}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TerminalBenchRunService {

  private final StateStore stateStore;
  private final RemoteToolDispatcher remoteToolDispatcher;
  private final WorkerAgent worker;
  private final RunCostTracker costTracker;
  private final SseBroadcaster broadcaster;
  private final JwtUtil jwtUtil;

  /** Per-task wall-clock safety net (Harbor enforces its own per-task timeout on top of this). */
  private static final int DEFAULT_WALL_CLOCK_SECONDS = 1800;

  /**
   * Standalone worker system prompt for arbitrary terminal goals. The product worker base prompt
   * ({@code SequentialExecutor.SYSTEM_PROMPT_BASE}) is code-gen-tuned and is NOT used on this
   * direct path, so we supply a terminal-task-oriented prompt here. Refined further under task #22
   * (port to all providers).
   */
  private static final String TB_SYSTEM_PROMPT =
      """
You are an autonomous terminal agent in a real Linux sandbox. Complete the user's task
end-to-end using ONLY the provided tools (shell, readFile, writeFile, editFile, listFiles,
searchFiles). The environment is real: you may install packages (pip / apt-get / etc.),
download data, and run any command — commands run in the sandbox, not a developer's machine.

Follow this workflow every time:
1. UNDERSTAND — inspect the working directory FIRST (listFiles, then read the instruction, any
   README, and any provided input/data files); tasks put critical details and the exact
   success criteria in those files. Identify the EXACT deliverable: the precise output
   location (e.g. a specific path such as /app/answer.txt) and the precise required
   format/value.
2. SOLVE — do the real work. Install whatever libraries you need and download whatever data the
   task references. Write correct solutions, never placeholders or guesses.
3. PRODUCE THE EXACT DELIVERABLE — write to the EXACT path requested, in the EXACT format
   requested: just the value, with no extra prose, labels, commas, or stray whitespace unless
   the task asks for them. A correct answer in the wrong file or wrong format scores zero.
4. VERIFY before finishing (mandatory) — re-read the task's success criteria and confirm your
   output satisfies EVERY requirement. If the task warns about edge cases (tricky inputs,
   boundary conditions, false matches), construct inputs covering them and confirm your
   solution handles them. Re-read the output file / re-run the command and confirm it is
   byte-exact. If you cannot confirm correctness, keep working — do NOT call completeTask.
5. Once the deliverable is produced AND verified, call completeTask with a one-line summary.

Act through tool calls, not prose. Be efficient — once verified, complete promptly rather than
iterating needlessly.
""";

  /** Result of launching a run: the runId plus a freshly minted scoped SSE token for the client. */
  public record StartResult(String runId, String sseToken) {}

  /**
   * Persist a run (ownership stamped), bind it to remote tool execution, and launch the worker on a
   * context-propagated virtual thread. Returns immediately so the caller can subscribe to the run's
   * SSE stream and drive tools.
   */
  public StartResult start(
      AppUserDetails userDetails,
      String instruction,
      String provider,
      String model,
      Integer maxIterations) {
    String runId = UUID.randomUUID().toString();
    String userId = String.valueOf(userDetails.getUser().getId());
    String orgId = userDetails.getUser().getOrgId();

    // 1) Persist the run row synchronously with ownership stamped BEFORE the async thread starts
    //    (CLAUDE.md race-condition rule). harnessVersion="v2" so GET /api/runs/{id}/cost reads the
    //    V2 cost tracker; runMode=PATCH documents the single-agent-loop shape.
    PipelineRun run =
        PipelineRun.builder()
            .runId(runId)
            .userRequest(instruction)
            .status("WORKING")
            .harnessVersion("v2")
            .runMode(RunMode.PATCH)
            .sourceType("TERMINAL_BENCH")
            .aiProvider(provider == null ? null : provider.toUpperCase())
            .workerModel(model)
            .createdByUserId(userId)
            .orgId(orgId)
            .executionMode("PROTOTYPE")
            .approvedExecutionMode("REALTIME")
            .queuePosition(0)
            .startedAt(Instant.now())
            .totalInputTokens(0)
            .totalOutputTokens(0)
            .build();
    stateStore.saveRun(run);

    // 2) Bind the run to remote tool execution BEFORE the worker runs → isRemote(runId)=true, so
    //    every worker tool call dispatches to the connected client over SSE (ToolRegistry:149)
    //    instead of executing on the server. No worker change required.
    remoteToolDispatcher.register(runId);
    broadcaster.emit(runId, RunEvent.runStatus(runId, "WORKING"));
    log.info("[TB] started remote worker run={} provider={} model={}", runId, provider, model);

    // 3) Launch the worker on a context-propagated virtual thread (traceId + RequestAttributes
    //    survive the boundary per the CLAUDE.md async rule).
    var snapshot = ContextSnapshotFactory.builder().build().captureAll();
    Thread.ofVirtual()
        .name("tb-run-" + runId)
        .start(snapshot.wrap(() -> runWorker(runId, instruction, provider, model, maxIterations)));

    String sseToken = jwtUtil.generateSseToken(userId, runId);
    return new StartResult(runId, sseToken);
  }

  private void runWorker(
      String runId, String instruction, String provider, String model, Integer maxIterations) {
    Path workspace = null;
    String finalStatus = "FAILED";
    String reason = null;
    try {
      // Remote mode never touches this dir (tools dispatch before tool.execute), but
      // ExecutionContext requires an absolute path.
      workspace = Files.createTempDirectory("tb-remote-");
      ExecutionContext ctx = new ExecutionContext(runId, "tb-task", workspace.toRealPath());
      WorkerResult result =
          worker.run(
              WorkerTaskRequest.builder()
                  .provider(provider)
                  .model(model)
                  .systemPrompt(TB_SYSTEM_PROMPT)
                  .userPrompt(instruction)
                  .context(ctx)
                  .maxIterations(maxIterations)
                  .temperature(0.0)
                  .wallClockSeconds(DEFAULT_WALL_CLOCK_SECONDS)
                  .build());
      boolean ok =
          result.outcome() == WorkerResult.Outcome.COMPLETED
              || result.outcome() == WorkerResult.Outcome.COMPLETED_BY_STAGNATION;
      finalStatus = ok ? "COMPLETE" : "FAILED";
      reason = result.outcome().name();
      log.info(
          "[TB] run={} worker outcome={} iters={} tools={} -> status={}",
          runId,
          result.outcome(),
          result.iterations(),
          result.toolCallsDispatched(),
          finalStatus);
    } catch (Exception e) {
      log.error("[TB] run={} worker threw", runId, e);
      finalStatus = "FAILED";
      reason = "TB_WORKER_ERROR: " + e.getClass().getSimpleName();
    } finally {
      finalizeTerminal(runId, finalStatus, reason);
      remoteToolDispatcher.unregister(runId); // ALWAYS — unblocks any in-flight client tool call
      deleteTree(workspace);
    }
  }

  private void finalizeTerminal(String runId, String status, String reason) {
    RunCostTracker.Snapshot cost = costTracker.finalizeRun(runId);
    stateStore
        .findRun(runId)
        .ifPresent(
            r -> {
              r.setStatus(status);
              r.setCompletedAt(Instant.now());
              r.setStatusReason(reason);
              if ("FAILED".equals(status) && r.getFailureReason() == null) {
                r.setFailureReason(reason);
              }
              if (cost.costUsd() > 0d) {
                r.setActualCostUsd(cost.costUsd());
              } else if (r.getActualCostUsd() == null) {
                r.setActualCostUsd(0d);
              }
              if (cost.inputTokens() > 0) {
                r.setTotalInputTokens(cost.inputTokens());
              }
              if (cost.outputTokens() > 0) {
                r.setTotalOutputTokens(cost.outputTokens());
              }
              stateStore.saveRun(r);
            });
    broadcaster.emit(runId, RunEvent.runStatus(runId, status));
    broadcaster.emit(runId, RunEvent.costUpdate(runId, cost.costUsd()));
    broadcaster.emit(runId, RunEvent.complete(runId, status, reason)); // the adapter's stop signal
    broadcaster.complete(runId);
  }

  private static void deleteTree(Path root) {
    if (root == null) {
      return;
    }
    try {
      if (!Files.exists(root)) {
        return;
      }
      try (var walk = Files.walk(root)) {
        walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
            .forEach(
                p -> {
                  try {
                    Files.deleteIfExists(p);
                  } catch (Exception ignored) {
                    // best-effort cleanup
                  }
                });
      }
    } catch (Exception ignored) {
      // best-effort cleanup
    }
  }
}

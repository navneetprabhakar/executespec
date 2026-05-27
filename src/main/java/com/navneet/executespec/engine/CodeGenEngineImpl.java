package com.navneet.executespec.engine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.navneet.executespec.agent.PatchAgent;
import com.navneet.executespec.agent.WorkerResult;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.tool.ExecutionContext;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Phase 2 of the engine extraction (RL-2026-05-052) — the <b>wrap facade</b>.
 *
 * <p>"Wrap, don't move": delegates to today's single-task code-gen path ({@code PatchAgent.run} →
 * {@code WorkerAgent} → tools → {@code LlmRouter}) and maps in/out of the provider-agnostic {@link
 * CodeGenEngine} contract. Behaviour is byte-identical to a direct {@code PatchAgent.run} — this
 * just gives the pipeline + benchmark a stable seam to call. Later phases relocate the worker
 * internals into {@code engine/} and lift the run-row / cost coupling out behind an observer.
 *
 * <p><b>The caller owns setup</b> (per the contract): the workspace is already prepared and a
 * {@code PipelineRun} row for {@code request.runId()} already exists (the worker reads its {@code
 * hardCapUsd} + {@code sourceType} from it). The facade does NOT clone/checkout, write the DB, emit
 * SSE, or push.
 *
 * <p><b>Phase-2 caveat:</b> {@code request.budget()} + {@code request.verifyMode()} are honoured
 * via the existing path's own mechanisms today (the run-row {@code hardCapUsd}; {@code
 * PatchAgent}'s per-provider iter caps; the {@code sourceType}-scoped test-verification gate).
 * Explicit pass-through of budget/verifyMode lands when the worker internals move in (phase 4+).
 *
 * <p>Registered as a bean but <b>not yet injected anywhere</b> (parallel + unused) — so every
 * production run stays byte-identical until phase 3 routes the benchmark through it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeGenEngineImpl implements CodeGenEngine {

  private final PatchAgent patchAgent;
  private final RunCostTracker costTracker;

  @Override
  public EngineResult solve(EngineRequest request) {
    Path workspace = request.workspace();
    ExecutionContext ctx;
    try {
      String taskId = request.taskId() != null ? request.taskId() : "__root__";
      ctx = new ExecutionContext(request.runId(), taskId, workspace.toRealPath());
    } catch (IOException ioe) {
      log.error("engine: workspace realpath failed for run {}", request.runId(), ioe);
      return EngineResult.builder()
          .diff("")
          .verification(EngineVerification.none())
          .transcript(List.of())
          .cost(EngineCost.zero())
          .outcome(EngineOutcome.ERROR)
          .iterations(0)
          .toolCalls(0)
          .summary("workspace realpath failed: " + ioe.getMessage())
          .build();
    }

    // THE code-gen call — the existing production single-task path, synchronous.
    WorkerResult wr = patchAgent.run(request.provider(), request.model(), request.taskSpec(), ctx);

    return EngineResult.builder()
        .diff(captureDiff(workspace))
        .verification(EngineVerification.none()) // Phase 2: not gathered (the contract allows null)
        .transcript(wr.toolCallLog() == null ? List.of() : wr.toolCallLog())
        .cost(costOf(request.runId()))
        .outcome(mapOutcome(wr.outcome()))
        .iterations(wr.iterations())
        .toolCalls(wr.toolCallsDispatched())
        .summary(wr.completionSummary())
        .build();
  }

  private EngineCost costOf(String runId) {
    try {
      RunCostTracker.Snapshot s = costTracker.snapshot(runId);
      return new EngineCost(s.inputTokens(), s.outputTokens(), s.cacheReadTokens(), s.costUsd());
    } catch (Exception e) {
      return EngineCost.zero();
    }
  }

  /** Plain host-side {@code git diff} in the (prepared) workspace, run-infra excluded. */
  private String captureDiff(Path workspace) {
    try {
      ProcessBuilder pb =
          new ProcessBuilder(
              "git",
              "-C",
              workspace.toString(),
              "diff",
              "--",
              ".",
              ":(exclude).agent-context",
              ":(exclude)Dockerfile",
              ":(exclude).dockerignore",
              ":(exclude)__pycache__",
              ":(exclude).pytest_cache",
              ":(exclude)*.egg-info");
      pb.redirectErrorStream(true);
      Process p = pb.start();
      String out = new String(p.getInputStream().readAllBytes());
      if (!p.waitFor(120, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        return "";
      }
      return p.exitValue() == 0 ? out : "";
    } catch (IOException e) {
      log.warn("engine: git diff failed in {}: {}", workspace, e.getMessage());
      return "";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "";
    }
  }

  private static EngineOutcome mapOutcome(WorkerResult.Outcome o) {
    if (o == null) return EngineOutcome.ERROR;
    return switch (o) {
      case COMPLETED -> EngineOutcome.COMPLETED;
      case COMPLETED_BY_STAGNATION -> EngineOutcome.STAGNATED;
      case ITERATION_LIMIT_EXCEEDED -> EngineOutcome.ITER_LIMIT;
      case BUDGET_EXCEEDED -> EngineOutcome.BUDGET;
      case WALL_CLOCK_EXCEEDED -> EngineOutcome.WALL_CLOCK;
      case STOPPED_WITHOUT_COMPLETE -> EngineOutcome.STOPPED;
      case ERROR -> EngineOutcome.ERROR;
    };
  }
}

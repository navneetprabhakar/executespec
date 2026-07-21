package com.navneet.executespec.engine;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import jakarta.annotation.PostConstruct;

import com.navneet.executespec.agent.PromptAssembler;
import com.navneet.executespec.agent.PromptTrustBoundary;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.harness.SequentialExecutor;
import com.navneet.executespec.state.BenchmarkSourceType;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.testgen.BoundedProcess;
import com.navneet.executespec.tool.ExecutionContext;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Code-gen engine implementation for one prepared workspace.
 *
 * <p>Owns the single-task PATCH-style worker request construction directly: prompt assembly,
 * provider patch fragments, per-provider iteration caps, source-scoped test gate, common critic
 * flags, and result mapping. This keeps benchmark and patch-mode orchestration on one code-gen
 * surface; PATCH policy is owned directly here rather than through a compatibility wrapper.
 *
 * <p><b>The caller owns setup</b> (per the contract): the workspace is already prepared and a
 * {@code PipelineRun} row for {@code request.runId()} already exists (the worker reads its {@code
 * hardCapUsd} + {@code sourceType} from it). The facade does NOT clone/checkout, write the DB, emit
 * SSE, or push.
 *
 * <p>The caller still owns orchestration: clone/checkout, DB lifecycle, SSE, scoring, PR push, and
 * benchmark patch capture stay outside this class.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeGenEngineImpl implements CodeGenEngine {

  private static final int MAX_TOKENS_PER_CALL = 16_000;
  private static final int DEFAULT_MAX_ITERATIONS = 180;

  private final WorkerAgent workerAgent;
  private final PipelineRunRepository runRepo;
  private final FeatureFlagService featureFlagService;
  private final PromptAssembler promptAssembler;
  private final RunCostTracker costTracker;
  private final LlmExecutionPolicyFactory executionPolicyFactory;

  /** task 11 — deterministic repo-orientation warm start (nullable in tests ⇒ inert). */
  private final RepoOrientationService repoOrientationService;

  private String patchPrompt;

  @Override
  public EngineResult solve(EngineRequest request) {
    Path workspace = request.workspace();
    ExecutionContext ctx;
    try {
      String taskId = request.taskId() != null ? request.taskId() : "__root__";
      ctx =
          new ExecutionContext(
              request.runId(),
              taskId,
              workspace.toRealPath(),
              executionPolicyFactory.forRun(request.runId()));
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

    WorkerTaskRequest workerReq = workerRequest(request, ctx);
    WorkerResult wr = workerAgent.run(workerReq);

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

  private WorkerTaskRequest workerRequest(EngineRequest request, ExecutionContext ctx) {
    int maxIterations =
        request.budget() != null && request.budget().iterCap() != null
            ? request.budget().iterCap()
            : iterCapFor(request.provider());
    int stagnationDisabled = maxIterations;

    return WorkerTaskRequest.builder()
        .provider(request.provider())
        .model(request.model())
        .systemPrompt(buildSystemPrompt(request.provider()))
        .userPrompt(withOrientation(request.taskSpec(), ctx))
        .context(ctx)
        .maxIterations(maxIterations)
        .temperature(0.0)
        .maxTokensPerCall(MAX_TOKENS_PER_CALL)
        .stagnationWarnAfter(stagnationDisabled)
        .stagnationForceAfter(stagnationDisabled)
        .requireTestVerification(requireTestVerification(request))
        .wallClockSeconds(request.budget() == null ? null : request.budget().wallClockSeconds())
        .absoluteDeadlineEpochMs(
            request.budget() == null ? null : request.budget().absoluteDeadlineEpochMs())
        .shutdownReserveMs(request.budget() == null ? null : request.budget().shutdownReserveMs())
        .criticPass(featureFlagService.isEnabled("worker.critic-pass.enabled"))
        .verifyBeforeComplete(featureFlagService.isEnabled("worker.verify-before-complete.enabled"))
        // progress-v2 (V255, default OFF): novelty-based progress + repetition force + bounded
        // text-only retries. Especially relevant on this lane: v1 warn/force is disabled above
        // (stagnationDisabled), so v2's repetition trigger is the ONLY loop protection a
        // benchmark PATCH run gets.
        .progressV2(featureFlagService.isEnabled("worker.progress-v2.enabled"))
        // auto-verify (V259, default ON): server-side edit-burst test runs with failure-output
        // injection — the schema-free replacement for the reverted V209/V217 pair.
        .autoVerify(featureFlagService.isEnabled("worker.auto-verify.enabled"))
        .build();
  }

  /**
   * task 11 (worker.localization.enabled, default OFF): prepend the deterministic repo-orientation
   * warm-start block for existing-repo work. Fail-open — empty block appends nothing.
   */
  private String withOrientation(String taskSpec, ExecutionContext ctx) {
    String taskEvidence = PromptTrustBoundary.wrap("PATCH_TASK", taskSpec);
    if (repoOrientationService == null
        || !featureFlagService.isEnabled("worker.localization.enabled")) {
      return taskEvidence;
    }
    String orientation = repoOrientationService.orient(ctx.workspaceRoot(), taskSpec);
    return orientation.isBlank()
        ? taskEvidence
        : taskEvidence + "\n\n" + PromptTrustBoundary.wrap("REPOSITORY_ORIENTATION", orientation);
  }

  private boolean requireTestVerification(EngineRequest request) {
    if (request.verifyMode() == VerifyMode.NONE) return false;
    String src = runRepo.findById(request.runId()).map(PipelineRun::getSourceType).orElse("");
    // Shared classifier — same set LlmExecutionPolicyFactory.isControlledWorkerRun uses (RR-1590),
    // so the test-gate-off and no-failover behaviours cannot drift. TERMINAL_BENCH runs execute via
    // engine.WorkerAgent directly (not this PATCH path), so it is inert for them today, but keeping
    // one classifier means a future PATCH-shaped benchmark source is correct here automatically.
    if (BenchmarkSourceType.isControlledWorkerSource(src)) {
      return false;
    }
    // Historical PATCH default: customer autofix/finding sources require a recent passing runTests
    // call before completeTask. Blind benchmark sources opt out above because target tests are held
    // out by design.
    return true;
  }

  private String buildSystemPrompt(String provider) {
    if (!featureFlagService.isEnabled(SequentialExecutor.FLAG_PROVIDER_TUNING)) {
      return patchPrompt;
    }
    String fragment = loadProviderPatchFragment(provider);
    return fragment.isBlank() ? patchPrompt : patchPrompt + "\n\n" + fragment;
  }

  private String loadProviderPatchFragment(String provider) {
    if (provider == null || provider.isBlank()) return "";
    String key = provider.trim().toLowerCase();
    return promptAssembler.loadFileSafe("fragments/provider/" + key + "/patch.md");
  }

  @PostConstruct
  void loadPatchPrompt() {
    patchPrompt = promptAssembler.assemble("patch-agent.md");
  }

  private static int iterCapFor(String provider) {
    if (provider == null) return DEFAULT_MAX_ITERATIONS;
    return switch (provider.toLowerCase()) {
      case "anthropic" -> 220;
      case "openai" -> 150;
      case "gemini", "google" -> 180;
      case "openrouter", "deepseek", "fireworks" -> 220;
      default -> DEFAULT_MAX_ITERATIONS;
    };
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
              ":(exclude)*.egg-info",
              ":(exclude,glob)appendonlydir/**");
      pb.redirectErrorStream(true);
      // RR-1565: BoundedProcess makes the 120s deadline reachable and caps the captured output.
      // Reading stdout to EOF on this thread first (the old shape) let a stalled `git diff` — an
      // index lock, a hung filter/textconv driver, or a credential-helper prompt — pin the pipeline
      // thread forever, and the unbounded read materialised a large working tree whole into heap.
      // (EngineResult.diff() has no production reader today; this keeps the value bounded and
      // honest
      // rather than deleting the capture.)
      BoundedProcess.Outcome outcome = BoundedProcess.run(pb, Duration.ofSeconds(120));
      return outcome.finished() && outcome.exitCode() == 0 ? outcome.output() : "";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "";
    } catch (Exception e) {
      log.warn("engine: git diff failed in {}: {}", workspace, e.getMessage());
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
      case WALL_CLOCK_EXCEEDED, ABSOLUTE_DEADLINE_EXCEEDED -> EngineOutcome.WALL_CLOCK;
      case STOPPED_WITHOUT_COMPLETE -> EngineOutcome.STOPPED;
      case ERROR -> EngineOutcome.ERROR;
    };
  }
}

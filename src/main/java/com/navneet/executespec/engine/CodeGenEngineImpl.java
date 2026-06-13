package com.navneet.executespec.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PostConstruct;

import com.navneet.executespec.agent.PromptAssembler;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.harness.SequentialExecutor;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.tool.ExecutionContext;

import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Code-gen engine implementation for one prepared workspace.
 *
 * <p>Owns the single-task PATCH-style worker request construction directly: prompt assembly,
 * provider patch fragments, per-provider iteration caps, source-scoped test gate, common critic
 * flags, and result mapping. This keeps benchmark and patch-mode orchestration on one code-gen
 * surface instead of routing through the deprecated {@code PatchAgent} wrapper.
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

  private String patchPrompt;

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
        .userPrompt(request.taskSpec())
        .context(ctx)
        .maxIterations(maxIterations)
        .temperature(0.0)
        .maxTokensPerCall(MAX_TOKENS_PER_CALL)
        .stagnationWarnAfter(stagnationDisabled)
        .stagnationForceAfter(stagnationDisabled)
        .requireTestVerification(requireTestVerification(request))
        .wallClockSeconds(request.budget() == null ? null : request.budget().wallClockSeconds())
        .criticPass(featureFlagService.isEnabled("worker.critic-pass.enabled"))
        .verifyBeforeComplete(featureFlagService.isEnabled("worker.verify-before-complete.enabled"))
        .build();
  }

  private boolean requireTestVerification(EngineRequest request) {
    if (request.verifyMode() == VerifyMode.NONE) return false;
    String src = runRepo.findById(request.runId()).map(PipelineRun::getSourceType).orElse("");
    if (src != null && (src.startsWith("swebench") || src.startsWith("benchmark"))) {
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
    try {
      Resource r =
          new org.springframework.core.io.ClassPathResource(
              "prompts/fragments/provider/" + key + "/patch.md");
      if (!r.exists()) return "";
      return r.getContentAsString(StandardCharsets.UTF_8);
    } catch (Exception e) {
      log.debug("loadProviderPatchFragment({}) failed: {}", provider, e.getMessage());
      return "";
    }
  }

  @PostConstruct
  void loadPatchPrompt() {
    try {
      patchPrompt = promptAssembler.assemble("patch-agent.md");
    } catch (Exception e) {
      log.error("[CodeGenEngine] Failed to load classpath:prompts/patch-agent.md; using stub", e);
      patchPrompt =
          "You are a patch-mode coding agent. Make the minimum change that fixes the issue.";
    }
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

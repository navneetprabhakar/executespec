package com.navneet.executespec.agent;

import java.nio.charset.StandardCharsets;

import jakarta.annotation.PostConstruct;

import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.engine.WorkerAgent;
import com.navneet.executespec.engine.WorkerResult;
import com.navneet.executespec.engine.WorkerTaskRequest;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.tool.ExecutionContext;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Patch-mode entry point — single thin-agent loop for bug-fix shapes.
 *
 * <p>The architecture motivation lives in the discussion that produced V182 / {@link
 * com.navneet.executespec.state.RunMode}; this class is the runtime end of it. It does not
 * re-implement the loop — it delegates straight to {@link WorkerAgent} with carefully chosen
 * configuration so the existing context-snapshot / cost-tracking / tool-dispatch / metric plumbing
 * is reused unchanged. The differences from a build-mode worker call are:
 *
 * <ul>
 *   <li>One {@code WorkerTaskRequest} for the entire user request — no classifier, no planner, no
 *       per-task fragmentation. The model's working memory IS the plan.
 *   <li>Iteration cap raised to 75 and stagnation thresholds raised to (effectively) off so the
 *       agent can spend many iterations exploring before any write. On SWE-bench / bug-fix shapes,
 *       investigation is the work — premature stagnation force-terminate killed empty-patch runs.
 *   <li>Forced PLANNING-tier model regardless of upstream classification. Haiku-class models can't
 *       navigate 1000-file codebases reliably; the reasoning-gap is bigger than any harness tweak.
 *   <li>Higher per-call output cap so a long edit / multi-file patch fits in one tool-call.
 * </ul>
 *
 * <p>Tools available are exactly {@link com.navneet.executespec.tool.ToolRegistry#bindForWorker}
 * (everything except gitCommit). The git-diff capture happens at run-end inside the bench services'
 * {@code captureOnTerminalAsync} path; the agent never commits.
 *
 * <p>This class deliberately stays small: ~80 LOC of orchestration + one prompt template.
 * Improvements (better navigation primitives, retrieval, self-consistency vote) layer on top
 * without touching the orchestrator's branch logic.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PatchAgent {

  /**
   * Per-call output cap. Higher than the build-mode worker's per-tier caps because a SWE-bench fix
   * may produce one big edit rather than several small ones, and we don't want truncation to leave
   * a half-formed tool-call.
   */
  private static final int MAX_TOKENS_PER_CALL = 16_000;

  /**
   * Default iteration cap. Per-provider overrides apply (see {@link #iterCapFor}). Real ceiling
   * stays the cost cap; iter cap is defense-in-depth.
   */
  private static final int DEFAULT_MAX_ITERATIONS = 180;

  /**
   * 2026-05-08 — per-provider iteration cap tuning, derived from hard-matrix output-token
   * distribution. Empirical observation across 15 hard SWE-bench runs:
   *
   * <ul>
   *   <li><b>Anthropic claude-sonnet-4-6</b>: rich 32-67K output tokens per run, edits efficiently,
   *       benefits from longest runway. Cap: 220.
   *   <li><b>OpenAI gpt-5.4</b>: historically thin output, but still gets a meaningful larger
   *       safety net now that cost/cache accounting is stronger. Cap: 150.
   *   <li><b>Gemini gemini-3-pro-preview</b>: medium 13-67K output, $1-4 typical spend. Default.
   *       Cap: 180.
   * </ul>
   *
   * The caps are deliberately high because the dollar hard-cap and same-provider retry are now the
   * real safety rails. Iteration limits should stop pathological loops, not truncate a productive
   * reproduce -> edit -> verify cycle.
   */
  private static int iterCapFor(String provider) {
    if (provider == null) return DEFAULT_MAX_ITERATIONS;
    return switch (provider.toLowerCase()) {
      case "anthropic" -> 220;
      case "openai" -> 150;
      case "gemini", "google" -> 180;
        // 2026-05-28 (RL-2026-05-052, T3.12) — make the OpenRouter + native DeepSeek caps explicit
        // instead of falling through to DEFAULT_MAX_ITERATIONS=120. Empirically (2026-05-28 mixed10
        // 5-arm eval): OpenRouter-routed arms averaged 18 min/instance vs Anthropic's 8 min — the
        // gap is per-call latency, not iter count, so a 120-iter cap doesn't bite at the iter axis.
        // But these are families whose long-tail tasks need iter headroom (same rationale Anthropic
        // got bumped to 150 originally); pinning them to 150 here makes intent explicit + future-
        // proofs against the OpenRouter cap silently regressing if DEFAULT changes. Honest
        // calibration awaits DB-side iter-count analysis; the explicit case is the safe step.
      case "openrouter", "deepseek" -> 220;
      default -> DEFAULT_MAX_ITERATIONS;
    };
  }

  /**
   * Stagnation thresholds set to MAX_ITERATIONS so the guard never fires in patch mode. The
   * downstream cost cap and the iteration cap itself are the authoritative stop conditions.
   * Investigation-heavy tasks need this — premature force-terminate at iter 12 was the failure mode
   * that motivated the patch-mode rewrite.
   */
  private final WorkerAgent workerAgent;

  /** Reads the run's {@code source} to scope the test-verification gate (see {@link #run}). */
  private final PipelineRunRepository runRepo;

  /**
   * RL-2026-06 — gates the common critic/reviewer pass (#1) for PATCH runs (SWE-bench + the autofix
   * surfaces) behind {@code worker.critic-pass.enabled} (V231, default OFF) so production is
   * byte-identical until an eval justifies flipping it. The critic itself lives in the shared
   * {@link WorkerAgent}; PATCH reads the worker's {@code git diff} as the deliverable under review.
   */
  private final FeatureFlagService featureFlagService;

  /** Run a single thin-agent patch loop. Blocks until the worker terminates. */
  public WorkerResult run(String provider, String model, String userRequest, ExecutionContext ctx) {

    int maxIterations = iterCapFor(provider);
    int stagnationDisabled = maxIterations;

    log.info(
        "PatchAgent START run={} provider={} model={} maxIter={}",
        ctx.runId(),
        provider,
        model,
        maxIterations);

    // Scope the test-verification gate by run source. Blind benchmark runs (SWE-bench harness)
    // deliberately hold out the target test, so the "runTests must pass before completeTask" gate
    // can never be satisfied and the worker loops to the iter cap. Disable it for those
    // ("swebench*"/"benchmark*"); keep it ON for the customer autofix surfaces ("finding:*").
    String src = runRepo.findById(ctx.runId()).map(PipelineRun::getSourceType).orElse("");
    boolean requireTV =
        !(src != null && (src.startsWith("swebench") || src.startsWith("benchmark")));

    WorkerTaskRequest req =
        WorkerTaskRequest.builder()
            .provider(provider)
            .model(model)
            .systemPrompt(buildSystemPrompt())
            .userPrompt(userRequest)
            .context(ctx)
            .maxIterations(maxIterations)
            .temperature(0.0)
            .maxTokensPerCall(MAX_TOKENS_PER_CALL)
            .stagnationWarnAfter(stagnationDisabled)
            .stagnationForceAfter(stagnationDisabled)
            // V182 layer-3 — refuse completeTask in patch mode unless runTests was called
            // recently. Without this, prompt-only "verify before finishing" guidance can be
            // ignored or over-applied; the gate enforces test execution programmatically.
            // 2026-05-08 strengthened: gate now requires tests to PASS (failed:[] +
            // errored:[] + exit_code:0) not just be run — see WorkerAgent line ~470.
            .requireTestVerification(requireTV)
            // RL-2026-06 — common critic/reviewer pass (#1), flag-gated default OFF (V231) so PATCH
            // is byte-identical until eval. When on, the FIRST completeTask gets one fresh-context
            // acceptance review over the worker's git diff. Validated on Terminal-Bench
            // (4/13→8/13).
            .criticPass(featureFlagService.isEnabled("worker.critic-pass.enabled"))
            // VBC verify-before-complete (RL-2026-06-…, V235, default OFF) — server-RUN the
            // project's tests at completeTask and refuse if red. Decoupled from V217 (calls the
            // runner directly), no model-visible schema change. Byte-identical until the flag
            // flips.
            .verifyBeforeComplete(
                featureFlagService.isEnabled("worker.verify-before-complete.enabled"))
            .build();

    WorkerResult result = workerAgent.run(req);

    log.info(
        "PatchAgent END run={} outcome={} iterations={} toolCalls={} latencyMs={}",
        ctx.runId(),
        result.outcome(),
        result.iterations(),
        result.toolCallsDispatched(),
        result.totalLatencyMs());

    return result;
  }

  /**
   * Patch-mode system prompt loaded from classpath at boot.
   *
   * <p>Audit 2026-05-12 review item M #69 — extracted from a 130-line inline string literal to
   * {@code prompts/patch-agent.md} per the CLAUDE.md MUST-pattern (prompts in external resource
   * files).
   */
  @Value("classpath:prompts/patch-agent.md")
  private Resource patchAgentPromptResource;

  private String patchAgentPrompt;

  @PostConstruct
  void loadPatchAgentPrompt() {
    try {
      patchAgentPrompt = patchAgentPromptResource.getContentAsString(StandardCharsets.UTF_8);
    } catch (Exception e) {
      log.error("[PatchAgent] Failed to load classpath:prompts/patch-agent.md — using a stub", e);
      patchAgentPrompt =
          "You are a patch-mode coding agent. Make the minimum change that fixes the issue.";
    }
  }

  /**
   * Returns the loaded patch-mode system prompt. See {@code prompts/patch-agent.md} for the full
   * template.
   */
  private String buildSystemPrompt() {
    return patchAgentPrompt;
  }
}

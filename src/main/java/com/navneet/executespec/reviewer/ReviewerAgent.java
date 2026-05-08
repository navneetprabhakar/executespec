package com.navneet.executespec.reviewer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.agent.WorkerAgent;
import com.navneet.executespec.agent.WorkerResult;
import com.navneet.executespec.agent.WorkerTaskRequest;
import com.navneet.executespec.observability.Metrics;
import com.navneet.executespec.tool.BuildProjectTool;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.tool.ToolResult;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * V2 post-execution code reviewer.
 *
 * <p>Runs after {@link com.navneet.executespec.harness.SequentialExecutor} completes. Executes two
 * review passes as narrowly-scoped worker invocations:
 *
 * <ol>
 *   <li><b>General review</b> — scan for logic bugs, incomplete implementations, contract
 *       violations, obvious runtime failures. Fix in place. Out of scope: style, formatting,
 *       refactoring preferences.
 *   <li><b>Wiring review</b> — project-type-aware integration checks (Rust mod.rs, TS barrels,
 *       Spring component scan, Express route registration, etc.). Fix any orphans/missing
 *       registrations. This is the specialized sub-phase mentioned in the V2 plan P7.1.
 * </ol>
 *
 * <p>After both passes, runs the build command once more and reports the final verdict: CLEAN
 * (nothing fixed) / FIXED_ISSUES (fixes applied + build passes) / UNRESOLVED_ISSUES (build still
 * fails) / REVIEW_FAILED (a pass didn't reach COMPLETED).
 *
 * <p>Implementation strategy: the reviewer is a thin composition over {@link WorkerAgent} — it
 * sends review-focused prompts through the same agent loop rather than reinventing it. This gives
 * reviewer the same native tool calling, context handling, and completeTask signaling for free.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReviewerAgent {

  /**
   * Default iteration cap per review pass. Was 30 — bumped to 50 on 2026-04-19 after reviewer
   * REVIEW_FAILED on run 0a0201c7 (RN Calorie Tracker) where the wiring pass ran out of budget
   * scanning a 7-file scaffold plus test suite. Reviewer does read-heavy work (readFile +
   * searchFiles), so iter cap grows roughly with codebase size, not LLM call cost. (Historical
   * note: original cap was 40 matching worker, trimmed to 30 because review is cheaper. Bumping to
   * 50 — still below worker's 50/75.) Smaller than worker's HIGH=75 because review passes are
   * fundamentally inspection + targeted edits, not generation.
   */
  public static final int DEFAULT_MAX_ITER_PER_PASS = 50;

  /**
   * Wall-clock cap on the general pass — long enough for thorough review, short enough that a stuck
   * LLM does not freeze the whole run. See v2.5.10 eval Bug C: a Gemini reviewer hung indefinitely
   * with all worker tasks complete, blocking terminate + score persistence.
   */
  public static final long GENERAL_PASS_TIMEOUT_SECONDS = 600; // was 90 — bumped 2026-04-19

  /** Wall-clock cap on the wiring pass — narrower scope than general. */
  public static final long WIRING_PASS_TIMEOUT_SECONDS = 600; // was 60 — bumped 2026-04-19

  // Real mobile reviews take 2–4 minutes on sonnet because each pass reads many
  // files (App.tsx + screens + reducer + tests). 60s was a holdover from when
  // the wiring pass only did grep-and-nudge; with meaningful reads we need 10m
  // to finish. Run 988364af: wiring pass was on call#90 at 60s mark and got
  // chopped mid-stream, returning ERROR → REVIEW_FAILED → skipping the final
  // build entirely → FINAL_BUILD_FAILED reason cascading to PARTIAL.

  private final WorkerAgent worker;
  private final BuildProjectTool buildProjectTool;
  private final ObjectMapper objectMapper;
  private final Metrics metrics;
  // v2.5.12 — resolve PLANNING-tier model from the matrix (same tier as
  // planner). Was req.model() (run-level worker default), so YAML matrix
  // changes for the reviewer had no effect.
  private final com.navneet.executespec.router.ModelResolver modelResolver;

  /**
   * Per-call executor for the timeout wrapper. Cached pool so successive reviews reuse threads;
   * daemon threads so JVM shutdown is not blocked.
   */
  private ExecutorService passExecutor;

  @PostConstruct
  void initExecutor() {
    passExecutor =
        Executors.newCachedThreadPool(
            r -> {
              Thread t = new Thread(r, "reviewer-pass");
              t.setDaemon(true);
              return t;
            });
  }

  @PreDestroy
  void shutdownExecutor() {
    if (passExecutor != null) {
      passExecutor.shutdownNow();
    }
  }

  public ReviewerResult review(ReviewerRequest req) {
    ReviewerResult result = reviewImpl(req);
    metrics.recordReviewerVerdict(result.overall().name());
    return result;
  }

  private ReviewerResult reviewImpl(ReviewerRequest req) {
    long start = System.currentTimeMillis();
    int maxIter =
        (req.maxIterationsPerPass() == null || req.maxIterationsPerPass() <= 0)
            ? DEFAULT_MAX_ITER_PER_PASS
            : req.maxIterationsPerPass();

    log.info(
        "ReviewerAgent START run={} task={} provider={} model={} skipWiring={}",
        req.context().runId(),
        req.context().taskId(),
        req.provider(),
        req.model(),
        req.skipWiringPass());

    // ── General pass ─────────────────────────────────────────────────────
    // A pass that hits the iteration cap has typically done meaningful fixes
    // before running out of turns — the workspace is still in a reasonable
    // state. Only a hard ERROR outcome (LLM failure, tool dispatch crash)
    // warrants REVIEW_FAILED. ITERATION_LIMIT_EXCEEDED and STOPPED_WITHOUT_COMPLETE
    // fall through to the final build, whose exit code dictates the verdict.
    WorkerResult general =
        runPassWithTimeout(
            req,
            maxIter,
            "reviewer-general",
            loadPrompt("prompts/reviewer-general.md"),
            GENERAL_PASS_TIMEOUT_SECONDS);
    if (general.outcome() == WorkerResult.Outcome.ERROR) {
      log.warn("ReviewerAgent general pass errored: {}", general.outcome());
      return ReviewerResult.builder()
          .overall(ReviewerResult.Overall.REVIEW_FAILED)
          .generalPassOutcome(general)
          .totalLatencyMs(System.currentTimeMillis() - start)
          .build();
    }
    if (general.outcome() != WorkerResult.Outcome.COMPLETED) {
      log.warn(
          "ReviewerAgent general pass did not call completeTask ({}), "
              + "continuing to wiring pass with partial work",
          general.outcome());
    }

    // ── Wiring pass ──────────────────────────────────────────────────────
    WorkerResult wiring = null;
    if (!req.skipWiringPass()) {
      wiring =
          runPassWithTimeout(
              req,
              maxIter,
              "reviewer-wiring",
              loadPrompt("prompts/reviewer-wiring.md"),
              WIRING_PASS_TIMEOUT_SECONDS);
      if (wiring.outcome() == WorkerResult.Outcome.ERROR) {
        log.warn("ReviewerAgent wiring pass errored: {}", wiring.outcome());
        return ReviewerResult.builder()
            .overall(ReviewerResult.Overall.REVIEW_FAILED)
            .generalPassOutcome(general)
            .generalPassSummary(general.completionSummary())
            .wiringPassOutcome(wiring)
            .totalLatencyMs(System.currentTimeMillis() - start)
            .build();
      }
      if (wiring.outcome() != WorkerResult.Outcome.COMPLETED) {
        log.warn(
            "ReviewerAgent wiring pass did not call completeTask ({}), "
                + "falling through to final build",
            wiring.outcome());
      }
    }

    // ── Final build ──────────────────────────────────────────────────────
    ObjectNode buildArgs = objectMapper.createObjectNode();
    if (req.buildCommand() != null && !req.buildCommand().isBlank()) {
      buildArgs.put("plannerCommand", req.buildCommand());
    }
    ToolResult buildResult = buildProjectTool.execute(buildArgs, req.context());
    boolean buildPassed = false;
    int buildExit = -1;
    String buildOutput = null;
    if (buildResult instanceof ToolResult.Ok ok) {
      buildOutput = ok.content();
      buildExit = extractExitCode(buildOutput);
      buildPassed = buildExit == 0;
    } else if (buildResult instanceof ToolResult.Err err) {
      buildOutput = "tool error: " + err.message();
    }

    ReviewerResult.Overall overall = determineOverall(general, wiring, buildPassed);
    long elapsed = System.currentTimeMillis() - start;
    log.info(
        "ReviewerAgent DONE run={} overall={} buildPassed={} elapsed={}ms",
        req.context().runId(),
        overall,
        buildPassed,
        elapsed);

    return ReviewerResult.builder()
        .overall(overall)
        .generalPassOutcome(general)
        .generalPassSummary(general.completionSummary())
        .wiringPassOutcome(wiring)
        .wiringPassSummary(wiring == null ? null : wiring.completionSummary())
        .finalBuildPassed(buildPassed)
        .finalBuildExitCode(buildExit)
        .finalBuildOutput(buildOutput)
        .totalLatencyMs(elapsed)
        .build();
  }

  // ─── helpers ─────────────────────────────────────────────────────────────

  private WorkerResult runPass(
      ReviewerRequest req, int maxIter, String taskIdSuffix, String systemPrompt) {
    ExecutionContext passCtx =
        new ExecutionContext(
            req.context().runId(),
            req.context().taskId() + "-" + taskIdSuffix,
            req.context().workspaceRoot());
    // v2.5.12 — resolve from the model matrix.
    com.navneet.executespec.router.LlmProvider reviewerProvider;
    try {
      reviewerProvider =
          com.navneet.executespec.router.LlmProvider.valueOf(
              req.provider() == null ? "ANTHROPIC" : req.provider().toUpperCase());
    } catch (IllegalArgumentException e) {
      reviewerProvider = com.navneet.executespec.router.LlmProvider.ANTHROPIC;
    }
    com.navneet.executespec.router.ResolvedModel resolved =
        modelResolver.resolve("ReviewerAgent", reviewerProvider);
    String reviewerModel = resolved.getModel();
    String reviewerProviderName = resolved.getActualProvider().name().toLowerCase();
    return worker.run(
        WorkerTaskRequest.builder()
            .provider(reviewerProviderName)
            .model(reviewerModel)
            .systemPrompt(systemPrompt)
            .userPrompt(
                "Begin your review now. Use the tools to inspect, fix, build, "
                    + "and then call completeTask.")
            .context(passCtx)
            .maxIterations(maxIter)
            .temperature(0.0)
            .maxTokensPerCall(8192)
            .build());
  }

  /**
   * Runs a review pass on the worker pool with a wall-clock timeout. On timeout the underlying
   * future is cancelled (interrupting the worker thread); we return a synthetic {@link
   * WorkerResult} with outcome {@code ERROR} so {@link #determineOverall} treats it as
   * REVIEW_FAILED and {@link
   * com.navneet.executespec.integration.RunOrchestrator#resolveTerminalStatus} falls through to the
   * build + execution signals.
   */
  private WorkerResult runPassWithTimeout(
      ReviewerRequest req,
      int maxIter,
      String taskIdSuffix,
      String systemPrompt,
      long timeoutSeconds) {
    Future<WorkerResult> future =
        passExecutor.submit(() -> runPass(req, maxIter, taskIdSuffix, systemPrompt));
    try {
      return future.get(timeoutSeconds, TimeUnit.SECONDS);
    } catch (TimeoutException te) {
      future.cancel(true);
      log.warn(
          "ReviewerAgent pass {} TIMED OUT after {}s — treating as ERROR; "
              + "final build verdict will determine run status",
          taskIdSuffix,
          timeoutSeconds);
      return WorkerResult.builder()
          .outcome(WorkerResult.Outcome.ERROR)
          .completionSummary("review pass timeout after " + timeoutSeconds + "s")
          .iterations(maxIter)
          .build();
    } catch (InterruptedException ie) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      return WorkerResult.builder()
          .outcome(WorkerResult.Outcome.ERROR)
          .completionSummary("review pass interrupted")
          .iterations(0)
          .build();
    } catch (Exception e) {
      log.error("ReviewerAgent pass {} unexpected failure", taskIdSuffix, e);
      return WorkerResult.builder()
          .outcome(WorkerResult.Outcome.ERROR)
          .completionSummary("review pass crashed: " + e.getClass().getSimpleName())
          .iterations(0)
          .build();
    }
  }

  /** Verdict the reviewer prompt asks the agent to emit on the first line of `completeTask`. */
  private enum PassVerdict {
    CLEAN,
    FIXED_ISSUES,
    UNRESOLVED,
    /** No verdict prefix found — fall back to free-text heuristic for backward compat. */
    UNPARSED
  }

  /**
   * Parse the {@code VERDICT: <CLEAN|FIXED_ISSUES|UNRESOLVED>} prefix on the first line of the
   * worker's completion summary. Both reviewer prompts (general + wiring) require this prefix as of
   * 2026-05-08. Pre-prompt-update runs return UNPARSED, in which case {@link #determineOverall}
   * falls back to the legacy substring heuristic.
   */
  private static PassVerdict parsePassVerdict(WorkerResult result) {
    if (result == null) return PassVerdict.UNPARSED;
    String summary = result.completionSummary();
    if (summary == null || summary.isBlank()) return PassVerdict.UNPARSED;
    // Take only the first line so subsequent prose can mention any verdict word freely.
    int nl = summary.indexOf('\n');
    String firstLine = (nl < 0 ? summary : summary.substring(0, nl)).trim();
    String upper = firstLine.toUpperCase();
    if (!upper.startsWith("VERDICT:")) return PassVerdict.UNPARSED;
    String tail = upper.substring("VERDICT:".length()).trim();
    if (tail.startsWith("CLEAN")) return PassVerdict.CLEAN;
    if (tail.startsWith("FIXED_ISSUES") || tail.startsWith("FIXED")) {
      return PassVerdict.FIXED_ISSUES;
    }
    if (tail.startsWith("UNRESOLVED")) return PassVerdict.UNRESOLVED;
    return PassVerdict.UNPARSED;
  }

  private ReviewerResult.Overall determineOverall(
      WorkerResult general, WorkerResult wiring, boolean buildPassed) {
    PassVerdict gV = parsePassVerdict(general);
    PassVerdict wV = wiring == null ? PassVerdict.CLEAN : parsePassVerdict(wiring);

    // Structured-verdict path: both reviewer passes emitted a parseable VERDICT: prefix.
    if (gV != PassVerdict.UNPARSED && wV != PassVerdict.UNPARSED) {
      // UNRESOLVED on either pass dominates. Build failure also dominates regardless of
      // what the agent claimed (the build is the ground truth).
      if (gV == PassVerdict.UNRESOLVED || wV == PassVerdict.UNRESOLVED || !buildPassed) {
        return ReviewerResult.Overall.UNRESOLVED_ISSUES;
      }
      // Both CLEAN + build OK → CLEAN.
      if (gV == PassVerdict.CLEAN && wV == PassVerdict.CLEAN) {
        return ReviewerResult.Overall.CLEAN;
      }
      // At least one FIXED_ISSUES (the other may be CLEAN), build OK → FIXED_ISSUES.
      return ReviewerResult.Overall.FIXED_ISSUES;
    }

    // Backward-compat: pre-prompt-update runs land here. Fall back to the legacy
    // free-text substring match. Removing this branch is safe once every deployed
    // reviewer prompt emits the VERDICT: prefix; tracked under doc-refresh.
    String gSum =
        general.completionSummary() == null ? "" : general.completionSummary().toLowerCase();
    String wSum =
        wiring == null || wiring.completionSummary() == null
            ? ""
            : wiring.completionSummary().toLowerCase();
    boolean generalClean =
        gSum.contains("no issues") || gSum.contains("no in-scope") || gSum.contains("found 0");
    boolean wiringClean =
        wiring == null
            || wSum.contains("no wiring issues")
            || wSum.contains("no issues")
            || wSum.contains("found 0");
    if (generalClean && wiringClean && buildPassed) return ReviewerResult.Overall.CLEAN;
    if (buildPassed) return ReviewerResult.Overall.FIXED_ISSUES;
    return ReviewerResult.Overall.UNRESOLVED_ISSUES;
  }

  private String loadPrompt(String path) {
    try {
      return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to load " + path, e);
    }
  }

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
}

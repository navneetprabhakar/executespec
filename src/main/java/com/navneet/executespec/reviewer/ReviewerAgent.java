package com.navneet.executespec.reviewer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.agent.PromptTrustBoundary;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.engine.WorkerAgent;
import com.navneet.executespec.engine.WorkerResult;
import com.navneet.executespec.engine.WorkerTaskRequest;
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
   * Default iteration cap per review pass. Raised to 70 on 2026-06-06 alongside the worker cap
   * increase: reviewers do read-heavy wiring inspection and non-frontier models need enough turns
   * to inspect, patch, and re-check without hitting an artificial ceiling. Still below the main
   * worker HIGH cap because review passes are inspection + targeted edits, not generation.
   */
  public static final int DEFAULT_MAX_ITER_PER_PASS = 70;

  /**
   * 2026-05-19 — Phase 2 auto-fix. Iteration cap for the third "fix-the-red-build" pass. Smaller
   * than the general/wiring caps because this pass is targeted: the user message names the exact
   * build error, the LLM only needs enough budget to read the affected file(s) and patch the
   * specific failure — not to scan the whole repo. 25 iterations ≈ 5–10 minutes on PLANNING tier,
   * which is the budget a junior engineer would need to fix a "missing import" or "wrong type"
   * compile error. Raised to 35 on 2026-06-06 so the pass can handle small multi-file compile
   * breakages without becoming a second full review pass.
   */
  public static final int FIX_BUILD_PASS_MAX_ITER = 35;

  /** Wall-clock cap on the fix-build pass — narrower scope than general/wiring. */
  public static final long FIX_BUILD_PASS_TIMEOUT_SECONDS = 360;

  /**
   * Flag key for the Phase 2 fix-the-build third pass. Default ON — the pass only fires when the
   * final build is already red, so the worst case is "spend a few extra LLM seconds without
   * improving the verdict." Flip off via {@code FeatureFlagService} to compare verdict
   * distributions A/B per provider.
   */
  public static final String FLAG_THIRD_PASS = "reviewer.third-pass.enabled";

  /**
   * Wall-clock cap on the general pass — long enough for thorough review, short enough that a stuck
   * LLM does not freeze the whole run. See v2.5.10 eval Bug C: a Gemini reviewer hung indefinitely
   * with all worker tasks complete, blocking terminate + score persistence.
   */
  public static final long GENERAL_PASS_TIMEOUT_SECONDS = 600; // was 90 — bumped 2026-04-19

  /** Wall-clock cap on the wiring pass — narrower scope than general. */
  public static final long WIRING_PASS_TIMEOUT_SECONDS = 600; // was 60 — bumped 2026-04-19

  /**
   * RR-272 — how long a cancelled review pass gets to actually stop before the review is abandoned.
   * {@code Future.cancel(true)} only sets the interrupt flag; {@link WorkerAgent} checks it at an
   * iteration boundary, so a provider call already in flight (native DeepSeek reads up to 300s) or
   * a running build/tool command has to unwind first. 330s covers one full in-flight provider read
   * plus unwind without letting the pipeline block indefinitely on a wedged thread.
   */
  public static final long TERMINATION_RESERVE_SECONDS = 330;

  /**
   * Poll interval while waiting for a cancelled pass to terminate; also the re-interrupt period.
   */
  static final long TERMINATION_POLL_MILLIS = 500;

  /**
   * Effective abandonment reserve. Instance-scoped only so a test can shorten it; production always
   * uses {@link #TERMINATION_RESERVE_SECONDS}.
   */
  private volatile long terminationReserveSeconds = TERMINATION_RESERVE_SECONDS;

  void setTerminationReserveSecondsForTest(long seconds) {
    this.terminationReserveSeconds = seconds;
  }

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
  private final FeatureFlagService featureFlagService;
  // 2026-05-13 — post-edit formatter pass. Audit run 6024f510 RC-1: reviewer's
  // surgical async→def edits introduced black-incompatible formatting drift
  // that broke the customer's `black --check app/` CI step. Runs after both
  // review passes finish, before the final build smoke, so the formatter's
  // re-write is included in the buildProject verification.
  private final PostEditFormatter postEditFormatter;

  /**
   * Harness-resolution task 8 (flag {@code worker.auto-verify.enabled}) — lets the reviewer run the
   * project's own tests after a GREEN final build and spend one bounded fix pass on red tests,
   * mirroring the fix-build third pass. Nullable (tests / legacy wiring) ⇒ inert.
   */
  private final com.navneet.executespec.smoketest.WorkspaceTestRunner workspaceTestRunner;

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
    // 2026-05-13 — sanity check on the reviewer's prompt files at boot. Previously a missing
    // prompt only surfaced on the FIRST run that exercised it (audit run e5f40b8a hit this on
    // dev; ran for ~2min before REVIEW_FAILED). Fail-fast at startup so a stale deploy is
    // caught by ops the moment the app comes up, not on the first customer-facing run.
    String[] reviewerPrompts = {"prompts/reviewer-general.md", "prompts/reviewer-wiring.md"};
    for (String prompt : reviewerPrompts) {
      org.springframework.core.io.ClassPathResource r = new ClassPathResource(prompt);
      if (!r.exists()) {
        // Don't crash the whole app — log loudly. Reviewer is a degradeable feature; runs
        // can still complete with overall=REVIEW_FAILED. But operators MUST see this.
        log.error(
            "[ReviewerAgent] BOOT CHECK FAILED: classpath resource '{}' missing. Reviewer will"
                + " REVIEW_FAILED on every run until this is fixed. JAR may be stale or built"
                + " with a resource exclusion. Verify with: jar tf <jar> | grep prompts/",
            prompt);
      } else {
        log.info("[ReviewerAgent] boot check ok — '{}' found on classpath", prompt);
      }
    }
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

    // Pre-review build smoke (2026-05-08)
    // Capture the build state going INTO the reviewer. When the per-task pipeline ended with
    // partial / build-failing tasks, the reviewer's job changes: it's no longer "scan for subtle
    // bugs" — it's "make this thing compile, here's the exact error." Without this signal the
    // reviewer fishes generically through the codebase, often emits an empty completionSummary,
    // and the run flips to PARTIAL/FINAL_BUILD_FAILED.
    //
    // Empirical motivation: 30-day failure rollup (2026-05-08) shows FINAL_BUILD_FAILED is
    // 16/22 of all PARTIAL runs. Drilled-into example (Spring Boot run 77a1bf15): both reviewer
    // passes returned empty generalPassSummary + wiringPassSummary while finalBuildExitCode=1.
    //
    // SWE-bench parallel: gold tests are the spec for patch-mode; the build error is the spec
    // for build-mode. Inject it verbatim into the user prompt and the LLM has a concrete target.
    String preReviewBuildError = capturePreReviewBuildError(req);

    log.info(
        "ReviewerAgent START run={} task={} provider={} model={} skipWiring={} preBuildFailing={}",
        req.context().runId(),
        req.context().taskId(),
        req.provider(),
        req.model(),
        req.skipWiringPass(),
        preReviewBuildError != null);

    // A reviewer pass is proof only when it reaches completeTask and emits the required verdict.
    // A green build cannot substitute for an unfinished semantic review: run 1e1762b9 hit the
    // iteration limit, produced a salvage summary with no verdict, and was incorrectly promoted to
    // CLEAN because the narrower wiring pass completed. Preserve the deterministic build evidence,
    // but fail the review protocol closed for every non-COMPLETED outcome.
    PassRun generalRun =
        runPassWithTimeout(
            req,
            maxIter,
            "reviewer-general",
            loadPrompt("prompts/reviewer-general.md"),
            GENERAL_PASS_TIMEOUT_SECONDS,
            preReviewBuildError);
    WorkerResult general = generalRun.result();
    if (!generalRun.workspaceQuiescent()) {
      return abandonedReview(general, null, "general", start);
    }
    if (general.outcome() != WorkerResult.Outcome.COMPLETED) {
      log.warn("ReviewerAgent general pass did not complete: {}", general.outcome());
      // RR-275 — do not discard cheap deterministic verification when the semantic pass is
      // unavailable. REVIEW_FAILED remains independent from the frozen workspace build result.
      FinalBuildEvidence fb = runFinalBuild(req);
      return ReviewerResult.builder()
          .overall(ReviewerResult.Overall.REVIEW_FAILED)
          .generalPassOutcome(general)
          .generalPassSummary(general.completionSummary())
          .finalBuildPassed(fb.passed())
          .finalBuildExitCode(fb.exitCode())
          .finalBuildOutput(fb.output())
          .totalLatencyMs(System.currentTimeMillis() - start)
          .build();
    }
    WorkerResult wiring = null;
    if (!req.skipWiringPass()) {
      // Re-capture build state before the wiring pass — the general pass may have already fixed
      // the build, in which case wiring shouldn't be told "build was failing." Conversely if the
      // general pass made things worse, wiring needs to know.
      String preWiringBuildError =
          (preReviewBuildError == null)
              ? null // build was clean coming in; assume general pass didn't break it
              : capturePreReviewBuildError(req);
      PassRun wiringRun =
          runPassWithTimeout(
              req,
              maxIter,
              "reviewer-wiring",
              loadPrompt("prompts/reviewer-wiring.md"),
              WIRING_PASS_TIMEOUT_SECONDS,
              preWiringBuildError);
      wiring = wiringRun.result();
      if (!wiringRun.workspaceQuiescent()) {
        return abandonedReview(general, wiring, "wiring", start);
      }
      if (wiring.outcome() != WorkerResult.Outcome.COMPLETED) {
        log.warn("ReviewerAgent wiring pass did not complete: {}", wiring.outcome());
        // RR-275 — same as the general-pass ERROR branch: keep REVIEW_FAILED but still run the
        // deterministic final build on the frozen workspace so the build verdict is not lost.
        FinalBuildEvidence fb = runFinalBuild(req);
        return ReviewerResult.builder()
            .overall(ReviewerResult.Overall.REVIEW_FAILED)
            .generalPassOutcome(general)
            .generalPassSummary(general.completionSummary())
            .wiringPassOutcome(wiring)
            .wiringPassSummary(wiring.completionSummary())
            .finalBuildPassed(fb.passed())
            .finalBuildExitCode(fb.exitCode())
            .finalBuildOutput(fb.output())
            .totalLatencyMs(System.currentTimeMillis() - start)
            .build();
      }
    }

    // Post-edit formatter pass (2026-05-13 audit Fix-1)
    // After the reviewer's general/wiring edits, run any language formatters
    // present on PATH (black, prettier, gofmt) so the workspace lands in the
    // shape the customer's CI's `--check` step expects. Best-effort — no-op
    // when no matching files exist or the formatter isn't on PATH. Runs
    // BEFORE the final build so its (possibly reformatted) output is what
    // buildProject verifies. Tracking telemetry on PostEditFormatter.Result.
    try {
      PostEditFormatter.Result formatResult =
          postEditFormatter.run(req.context().workspaceRoot(), req.context().runId());
      if (formatResult.filesReformatted() > 0) {
        log.info(
            "ReviewerAgent run={} post-edit formatter reformatted ~{} file(s) via {}",
            req.context().runId(),
            formatResult.filesReformatted(),
            formatResult.formattersRun());
      }
      if (!formatResult.errors().isEmpty()) {
        log.warn(
            "ReviewerAgent run={} post-edit formatter had {} non-fatal error(s): {}",
            req.context().runId(),
            formatResult.errors().size(),
            formatResult.errors());
      }
    } catch (Exception e) {
      // Never abort the run on a formatter error — final build still runs.
      log.warn(
          "ReviewerAgent run={} post-edit formatter pass threw (continuing): {}",
          req.context().runId(),
          e.getMessage());
    }

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
      buildPassed = isBuildVerifiedEvidence(buildOutput);
    } else if (buildResult instanceof ToolResult.Err err) {
      buildOutput = "tool error: " + err.message();
    }

    // Phase 2 third pass — fix the red build (2026-05-19)
    // When the final build is still red after general + wiring, give the
    // reviewer one more targeted pass with the exact build error as input.
    // This is the class of failure where the agent was one typo away from
    // CLEAN — a stray import, a wrong type, a missing decorator — and the
    // earlier passes either didn't trip the build or were timing-out on the
    // last edit. Empirically (audit 2026-05-19, admin's 30 run sample):
    // 4 of the 30 "complete" runs in the admin list had a passing reviewer
    // BUT a red final build, landing in UNRESOLVED_ISSUES / partial. Phase
    // 2 rescues that class, pushing them toward FIXED_ISSUES.
    //
    // Gate: build red + we have meaningful build-output text + flag on.
    // We never run the pass on a clean build — there's nothing to fix.
    boolean thirdPassEnabled =
        featureFlagService == null || featureFlagService.isEnabled(FLAG_THIRD_PASS);
    WorkerResult fixBuild = null;
    if (!buildPassed && thirdPassEnabled && buildOutput != null && !buildOutput.isBlank()) {
      log.info(
          "ReviewerAgent run={} third pass START — build red after general+wiring, retrying",
          req.context().runId());
      PassRun fixBuildRun =
          runPassWithTimeout(
              req,
              FIX_BUILD_PASS_MAX_ITER,
              "reviewer-fixbuild",
              loadPrompt("prompts/reviewer-general.md"),
              FIX_BUILD_PASS_TIMEOUT_SECONDS,
              buildOutput);
      fixBuild = fixBuildRun.result();
      if (!fixBuildRun.workspaceQuiescent()) {
        // RR-272 — rebuilding here would report the exit code of a workspace the abandoned
        // fix-build thread is still editing.
        return abandonedReview(general, wiring, "fix-build", start);
      }
      // Re-run final build to capture the post-third-pass state.
      ObjectNode rebuildArgs = objectMapper.createObjectNode();
      if (req.buildCommand() != null && !req.buildCommand().isBlank()) {
        rebuildArgs.put("plannerCommand", req.buildCommand());
      }
      ToolResult rebuildResult = buildProjectTool.execute(rebuildArgs, req.context());
      if (rebuildResult instanceof ToolResult.Ok ok2) {
        buildOutput = ok2.content();
        buildExit = extractExitCode(buildOutput);
        buildPassed = isBuildVerifiedEvidence(buildOutput);
      } else if (rebuildResult instanceof ToolResult.Err err2) {
        buildOutput = "tool error: " + err2.message();
        buildPassed = false;
      }
      log.info(
          "ReviewerAgent run={} third pass DONE — buildPassedAfter={}",
          req.context().runId(),
          buildPassed);
    }

    // Harness-resolution task 8 — fix-tests pass (flag worker.auto-verify.enabled). The build is
    // the compile signal; the suite is the behavior signal, and until now NO reviewer stage ever
    // executed it. When the final build is green but the project's own tests are red, spend ONE
    // bounded pass with the failing output as the spec (exact same machinery as fix-build), then
    // re-check. Fail-open at every step; flag OFF ⇒ byte-identical.
    boolean autoVerifyEnabled =
        featureFlagService != null && featureFlagService.isEnabled("worker.auto-verify.enabled");
    WorkerResult fixTests = null;
    // RR-273 — build and test evidence must describe ONE immutable final workspace generation.
    // Previously the post-repair rerun existed only as a log line: its counts were discarded, the
    // verdict was computed from the PRE-repair build bit, and no build was rerun even though the
    // fix-tests worker had just mutated source. CLEAN could therefore be returned with the suite
    // still red, or with code that no longer compiled.
    Integer finalTestsPassed = null;
    Integer finalTestsTotal = null;
    boolean concreteTestsRed = false;
    if (buildPassed && autoVerifyEnabled && workspaceTestRunner != null) {
      try {
        var initial =
            workspaceTestRunner.runTestsDetailed(
                req.context().runId(), req.context().workspaceRoot());
        if (initial.isPresent() && initial.get().summary().getTotalTests() > 0) {
          finalTestsPassed = initial.get().summary().getPassed();
          finalTestsTotal = initial.get().summary().getTotalTests();
        }
        var red =
            initial
                .filter(d -> d.summary().getTotalTests() > 0)
                .filter(d -> d.summary().getFailed() > 0 || d.summary().getErrors() > 0);
        concreteTestsRed = red.isPresent();
        if (red.isPresent()) {
          var d = red.get();
          log.info(
              "ReviewerAgent run={} fix-tests pass START — {} failed / {} errored of {}",
              req.context().runId(),
              d.summary().getFailed(),
              d.summary().getErrors(),
              d.summary().getTotalTests());
          String testSpec =
              "THE PROJECT'S OWN TESTS ARE FAILING ("
                  + d.summary().getFailed()
                  + " failed / "
                  + d.summary().getErrors()
                  + " errored of "
                  + d.summary().getTotalTests()
                  + "; command: "
                  + d.command()
                  + ").\n\nTest output (tail):\n"
                  + d.outputTail();
          PassRun fixTestsRun =
              runPassWithTimeout(
                  req,
                  FIX_BUILD_PASS_MAX_ITER,
                  "reviewer-fixtests",
                  loadPrompt("prompts/reviewer-general.md"),
                  FIX_BUILD_PASS_TIMEOUT_SECONDS,
                  testSpec);
          fixTests = fixTestsRun.result();
          if (!fixTestsRun.workspaceQuiescent()) {
            // RR-272 — the post-repair build + suite rerun below would measure a workspace the
            // abandoned fix-tests thread is still writing.
            return abandonedReview(general, wiring, "fix-tests", start);
          }
          // The fix-tests worker just mutated source. Re-establish BOTH gates against the workspace
          // as it now stands: a repair that fixes a test but breaks compilation is not a repair.
          ObjectNode postFixArgs = objectMapper.createObjectNode();
          if (req.buildCommand() != null && !req.buildCommand().isBlank()) {
            postFixArgs.put("plannerCommand", req.buildCommand());
          }
          ToolResult postFixBuild = buildProjectTool.execute(postFixArgs, req.context());
          if (postFixBuild instanceof ToolResult.Ok ok3) {
            buildOutput = ok3.content();
            buildExit = extractExitCode(buildOutput);
            buildPassed = isBuildVerifiedEvidence(buildOutput);
          } else if (postFixBuild instanceof ToolResult.Err err3) {
            buildOutput = "tool error: " + err3.message();
            buildPassed = false;
          }

          var after =
              workspaceTestRunner.runTestsDetailed(
                  req.context().runId(), req.context().workspaceRoot());
          if (after.isPresent() && after.get().summary().getTotalTests() > 0) {
            finalTestsPassed = after.get().summary().getPassed();
            finalTestsTotal = after.get().summary().getTotalTests();
            concreteTestsRed =
                after.get().summary().getFailed() > 0 || after.get().summary().getErrors() > 0;
          } else {
            // The suite was provably red before the repair and cannot now be proven green. An
            // unavailable rerun is not evidence of success — the red finding stands.
            concreteTestsRed = true;
          }
          log.info(
              "ReviewerAgent run={} fix-tests pass DONE — after: {}; buildPassedAfter={}",
              req.context().runId(),
              after
                  .map(
                      a ->
                          a.summary().getFailed()
                              + " failed / "
                              + a.summary().getErrors()
                              + " errored of "
                              + a.summary().getTotalTests())
                  .orElse("unresolvable"),
              buildPassed);
        }
      } catch (Exception e) {
        log.warn(
            "ReviewerAgent run={} fix-tests evidence failed: {}",
            req.context().runId(),
            e.getMessage());
        // A crashed verification pass never upgrades the verdict; keep whatever was already proven.
      }
    }

    ReviewerResult.Overall overall = determineOverall(general, wiring, buildPassed);
    if (concreteTestsRed && overall != ReviewerResult.Overall.REVIEW_FAILED) {
      // Tighten-only: red concrete tests cannot coexist with a clean verdict, whatever the
      // reviewer LLM claimed about its own work.
      overall = ReviewerResult.Overall.UNRESOLVED_ISSUES;
    }
    long elapsed = System.currentTimeMillis() - start;
    log.info(
        "ReviewerAgent DONE run={} overall={} buildPassed={} thirdPassFired={} fixTestsFired={}"
            + " elapsed={}ms",
        req.context().runId(),
        overall,
        buildPassed,
        fixBuild != null,
        fixTests != null,
        elapsed);

    // Compose pass summary including third-pass note if it fired, so the UI
    // changelog ("Reviewer auto-fixed …") reflects the third pass's work.
    String generalSummary = general.completionSummary();
    String wiringSummary = wiring == null ? null : wiring.completionSummary();
    if (fixBuild != null && fixBuild.completionSummary() != null) {
      wiringSummary =
          (wiringSummary == null ? "" : wiringSummary + " | ")
              + "fix-build pass: "
              + fixBuild.completionSummary();
    }

    return ReviewerResult.builder()
        .overall(overall)
        .generalPassOutcome(general)
        .generalPassSummary(generalSummary)
        .wiringPassOutcome(wiring)
        .wiringPassSummary(wiringSummary)
        .finalBuildPassed(buildPassed)
        .finalBuildExitCode(buildExit)
        .finalBuildOutput(buildOutput)
        .finalTestsPassed(finalTestsPassed)
        .finalTestsTotal(finalTestsTotal)
        .concreteTestsRed(concreteTestsRed)
        .totalLatencyMs(elapsed)
        .build();
  }

  /**
   * RR-275 — deterministic final build on the frozen workspace, factored out so the reviewer ERROR
   * branches can preserve REVIEW_FAILED as an independent signal AND still attach the cheap non-LLM
   * build verdict (rather than leaving downstream with a null/unknown build state that mis-applies
   * the trust cap). Only called when the workspace is quiescent — an ERROR outcome surfaces at an
   * iteration boundary, so no pass thread is still mutating the tree.
   */
  private FinalBuildEvidence runFinalBuild(ReviewerRequest req) {
    ObjectNode buildArgs = objectMapper.createObjectNode();
    if (req.buildCommand() != null && !req.buildCommand().isBlank()) {
      buildArgs.put("plannerCommand", req.buildCommand());
    }
    ToolResult buildResult = buildProjectTool.execute(buildArgs, req.context());
    if (buildResult instanceof ToolResult.Ok ok) {
      String out = ok.content();
      return new FinalBuildEvidence(isBuildVerifiedEvidence(out), extractExitCode(out), out);
    }
    if (buildResult instanceof ToolResult.Err err) {
      return new FinalBuildEvidence(false, -1, "tool error: " + err.message());
    }
    return new FinalBuildEvidence(false, -1, null);
  }

  private record FinalBuildEvidence(boolean passed, int exitCode, String output) {}

  WorkerResult runPass(
      ReviewerRequest req,
      int maxIter,
      String taskIdSuffix,
      String systemPrompt,
      long wallClockSeconds,
      String preBuildError,
      com.navneet.executespec.tool.ReviewerMutationScope.Lease lease) {
    ExecutionContext passCtx = req.context().child(req.context().taskId() + "-" + taskIdSuffix);
    // v2.5.12 — resolve from the model matrix.
    com.navneet.executespec.router.LlmProvider reviewerProvider;
    try {
      reviewerProvider =
          com.navneet.executespec.router.LlmProvider.valueOf(
              req.provider() == null ? "ANTHROPIC" : req.provider().toUpperCase());
    } catch (IllegalArgumentException e) {
      reviewerProvider = com.navneet.executespec.router.LlmProvider.ANTHROPIC;
    }
    // ReviewerRequest is already resolved by RunOrchestrator. Re-resolving here used to discard a
    // continuation-selected model and made the "reviewer route promoted" audit event false. It
    // also made the explicit model pin work only accidentally through a thread-local override.
    // Preserve the exact validated pair handed across the orchestration boundary.
    String reviewerModel = req.model();
    String reviewerProviderName = reviewerProvider.name().toLowerCase();
    String userPrompt = composeReviewerUserPrompt(req, preBuildError);
    WorkerTaskRequest workerRequest =
        WorkerTaskRequest.builder()
            .provider(reviewerProviderName)
            .model(reviewerModel)
            .systemPrompt(systemPrompt)
            .userPrompt(userPrompt)
            .context(passCtx)
            .maxIterations(maxIter)
            // A4 (audit 2026-05-29) — give the worker the same wall-clock budget the pass executor
            // enforces, so a runaway reviewer pass self-terminates at the cap even independent of
            // the future.cancel(true) interrupt. Belt-and-braces with the worker's interrupt check.
            .wallClockSeconds((int) wallClockSeconds)
            .temperature(0.0)
            // Review turns should produce one bounded tool decision, not another long-form code
            // generation. This also caps non-DeepSeek providers; native DeepSeek additionally
            // disables hidden thinking for reviewer task ids in LlmRouter.
            .maxTokensPerCall(4096)
            // 2026-05-08 — when build is failing pre-review, force completeTask gate to require
            // a successful buildProject call before terminating. Mirrors the patch-mode test gate
            // shape (V182 layer-3). Without this, reviewer can call completeTask after a few edits
            // without verifying — leaves run in PARTIAL/FINAL_BUILD_FAILED.
            .requireBuildVerification(preBuildError != null)
            .build();
    var mutationPolicy =
        new com.navneet.executespec.tool.ReviewerMutationScope.Policy(
            Boolean.TRUE.equals(req.brownfield()),
            req.allowedRepoRoots(),
            req.approvedNewFiles(),
            Boolean.TRUE.equals(req.allowBuildManifestCreation()));
    return com.navneet.executespec.tool.ReviewerMutationScope.withPolicy(
        mutationPolicy, lease, () -> worker.run(workerRequest));
  }

  /**
   * Builds the user-facing kickoff message for a reviewer pass. When {@code preBuildError} is
   * non-null, the build was failing entering the reviewer — prepend the verbatim error so the LLM
   * has a concrete target rather than a generic "look around for issues" prompt.
   */
  static String composeReviewerUserPrompt(String preBuildError) {
    return composeReviewerUserPrompt(null, preBuildError);
  }

  static String composeReviewerUserPrompt(ReviewerRequest req, String preBuildError) {
    String contractEvidence = reviewerContractEvidence(req);
    String prefix = contractEvidence.isBlank() ? "" : contractEvidence + "\n\n";
    if (preBuildError == null || preBuildError.isBlank()) {
      return prefix
          + "Begin your review now. Use the tools to inspect, fix, build, and then call"
          + " completeTask.";
    }
    return prefix
        + """
          BUILD IS CURRENTLY FAILING. Your job is to make it pass.

          The build error below is your spec. Read it carefully — the specific files,
          line numbers, and error messages name the exact problem. Match the fix to
          the error: don't refactor unrelated code, don't reorganise; identify the
          root cause, edit the affected files, run buildProject, and only call
          completeTask after the build returns exit=0.

          --- BUILD ERROR (verbatim, captured before this review pass) ---
          """
        + PromptTrustBoundary.wrap("BUILD_ERROR", truncateBuildError(preBuildError))
        + """

--- END BUILD ERROR ---

Begin now. Use readFile / editFile to fix the source files, then call
buildProject to verify. Do NOT call completeTask before buildProject reports
exit=0 — the harness will reject premature completions.
""";
  }

  private static String reviewerContractEvidence(ReviewerRequest req) {
    if (req == null) return "";
    StringBuilder evidence =
        new StringBuilder(
            "Review against the customer's exact contracts below, not merely whether the build"
                + " and generated tests are green. A requirement is not satisfied until every"
                + " semantic clause is present in production wiring and exercised without test-only"
                + " substitutions.\n");
    if (req.originalGoal() != null && !req.originalGoal().isBlank()) {
      evidence
          .append('\n')
          .append(PromptTrustBoundary.wrap("ORIGINAL_SPEC", req.originalGoal().strip()));
    }
    if (req.requirements() != null && !req.requirements().isEmpty()) {
      StringBuilder rows = new StringBuilder();
      for (com.navneet.executespec.agent.RequirementSpec requirement : req.requirements()) {
        if (requirement == null) continue;
        rows.append("- ")
            .append(requirement.id())
            .append(" [")
            .append(requirement.kind())
            .append("]: ")
            .append(requirement.text())
            .append('\n');
      }
      evidence
          .append("\n\n")
          .append(PromptTrustBoundary.wrap("PLAN_REQUIREMENTS", rows.toString().stripTrailing()));
    }
    return evidence.toString().stripTrailing();
  }

  /**
   * Cap the build error at 12K chars (head 8K + tail 4K) — long enough for compiler stack traces
   * with stem+leaf, short enough that the reviewer's prefix stays cache-friendly. Real build errors
   * over 12K usually have the actionable info in the first stack trace and the last few "BUILD
   * FAILED" lines; the middle is repetition.
   */
  private static String truncateBuildError(String s) {
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

  /**
   * Run the build smoke once before any review pass to capture the entering build state. Returns
   * null when the build passes (clean entry), the verbatim build output when it fails. Also returns
   * null on any tool error — we don't want a flaky build infrastructure to falsely trip the
   * build-failing path; the run-end final smoke will catch a real failure either way.
   */
  private String capturePreReviewBuildError(ReviewerRequest req) {
    // RR-1686 — do not run the pre-review build smoke over a workspace an abandoned reviewer pass
    // (from this or a concurrent review of the same workspace) may still be mutating. Returning
    // null is the existing "could not capture" fail-safe: the reviewer simply proceeds without a
    // build-error target rather than measuring a workspace generation another thread is writing.
    if (isWorkspaceNonQuiescent(req)) {
      log.warn(
          "capturePreReviewBuildError skipped run={} — workspace flagged non-quiescent by an"
              + " abandoned reviewer pass; not reading a tree another thread may still hold",
          req.context() == null ? null : req.context().runId());
      return null;
    }
    try {
      ObjectNode buildArgs = objectMapper.createObjectNode();
      if (req.buildCommand() != null && !req.buildCommand().isBlank()) {
        buildArgs.put("plannerCommand", req.buildCommand());
      }
      ToolResult result = buildProjectTool.execute(buildArgs, req.context());
      if (!(result instanceof ToolResult.Ok ok)) return null;
      String output = ok.content();
      return isBuildVerifiedEvidence(output) ? null : output;
    } catch (Exception e) {
      log.debug("capturePreReviewBuildError failed: {}", e.getMessage());
      return null;
    }
  }

  /**
   * A finished review pass plus the proof that its worker thread is no longer running.
   *
   * <p>RR-272 — {@code workspaceQuiescent=false} means a timed-out pass could not be proven
   * terminated within {@link #TERMINATION_RESERVE_SECONDS}. That thread still holds the shared
   * workspace and its (brownfield) write authority, so no later stage of the review may read the
   * workspace or publish evidence about it.
   */
  record PassRun(WorkerResult result, boolean workspaceQuiescent) {}

  /**
   * Runs a review pass on the worker pool with a wall-clock timeout. On timeout the underlying
   * future is cancelled (interrupting the worker thread) and we then WAIT, re-interrupting, for
   * confirmed termination of that thread before returning. {@link WorkerAgent} observes
   * interruption at an iteration boundary, so one in-flight provider response or tool batch may
   * still be running when {@code cancel} returns; returning immediately let the reviewer's own
   * later stages — final build, fix-build pass, fix-tests pass — and the orchestrator's validate /
   * push / cleanup run against a workspace that a live thread was still editing.
   *
   * <p>The returned {@link WorkerResult} keeps outcome {@code ERROR} so {@link #determineOverall}
   * treats it as REVIEW_FAILED and {@link
   * com.navneet.executespec.integration.RunOrchestrator#resolveTerminalStatus} falls through to the
   * build + execution signals.
   */
  PassRun runPassWithTimeout(
      ReviewerRequest req,
      int maxIter,
      String taskIdSuffix,
      String systemPrompt,
      long timeoutSeconds,
      String preBuildError) {
    AtomicReference<Thread> passThread = new AtomicReference<>();
    CountDownLatch terminated = new CountDownLatch(1);
    // RR-272 / RR-1687 — the revocation handle for THIS pass. Bound onto the worker thread via
    // runPass -> ReviewerMutationScope.withPolicy; retained here so that if we abandon the pass we
    // can strip its write authority even while its thread is still running.
    com.navneet.executespec.tool.ReviewerMutationScope.Lease lease =
        new com.navneet.executespec.tool.ReviewerMutationScope.Lease();
    Future<WorkerResult> future =
        passExecutor.submit(
            () -> {
              passThread.set(Thread.currentThread());
              try {
                return runPass(
                    req, maxIter, taskIdSuffix, systemPrompt, timeoutSeconds, preBuildError, lease);
              } finally {
                terminated.countDown();
              }
            });
    try {
      return new PassRun(future.get(timeoutSeconds, TimeUnit.SECONDS), true);
    } catch (TimeoutException te) {
      boolean quiescent =
          awaitPassTermination(future, passThread, terminated, taskIdSuffix, lease, req);
      String summary =
          quiescent
              ? "review pass timeout after " + timeoutSeconds + "s"
              : "review pass timeout after "
                  + timeoutSeconds
                  + "s; worker did not terminate within "
                  + terminationReserveSeconds
                  + "s — workspace evidence abandoned";
      return new PassRun(
          WorkerResult.builder()
              .outcome(WorkerResult.Outcome.ERROR)
              .completionSummary(summary)
              .iterations(maxIter)
              .build(),
          quiescent);
    } catch (InterruptedException ie) {
      boolean quiescent =
          awaitPassTermination(future, passThread, terminated, taskIdSuffix, lease, req);
      Thread.currentThread().interrupt();
      return new PassRun(
          WorkerResult.builder()
              .outcome(WorkerResult.Outcome.ERROR)
              .completionSummary("review pass interrupted")
              .iterations(0)
              .build(),
          quiescent);
    } catch (Exception e) {
      // ExecutionException — the callable itself threw, so its thread has already returned and the
      // workspace is quiescent by construction.
      log.error("ReviewerAgent pass {} unexpected failure", taskIdSuffix, e);
      return new PassRun(
          WorkerResult.builder()
              .outcome(WorkerResult.Outcome.ERROR)
              .completionSummary("review pass crashed: " + e.getClass().getSimpleName())
              .iterations(0)
              .build(),
          true);
    }
  }

  /**
   * Cancel a pass and prove it stopped. Interrupts repeatedly (the worker only observes the flag at
   * an iteration boundary, and an interruptible wait inside a tool can clear it) and waits on the
   * latch the pass counts down in its own {@code finally}. Returns true only when that latch trips,
   * which is the single fact that proves no further tool call can originate from that thread.
   */
  private boolean awaitPassTermination(
      Future<WorkerResult> future,
      AtomicReference<Thread> passThread,
      CountDownLatch terminated,
      String taskIdSuffix,
      com.navneet.executespec.tool.ReviewerMutationScope.Lease lease,
      ReviewerRequest req) {
    future.cancel(true);
    // RR-272 / RR-1687 — revoke write authority BEFORE we start waiting, not after. The worker
    // thread only observes interruption at an iteration boundary, so it may still issue a
    // writeFile/editFile/shell during the reserve window; revoking now makes every such call fail
    // closed, whether the pass is brownfield or greenfield. Revocation is permanent and idempotent.
    lease.revoke();
    long reserve = terminationReserveSeconds;
    long deadline = System.currentTimeMillis() + reserve * 1000L;
    boolean wasInterrupted = false;
    try {
      while (System.currentTimeMillis() < deadline) {
        Thread t = passThread.get();
        if (t != null) t.interrupt();
        try {
          if (terminated.await(TERMINATION_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
            log.warn(
                "ReviewerAgent pass {} TIMED OUT — worker terminated after cancellation;"
                    + " treating as ERROR",
                taskIdSuffix);
            return true;
          }
        } catch (InterruptedException ie) {
          // Our own thread was interrupted while waiting. Keep proving termination of the pass —
          // abandoning the wait here is exactly the race this method exists to close — and restore
          // the flag on the way out.
          wasInterrupted = true;
        }
      }
    } finally {
      if (wasInterrupted) Thread.currentThread().interrupt();
    }
    // RR-1686 — the pass thread outlived the reserve window and may still be mutating this
    // workspace. Record the workspace as non-quiescent for the JVM's lifetime so a CONCURRENT
    // review of the same workspace (a sibling of RR-272) does not run its pre-review build smoke
    // over a tree this abandoned thread still holds. There is no safe removal point: we cannot
    // prove the thread ever stopped, so the flag is deliberately permanent.
    markWorkspaceNonQuiescent(req);
    log.error(
        "ReviewerAgent pass {} TIMED OUT and did NOT terminate within {}s of cancellation."
            + " The workspace may still be mutating; abandoning all further review evidence for"
            + " this run rather than reporting a build/test result for a workspace generation"
            + " another thread is still writing.",
        taskIdSuffix,
        reserve);
    return false;
  }

  /**
   * Workspaces (by canonical root) whose reviewer pass could not be proven terminated. Consulted by
   * {@link #capturePreReviewBuildError} so a concurrent review of the same workspace does not read
   * a tree an abandoned thread from another review may still hold (RR-1686). JVM-scoped and
   * append-only: an unproven termination is never rescinded.
   */
  private static final java.util.Set<String> NON_QUIESCENT_WORKSPACES =
      java.util.concurrent.ConcurrentHashMap.newKeySet();

  private static String workspaceKey(ReviewerRequest req) {
    try {
      java.nio.file.Path root = req.context() == null ? null : req.context().workspaceRoot();
      if (root == null) return null;
      return root.toAbsolutePath().normalize().toString();
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static void markWorkspaceNonQuiescent(ReviewerRequest req) {
    String key = workspaceKey(req);
    if (key != null) NON_QUIESCENT_WORKSPACES.add(key);
  }

  /**
   * Package-private for tests: is this request's workspace known to be held by an abandoned pass?
   */
  static boolean isWorkspaceNonQuiescent(ReviewerRequest req) {
    String key = workspaceKey(req);
    return key != null && NON_QUIESCENT_WORKSPACES.contains(key);
  }

  /**
   * Terminal result for a review whose pass thread could not be proven stopped. No build, formatter
   * or test evidence is attached, because none of it can be attributed to a stable workspace.
   */
  private ReviewerResult abandonedReview(
      WorkerResult general, WorkerResult wiring, String stage, long start) {
    log.error(
        "ReviewerAgent ABANDONED review at stage={} — returning REVIEW_FAILED with no workspace"
            + " evidence",
        stage);
    return ReviewerResult.builder()
        .overall(ReviewerResult.Overall.REVIEW_FAILED)
        .generalPassOutcome(general)
        .generalPassSummary(general == null ? null : general.completionSummary())
        .wiringPassOutcome(wiring)
        .wiringPassSummary(wiring == null ? null : wiring.completionSummary())
        .workspaceQuiescent(false)
        .totalLatencyMs(System.currentTimeMillis() - start)
        .build();
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
    if (!completedReviewPass(general) || (wiring != null && !completedReviewPass(wiring))) {
      return ReviewerResult.Overall.REVIEW_FAILED;
    }
    PassVerdict gV = parsePassVerdict(general);
    PassVerdict wV = wiring == null ? PassVerdict.CLEAN : parsePassVerdict(wiring);
    ReviewerResult.Overall raw = determineOverallRaw(gV, wV, general, wiring, buildPassed);

    // 2026-05-10 — write-aware downgrade. If the verdict computation lands on
    // FIXED_ISSUES but neither pass actually performed a write-class tool call
    // (writeFile / editFile), the reviewer didn't fix anything — it just ran
    // through reads and decided to say "FIXED_ISSUES" anyway. Dev run 61c9fffa
    // hit this exact mismatch: both reviewer passes were force-terminated by
    // the stagnation watchdog with totalWrites=0, fell into the SALVAGE path,
    // and the legacy fallback at the bottom of the verdict tree returned
    // FIXED_ISSUES because build was passing. The downstream
    // RunOrchestrator.synthesiseReviewIssues then has no SYNTH-REV-* row, but
    // the verdict itself ("the reviewer fixed problems") is misleading to the
    // user.
    //
    // Correct semantic: a build that passes with zero reviewer mutations means
    // the reviewer found nothing to fix → CLEAN. The verdict downgrade only
    // fires when build is healthy; if build is broken we keep the prior path
    // (UNRESOLVED_ISSUES already dominates).
    if (raw == ReviewerResult.Overall.FIXED_ISSUES
        && buildPassed
        && !hasWriteCalls(general)
        && !hasWriteCalls(wiring)) {
      log.info(
          "ReviewerAgent verdict downgrade FIXED_ISSUES → CLEAN: zero writes across both passes,"
              + " build passing — nothing was actually fixed");
      return ReviewerResult.Overall.CLEAN;
    }
    return raw;
  }

  private static boolean completedReviewPass(WorkerResult result) {
    return result != null && result.outcome() == WorkerResult.Outcome.COMPLETED;
  }

  /**
   * Original verdict computation, extracted so {@link #determineOverall} can layer the write-aware
   * downgrade on top without restructuring the existing decision tree.
   */
  private static ReviewerResult.Overall determineOverallRaw(
      PassVerdict gV,
      PassVerdict wV,
      WorkerResult general,
      WorkerResult wiring,
      boolean buildPassed) {
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

  /**
   * True iff {@code result} dispatched at least one write-class tool call. Inspects {@link
   * WorkerResult#toolCallLog()} which records {@code "<toolName>(<argsPreview>)"} per dispatch (see
   * {@code WorkerAgent.run}).
   */
  static boolean hasWriteCalls(WorkerResult result) {
    if (result == null || result.toolCallLog() == null) return false;
    for (String entry : result.toolCallLog()) {
      if (entry == null) continue;
      // Entries are formatted as "writeFile(path=...)" / "edit_file(...)" / refused-prefixed
      // variants. Extracting only a leading tool name keeps refused/error prefixes from counting as
      // writes while covering the same write aliases advertised in the reviewer prompts.
      String toolName = leadingToolName(entry);
      if (isWriteToolName(toolName)) return true;
    }
    return false;
  }

  private static String leadingToolName(String entry) {
    int end = 0;
    while (end < entry.length()) {
      char c = entry.charAt(end);
      if (!(Character.isLetterOrDigit(c) || c == '_')) break;
      end++;
    }
    return end == 0 ? "" : entry.substring(0, end);
  }

  private static boolean isWriteToolName(String toolName) {
    return switch (toolName) {
      case "writeFile",
              "write_file",
              "editFile",
              "edit_file",
              "multiEdit",
              "multi_edit",
              "applyPatch",
              "apply_patch",
              "astEdit",
              "ast_edit",
              "move",
              "rename",
              "delete",
              "deletePath",
              "shell",
              "bash",
              "executeCommand",
              "runCommand",
              "terminalCommand",
              "backgroundJob",
              "background_process",
              "installDependencies",
              "testProject",
              "lintProject",
              "formatProject",
              "gitCommit" ->
          true;
      default -> false;
    };
  }

  /**
   * Load a classpath prompt resource. Returns content as UTF-8 string.
   *
   * <p>2026-05-13 — hardened after dev run e5f40b8a failed silently with the upstream {@code
   * IOException} hidden behind an unchained {@code IllegalStateException}. The reviewer caught
   * {@code REVIEW_FAILED} but the JSON error only surfaced "Failed to load
   * prompts/reviewer-general.md" — operators couldn't tell whether the file was missing,
   * unreadable, or had an encoding issue. Now logs the root cause class + message + classpath
   * resource state before re-throwing so the next failure points operators at the right fix.
   */
  private String loadPrompt(String path) {
    ClassPathResource resource = new ClassPathResource(path);
    try {
      if (!resource.exists()) {
        // Distinct error class for "file missing from classpath" vs IO. Operators see this in
        // logs when a deploy ships a JAR with the resource excluded (resource-plugin filter,
        // .gitignore drift, etc).
        log.error(
            "[ReviewerAgent] Classpath resource MISSING — '{}'. JAR may be stale or built with"
                + " a resource exclusion. Try: jar tf <jar> | grep '{}'",
            path,
            path);
        throw new IllegalStateException(
            "Classpath resource not found: " + path + " (deploy artefact issue)");
      }
      return resource.getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      log.error(
          "[ReviewerAgent] IOException loading '{}': {}: {} (resource.exists()={})",
          path,
          e.getClass().getSimpleName(),
          e.getMessage(),
          resource.exists(),
          e);
      throw new IllegalStateException(
          "Failed to load "
              + path
              + " ("
              + e.getClass().getSimpleName()
              + ": "
              + e.getMessage()
              + ")",
          e);
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

  /** No-op build fallbacks are not compilation evidence even when their shell exit is zero. */
  static boolean isBuildVerifiedEvidence(String output) {
    return com.navneet.executespec.smoketest.BuildVerificationResult.parse(output).passed();
  }
}

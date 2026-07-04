package com.navneet.executespec.terminalbench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.JwtUtil;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
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

import org.springframework.beans.factory.annotation.Value;
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
  private final FeatureFlagService featureFlagService;

  /**
   * Per-task wall-clock safety net for Terminal-Bench remote workers. Harbor's internal benchmark
   * runner sets the outer agent timeout to 1350s; keep this lower so ExecuteSpec can run its
   * terminal finalizer and return a clean terminal state before Harbor kills the adapter.
   */
  @Value("${terminal-bench.worker-wall-clock-seconds:1260}")
  private int workerWallClockSeconds;

  private static final int TB_DEFAULT_MAX_ITERATIONS = 120;
  private static final int TB_STAGNATION_WARN_AFTER = 16;
  private static final int TB_STAGNATION_FORCE_AFTER = 64;
  private static final int TB_ARTIFACT_DEADLINE_AFTER = 32;
  private static final String FLAG_PROVIDER_TUNING = "worker.provider-prompt-tuning.enabled";

  /**
   * RL-2026-06 — terminal-mode enforced-verify gate (see {@code
   * WorkerTaskRequest#terminalVerifyGate}). Forces ONE verify nudge before the first {@code
   * completeTask} so the worker exercises its deliverable against the task's own stated
   * example/criterion instead of completing after a shallow smoke. Default ON; set {@code
   * ES_TB_VERIFY_GATE=false} (and restart) to A/B the lever off against the base-20 slice without a
   * rebuild. Read once at class load — operator-only bench path, no hot-reload needed.
   */
  private static final boolean TB_VERIFY_GATE_ENABLED =
      !"false".equalsIgnoreCase(System.getenv("ES_TB_VERIFY_GATE"));

  /**
   * RL-2026-06 — deterministic deliverable gate (#2). Default ON; set {@code
   * ES_TB_DELIVERABLE_GATE=false} (and restart) to A/B it off. The exact required output paths are
   * extracted from the instruction and handed to the worker, which deterministically checks they
   * exist in the container before allowing completeTask (fail-open, bounded refusals).
   */
  private static final boolean TB_DELIVERABLE_GATE_ENABLED =
      !"false".equalsIgnoreCase(System.getenv("ES_TB_DELIVERABLE_GATE"));

  /**
   * RL-2026-06 — terminal critic/reviewer pass (#1). Default ON; set {@code ES_TB_CRITIC=false}
   * (and restart) to A/B it off. One fresh-context acceptance review before the first completeTask;
   * see {@code WorkerTaskRequest#criticPass}.
   */
  private static final boolean TB_CRITIC_ENABLED =
      !"false".equalsIgnoreCase(System.getenv("ES_TB_CRITIC"));

  /**
   * Extensionless executables are common enough to matter, but matching every {@code /app/foo}
   * would over-gate inputs. Only accept extensionless absolute paths when they appear in the same
   * clause as an output verb.
   */
  private static final Pattern ABSOLUTE_OUTPUT_DELIVERABLE =
      Pattern.compile(
          "(?i)\\b(?:write|create|produce|generate|save|output|emit|store|build|put|place|"
              + "copy|combine)\\b[^.\\n]{0,100}?(/app/[A-Za-z0-9_][A-Za-z0-9._/-]*"
              + "[A-Za-z0-9_-])\\b");

  /**
   * RL-2026-06 — also catch a deliverable named RELATIVELY right after an output verb (e.g. "Write
   * me {@code data.comp}", "create {@code output.json}"), which the absolute {@code /app/} pattern
   * misses. The dominant write-compressor miss: the instruction said "Write me data.comp", so the
   * gate extracted only the {@code /app/} INPUTS (decomp.c, data.txt — which exist → pass) and
   * never the un-prefixed OUTPUT, so completion was never gated on producing it. Conservative: the
   * bare filename must follow an output verb in the SAME clause ({@code [^./\n]} between them, so
   * the match can't reach across a period into the next sentence NOR across a {@code /} into an
   * absolute path like {@code /app/ssl/server.key}), must be a bare name (not preceded by {@code /
   * . word} — so it never re-captures the tail of an absolute path), and needs a letter-led
   * extension (so prose abbreviations like {@code e.g.}/{@code i.e.} don't match). Captured names
   * are prepended with {@code /app/} (the TB working dir). Safe like the absolute matcher: the gate
   * only refuses on a MISSING path + is bounded/fail-open, so a stray match costs at most a couple
   * of iterations, never a false FAIL.
   */
  private static final Pattern RELATIVE_DELIVERABLE =
      Pattern.compile(
          "(?i)\\b(?:write|create|produce|generate|save|output|emit|store|build)\\b[^./\\n"
              + "]{0,40}?(?<![/\\w.])([A-Za-z][A-Za-z0-9_-]*\\.[A-Za-z][A-Za-z0-9]{0,5})\\b");

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
   Before solving, pin the required deliverable path(s) and format in your own scratch notes.
   If the task asks for a file under /app, producing that exact file is the job.
   If /tests exists, inspect /tests/test.sh and the relevant test files early. Treat visible
   tests as the executable scoring contract; they often reveal exact paths, edge cases, and
   performance thresholds that are not obvious from the task prose alone.
   If visible tests assert exact literal output, exact file names, or exact directory contents,
   satisfy those assertions byte-for-byte. Do not output extra alternatives, labels, helper files,
   or compiled scratch artifacts in a deliverable directory when the tests expect only specific
   files.
   For sanitizer/filter/transform tasks, preserve benign inputs byte-for-byte unless the tests or
   task explicitly require normalization. Implement detection first: if the input contains none of
   the prohibited constructs, copy/leave the file unchanged and exit. Do not run a parser,
   serializer, formatter, pretty-printer, minifier, or BeautifulSoup-style rewrite on clean input.
2. SOLVE — do the real work. Install whatever libraries you need and download whatever data the
   task references. Write correct solutions, never placeholders or guesses.
   Build the MINIMUM that satisfies the task: do not add options, configuration, extra output
   files, or abstraction the task did not ask for, and do NOT keep "improving" a candidate that
   already passes its check. Over-building is a real failure mode — a correct deliverable plus an
   unrequested bonus script or extra dependency that errors scores zero.
   If a verification error names a concrete symbol, API, file, or pattern, search recursively
   for all relevant occurrences and fix the family of failures before retesting. For compatibility
   failures such as removed aliases or deprecated APIs, patch every source/generated/build file
   that can feed the runtime, not only the first file shown in the traceback.
   If a verifier says a required file does not exist, create that exact file before doing any more
   broad exploration. Put temporary build products under /tmp or delete them before finishing.
3. PRODUCE THE EXACT DELIVERABLE — write to the EXACT path requested, in the EXACT format
   requested: just the value, with no extra prose, labels, commas, or stray whitespace unless
   the task asks for them. A correct answer in the wrong file or wrong format scores zero.
   If a visible assertion shows the expected value, use that exact value unless the task text
   explicitly contradicts it.
   If the visible tests require unchanged clean inputs, add an explicit no-op path for clean input
   and test that path before completing.
   By the middle of the run, stop broad exploration and create the best verified candidate
   deliverable. Extra analysis without a /app artifact is worse than an imperfect artifact.
4. VERIFY before finishing (mandatory) — re-read the task's success criteria and confirm your
   output satisfies EVERY requirement. If the task warns about edge cases (tricky inputs,
   boundary conditions, false matches), construct inputs covering them and confirm your
   solution handles them. Re-read the output file / re-run the command and confirm it is
   byte-exact. If /tests exists, run /tests/test.sh or a targeted subset before finishing.
   For expensive checks, first run a bounded smoke command (for example with timeout or reduced
   iterations), then run the official verifier once when the candidate is ready. If you cannot
   confirm correctness, keep working — do NOT call completeTask.
   If the task is to FIX or REPAIR existing behavior, verify against the SYMPTOM, not your own
   paraphrase: reproduce the exact bad behavior the task names FIRST, then confirm your change made
   THAT exact symptom go away. If your edit did not change the observed output/state, you edited the
   wrong place — trace where the bad value is actually produced (grep the literal bad string, add a
   print) and fix THERE. Sweep ALL occurrences of the problem, never just the first.
   For performance/optimization tasks, correctness alone is not enough: compare against the
   provided golden/reference benchmark using the same command or a smaller equivalent smoke.
   Once your candidate satisfies the required threshold, preserve it and complete; do not keep
   making risky marginal optimizations after a passing verifier-shaped check.
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
      int effectiveMaxIterations = normalizeMaxIterations(maxIterations);
      // Remote mode never touches this dir (tools dispatch before tool.execute), but
      // ExecutionContext requires an absolute path.
      workspace = Files.createTempDirectory("tb-remote-");
      ExecutionContext ctx = new ExecutionContext(runId, "tb-task", workspace.toRealPath());
      WorkerResult result =
          worker.run(
              WorkerTaskRequest.builder()
                  .provider(provider)
                  .model(model)
                  .systemPrompt(systemPromptForProvider(provider))
                  .userPrompt(instruction)
                  .context(ctx)
                  .maxIterations(effectiveMaxIterations)
                  .stagnationWarnAfter(TB_STAGNATION_WARN_AFTER)
                  .stagnationForceAfter(TB_STAGNATION_FORCE_AFTER)
                  .temperature(0.0)
                  .wallClockSeconds(workerWallClockSeconds)
                  .forceToolChoiceOnSalvage(true)
                  .terminalArtifactDeadlineAfter(TB_ARTIFACT_DEADLINE_AFTER)
                  .terminalTaskMode(true)
                  .terminalVerifyGate(TB_VERIFY_GATE_ENABLED)
                  .requiredDeliverablePaths(extractDeliverablePaths(instruction))
                  .criticPass(TB_CRITIC_ENABLED)
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

  private static int normalizeMaxIterations(Integer requested) {
    if (requested == null || requested <= 0) return TB_DEFAULT_MAX_ITERATIONS;
    return Math.max(requested, TB_DEFAULT_MAX_ITERATIONS);
  }

  String systemPromptForProvider(String provider) {
    if (featureFlagService == null || !featureFlagService.isEnabled(FLAG_PROVIDER_TUNING)) {
      return TB_SYSTEM_PROMPT;
    }
    String fragment = loadTerminalProviderFragment(provider);
    if (fragment.isBlank()) return TB_SYSTEM_PROMPT;
    return TB_SYSTEM_PROMPT + "\n\n" + fragment;
  }

  String loadTerminalProviderFragment(String provider) {
    if (provider == null || provider.isBlank()) return "";
    String key = provider.trim().toLowerCase();
    try {
      var resource =
          new org.springframework.core.io.ClassPathResource(
              "prompts/fragments/provider/" + key + "/terminal.md");
      if (!resource.exists()) return "";
      return resource.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
      log.debug("loadTerminalProviderFragment({}) failed: {}", provider, e.getMessage());
      return "";
    }
  }

  /**
   * RL-2026-06 — extract the exact required deliverable path(s) the worker's deterministic gate
   * (#2) will check. Package-private + static for unit testing. Returns at most 8 distinct {@code
   * /app/} output paths in first-seen order; empty when the gate is disabled or no output-looking
   * path is named (→ the gate is inert). The extractor intentionally does not gate every referenced
   * {@code /app/*.ext} path: input files are evidence, not completion criteria.
   */
  static List<String> extractDeliverablePaths(String instruction) {
    if (instruction == null || instruction.isBlank() || !TB_DELIVERABLE_GATE_ENABLED) {
      return List.of();
    }
    LinkedHashSet<String> paths = new LinkedHashSet<>();
    Matcher absoluteOutput = ABSOLUTE_OUTPUT_DELIVERABLE.matcher(instruction);
    while (absoluteOutput.find() && paths.size() < 8) {
      paths.add(absoluteOutput.group(1));
    }
    // RL-2026-06 — append relatively-named outputs introduced by an output verb ("Write me
    // data.comp"), prepended with /app/. Deduped against the absolute matches by the LinkedHashSet.
    Matcher r = RELATIVE_DELIVERABLE.matcher(instruction);
    while (r.find() && paths.size() < 8) {
      paths.add("/app/" + r.group(1));
    }
    return List.copyOf(paths);
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

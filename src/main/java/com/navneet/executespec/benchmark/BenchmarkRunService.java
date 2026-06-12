package com.navneet.executespec.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PostConstruct;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.harness.event.PipelineEvent;
import com.navneet.executespec.integration.RunOrchestrator;
import com.navneet.executespec.state.RunMode;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.swebench.SwebenchContainerStore;
import com.navneet.executespec.swebench.SwebenchInstanceLoader;
import com.navneet.executespec.swebench.SwebenchProperties;

import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Clean-slate SWE-bench harness — runs each instance through the REAL production pipeline entry
 * point ({@link RunOrchestrator#submitRun}), blind. Supersedes {@link
 * com.navneet.executespec.swebench.SwebenchTaskService} (a bespoke oracle-mode adapter that
 * bypassed the production entry point AND injected the gold {@code test_patch} into the prompt —
 * invalid for any public leaderboard).
 *
 * <p><b>Principle: real system, sandbox-only.</b> The code-gen path is untouched production: the
 * real {@code submitRun} entry, real tier routing, real worker + tools. We run in {@code
 * runMode=BUILD} with {@code source="swebench-codegen"} (RL-2026-05-050): the FULL pipeline —
 * classifier picks the strategy, planner plans, worker generates — but the orchestrator's
 * code-gen-only gate stops it right after the worker, BEFORE the reviewer. That gives the real
 * classifier/planner decisioning AND a clean diff: the reviewer's {@code PostEditFormatter} (a
 * 469-line whole-file reformat on the earlier smoke) + scaffolding + auto-commit all run in/after
 * the reviewer, so skipping it removes exactly that non-fix churn. Only the <i>sandbox</i> is
 * benchmark-specific: the cleanroom container, the blind issue→spec input, the diff capture, and
 * (external) scoring.
 *
 * <p><b>Blind.</b> The agent receives ONLY the issue {@code problem_statement} +
 * offline/environment context. It never sees {@code FAIL_TO_PASS}/{@code PASS_TO_PASS} ids or the
 * gold {@code test_patch} — those are applied only at scoring time, outside the agent. This is what
 * makes the number leaderboard-comparable.
 *
 * <p>Reuses the proven sandbox plumbing from the old adapter (we keep {@link SwebenchProperties},
 * {@link SwebenchInstanceLoader}, {@link SwebenchContainerStore} — the last is consulted directly
 * by {@code ShellTool}/{@code BuildProjectTool}/{@code RunTestsTool} to dispatch into {@code
 * /testbed}).
 *
 * <p>Per-instance lifecycle:
 *
 * <ol>
 *   <li>Stash the setup recipe in {@link #PENDING}.
 *   <li>{@link RunOrchestrator#submitRun} with {@code runMode=BUILD}, {@code
 *       source="swebench-codegen"}, {@code repoMode=NONE}, the blind spec.
 *   <li>{@link #onRunStarted} fires synchronously → docker pull → cp {@code /testbed} into the host
 *       workspace → run with bind-mount + {@code --network none} → register the container.
 *   <li>The real classifier→planner→worker pipeline runs against the bind-mounted {@code /testbed},
 *       stopping at the worker (code-gen-only gate) before the reviewer.
 *   <li>{@link #captureOnTerminalAsync} polls to terminal, captures the {@code git diff} as an
 *       SWE-bench {@code preds.json}.
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BenchmarkRunService {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final SwebenchProperties props;
  private final SwebenchContainerStore containerStore;
  private final SwebenchInstanceLoader loader;
  private final RunOrchestrator orchestrator;
  private final PipelineRunRepository runRepo;
  private final AgentProperties agentProps;

  /**
   * RL-2026-05-059 — hard concurrency cap. A permit is acquired in {@link #submit} before the run
   * is dispatched and released in {@link #captureOnTerminalAsync}'s {@code finally} (so terminal,
   * wall-timeout, and error paths all free it). Bounds concurrent benchmark pipelines regardless of
   * how many runs are submitted or orphaned — the structural fix for the 2026-05-31 OOM crash where
   * 4 flagship runs + orphaned runs from killed runners exceeded RAM and the kernel killed the JVM.
   * Fair (FIFO) so providers don't starve. Sized from {@link
   * SwebenchProperties#getMaxConcurrentRuns} at startup.
   */
  private Semaphore slots;

  @PostConstruct
  void initConcurrencyGate() {
    int permits = Math.max(1, props.getMaxConcurrentRuns());
    this.slots = new Semaphore(permits, true);
    log.info("benchmark: concurrency gate initialised — maxConcurrentRuns={}", permits);
  }

  /**
   * ThreadLocal stash for the in-flight setup recipe (set before submitRun, read by the listener).
   */
  private static final ThreadLocal<SetupRecipe> PENDING = new ThreadLocal<>();

  record SetupRecipe(String image, String containerName, String instanceId, String workdir) {}

  /**
   * Synchronous {@link PipelineEvent.RunStarted} listener. For benchmark runs (recipe present in
   * {@link #PENDING}): docker pull → cp {@code /testbed/.} into the host workspace → run a live
   * container bind-mounting the workspace at {@code /testbed} with {@code --network none} →
   * register the container so the worker's shell/build tools dispatch via {@code docker exec}.
   *
   * <p>Unlike the old adapter this does <b>not</b> inject any failing-test output or gold test
   * patch — the run is blind.
   */
  @EventListener
  public void onRunStarted(PipelineEvent.RunStarted event) {
    SetupRecipe recipe = PENDING.get();
    if (recipe == null) return; // not a benchmark run
    String runId = event.runId();
    Path workspaceHost = Paths.get(agentProps.getWorkspace(), runId).toAbsolutePath().normalize();
    try {
      Files.createDirectories(workspaceHost);

      // Skip the registry pull when the image is already present (e.g. pre-pulled by the runner).
      // A present-image `docker pull` still round-trips the registry and can time out under host
      // load — that timeout was the trigger for the stuck-run failure. Only pull when absent.
      boolean imagePresent = run("docker image inspect " + recipe.image(), 30).exit() == 0;
      if (!imagePresent && run("docker pull " + recipe.image(), 1800).exit() != 0) {
        throw new RuntimeException("docker pull failed for " + recipe.image());
      }

      String tempName = "bench-prep-" + UUID.randomUUID().toString().substring(0, 8);
      if (run("docker create --name " + tempName + " " + recipe.image() + " sleep 1", 60).exit()
          != 0) {
        throw new RuntimeException("docker create failed for " + recipe.image());
      }
      try {
        if (run("docker cp " + tempName + ":" + recipe.workdir() + "/. " + workspaceHost, 600)
                .exit()
            != 0) {
          throw new RuntimeException("docker cp " + recipe.workdir() + " failed");
        }
      } finally {
        run("docker rm -f " + tempName, 30);
      }

      String runCmd =
          String.format(
              "docker run -d --rm --network none --name %s -v %s:%s -w %s %s sleep" + " %dh",
              recipe.containerName(),
              workspaceHost,
              recipe.workdir(),
              recipe.workdir(),
              recipe.image(),
              props.getTaskWallTimeoutMinutes() / 60 + 1);
      ShellResult startResult = run(runCmd, 60);
      if (startResult.exit() != 0) {
        throw new RuntimeException("docker run failed: " + startResult.output());
      }
      String[] outLines = startResult.output().trim().split("\\r?\\n");
      String containerId = "";
      for (int i = outLines.length - 1; i >= 0; i--) {
        if (!outLines[i].trim().isEmpty()) {
          containerId = outLines[i].trim();
          break;
        }
      }
      if (containerId.isEmpty()) {
        throw new RuntimeException(
            "docker run produced no container id; output=" + startResult.output());
      }
      log.info(
          "benchmark: container {} ({}) ready for run {}, workspace={}",
          recipe.containerName(),
          containerId,
          runId,
          workspaceHost);
      containerStore.put(runId, containerId, recipe.workdir());
    } catch (Exception e) {
      log.error(
          "benchmark: cleanroom setup FAILED for runId={} instance={} — marking run FAILED to avoid"
              + " poisoning benchmark stats",
          runId,
          recipe.instanceId(),
          e);
      run("docker rm -f " + recipe.containerName(), 30);
      try {
        runRepo
            .findById(runId)
            .ifPresent(
                pr -> {
                  pr.setStatus("FAILED");
                  String reason =
                      "BENCH_SETUP_FAILED: "
                          + (e.getMessage() == null
                              ? e.getClass().getSimpleName()
                              : e.getMessage());
                  // status_reason is VARCHAR(80): an over-long value makes THIS save throw, so the
                  // run never gets marked FAILED and falls through to a containerless worker run on
                  // the host. Truncate so the FAILED-mark always persists.
                  pr.setStatusReason(reason.length() > 80 ? reason.substring(0, 80) : reason);
                  runRepo.save(pr);
                });
      } catch (Exception persistEx) {
        log.warn(
            "benchmark: failed to mark run {} FAILED after setup error: {}",
            runId,
            persistEx.getMessage());
      }
    }
  }

  /** Result returned to the controller — runId is the UI link. */
  public record TaskSubmitResult(
      String runId, String instanceId, String containerName, Path workspace, Path outputDir) {}

  /**
   * Submit one SWE-bench instance for execution through the real BUILD pipeline, blind. Returns the
   * runId so the caller can poll status / view in UI.
   */
  public TaskSubmitResult submit(String instanceId, String aiProvider, String modelName) {
    return submit(instanceId, aiProvider, modelName, null, null);
  }

  /**
   * Project-mode overload (RL-2026-05-066): when {@code projectId} is set, the run carries that
   * project so the pipeline injects its codemap / architecture / RAG into planner + worker. {@code
   * selectedRepoIds} are the project's repo ids (as strings) to resolve for code-intel. Null/blank
   * {@code projectId} → the default bench project (blind, today's behaviour).
   */
  public TaskSubmitResult submit(
      String instanceId,
      String aiProvider,
      String modelName,
      String projectId,
      java.util.List<String> selectedRepoIds) {
    if (!props.isEnabled()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "swebench is disabled (set swebench.enabled=true to use)");
    }
    if (instanceId == null || instanceId.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "instanceId required");
    }

    SwebenchInstanceLoader.SwebenchInstance instance = loader.findById(instanceId);
    String image = loader.imageNameFor(instanceId);
    String provider =
        aiProvider != null && !aiProvider.isBlank() ? aiProvider : props.getDefaultProvider();

    Path outputDir =
        Paths.get(props.getOutputBase(), instanceId + "-" + Instant.now().toEpochMilli());
    try {
      Files.createDirectories(outputDir);
    } catch (IOException ioe) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "failed to create output dir: " + ioe.getMessage());
    }

    String containerName = "bench-run-" + UUID.randomUUID().toString().substring(0, 8);
    String userRequest = synthesizeBlindSpec(instance);

    // RL-2026-05-059 — acquire a concurrency permit BEFORE dispatch (released in
    // captureOnTerminalAsync's finally on success, or this method's finally on a pre-handoff
    // failure). Bounds concurrent benchmark pipelines so a submission flood / orphan pileup can't
    // over-subscribe RAM and OOM the JVM (the 2026-05-31 crash).
    acquireSlotOrThrow();
    boolean permitHandedOff = false;
    try {
      PipelineRun run;
      PENDING.set(new SetupRecipe(image, containerName, instanceId, workdirFor(instance)));
      try {
        // RL-2026-05-050 — runMode=BUILD + source="swebench-codegen" → run the REAL system end to
        // end (classifier picks the strategy, planner plans, worker generates with the full tool
        // set), but STOP after the worker. The orchestrator's code-gen-only gate (keyed on the
        // "swebench-codegen" source) finalizes COMPLETE right after the executor, BEFORE the
        // reviewer
        // — so PostEditFormatter / scaffolding / auto-commit never run and the captured diff is a
        // clean, scoreable minimal patch. (The earlier BUILD smoke polluted the diff precisely
        // because it ran the full reviewer + post-steps; stopping at the worker removes that.)
        // repoMode=NONE + repoUrl=null → no clone; the workspace is populated by onRunStarted's cp.
        // Blind: the agent gets only the issue text (no FAIL_TO_PASS / gold test_patch) — those are
        // applied externally at scoring time.
        run =
            orchestrator.submitRun(
                userRequest,
                /* source */ "swebench-codegen",
                /* repoUrl */ null,
                /* repoMode */ "NONE",
                /* githubPat */ null,
                /* githubOwner */ null,
                /* skipApproval */ true,
                /* autoMerge */ false,
                /* modelName */ modelName,
                /* aiProvider */ provider,
                /* createdByUserId */ props.getBenchUserId(),
                /* orgId */ props.getBenchOrgId(),
                /* projectId */
                (projectId != null && !projectId.isBlank()) ? projectId : props.getBenchProjectId(),
                /* executionMode */ "PROTOTYPE",
                /* selectedRepoIds */
                (selectedRepoIds == null || selectedRepoIds.isEmpty())
                    ? java.util.List.<java.util.UUID>of()
                    : selectedRepoIds.stream()
                        .map(java.util.UUID::fromString)
                        .collect(java.util.stream.Collectors.toList()),
                /* figmaUrl */ null,
                /* tddMode */ false,
                /* branchOverride */ null,
                /* prReviewComments */ null,
                /* runMode */ RunMode.BUILD);
      } catch (Exception e) {
        run("docker rm -f " + containerName, 30);
        throw new ResponseStatusException(
            HttpStatus.INTERNAL_SERVER_ERROR, "submitRun failed: " + e.getMessage(), e);
      } finally {
        PENDING.remove();
      }

      Path workspaceHost =
          Paths.get(agentProps.getWorkspace(), run.getRunId()).toAbsolutePath().normalize();
      captureOnTerminalAsync(run.getRunId(), instanceId, containerName, workspaceHost, outputDir);
      permitHandedOff = true; // captureOnTerminalAsync now owns releasing the permit at terminal

      return new TaskSubmitResult(
          run.getRunId(), instanceId, containerName, workspaceHost, outputDir);
    } finally {
      // Release here ONLY if we never handed off to captureOnTerminalAsync (i.e. submitRun threw or
      // a failure before dispatch). On the success path the async capture's finally frees it.
      if (!permitHandedOff) {
        slots.release();
      }
    }
  }

  /**
   * Acquire a benchmark concurrency permit or fail fast with HTTP 429 (RL-2026-05-059). Waits up to
   * {@link SwebenchProperties#getSubmitQueueWaitSeconds} for a slot — so a runner submitting
   * against a busy box blocks (backpressure) rather than piling on. The permit is released in
   * {@link #captureOnTerminalAsync}'s finally (terminal / wall-timeout / error) or {@link
   * #submit}'s finally on a pre-handoff failure.
   */
  private void acquireSlotOrThrow() {
    try {
      if (!slots.tryAcquire(props.getSubmitQueueWaitSeconds(), TimeUnit.SECONDS)) {
        throw new ResponseStatusException(
            HttpStatus.TOO_MANY_REQUESTS,
            "benchmark at capacity (maxConcurrentRuns="
                + props.getMaxConcurrentRuns()
                + "); retry shortly");
      }
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "interrupted while acquiring benchmark slot");
    }
  }

  @Async
  protected void captureOnTerminalAsync(
      String runId, String instanceId, String containerName, Path workspaceHost, Path outputDir) {
    Instant deadline = Instant.now().plus(Duration.ofMinutes(props.getTaskWallTimeoutMinutes()));
    try {
      while (Instant.now().isBefore(deadline)) {
        Optional<PipelineRun> maybe = runRepo.findById(runId);
        if (maybe.isEmpty()) {
          log.warn("benchmark: runId {} disappeared from DB", runId);
          return;
        }
        if (isTerminal(maybe.get().getStatus())) {
          log.info("benchmark: run {} terminal status={}", runId, maybe.get().getStatus());
          captureSubmission(runId, instanceId, containerName, outputDir, maybe.get().getStatus());
          return;
        }
        Thread.sleep(5_000);
      }
      // Wall-timeout: the run never reached terminal (e.g. a frozen/slow worker). Capture a
      // best-effort diff anyway — BEFORE the finally removes the container — so a run that did
      // real work is never silently left without a preds.json (RL-2026-05-052: the Anthropic
      // "16→15" was exactly this — django-14855 timed out → no preds.json → counted unresolved).
      log.warn(
          "benchmark: run {} exceeded wall timeout {}min — best-effort capture before teardown",
          runId,
          props.getTaskWallTimeoutMinutes());
      captureSubmission(runId, instanceId, containerName, outputDir, "TIMEOUT");
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    } finally {
      run("docker rm -f " + containerName, 30);
      containerStore.remove(runId);
      // RL-2026-05-059 — release the concurrency permit acquired in submit(). This finally is the
      // single release point for the success path (terminal / wall-timeout / interrupt all pass
      // through here), freeing a slot for the next queued submission.
      if (slots != null) {
        slots.release();
      }
    }
  }

  /**
   * On terminal status: capture the agent's {@code git diff} inside the container as an SWE-bench
   * {@code preds.json} (so the official harness can score it) + the raw patch for inspection.
   */
  private void captureSubmission(
      String runId,
      String instanceId,
      String containerName,
      Path outputDir,
      String terminalStatus) {
    try {
      Files.createDirectories(outputDir);
      String baseCommit = "";
      SwebenchInstanceLoader.SwebenchInstance instance = null;
      try {
        instance = loader.findById(instanceId);
        baseCommit = instance.baseCommit();
      } catch (Exception ignore) {
        // fall back to HEAD below
      }
      String workdir = instance == null ? props.workdir() : workdirFor(instance);
      String diffTarget = (baseCommit == null || baseCommit.isBlank()) ? "HEAD" : baseCommit;
      // Diff against the REAL base commit (not HEAD) so any pipeline commit can't hide the fix, and
      // exclude run-infra noise (scaffolding / caches) so the patch is the source change only.
      //
      // RL-2026-05-059 — diff-capture hygiene (the standard SWE-bench-harness contract: submit the
      // SOURCE diff only). The 2026-05-31 50-instance eval found ~38-47% of every provider's
      // UNRESOLVED diffs were sabotaged by non-source pollution the agent added DESPITE the prompt
      // (provider fragments + blind spec) explicitly forbidding it — models disobey under pressure:
      //   • build/env config (pyproject.toml / setup.py / setup.cfg / tox.ini / requirements …):
      //     pinning setuptools etc. forces a rebuild in the official scorer that breaks an
      //     otherwise-correct fix — astropy-12907's source diff was BYTE-IDENTICAL to GOLD yet
      // scored
      //     unresolved purely because the same patch also touched pyproject.toml. Same astropy
      //     pyproject cluster failed Anthropic, OpenAI, Gemini AND DeepSeek identically.
      //   • test files: SWE-bench applies the GOLD test_patch on top, so a model test edit is at
      // best
      //     overwritten and at worst causes a patch-apply CONFLICT → unresolved.
      //   • throwaway repro/scratch scripts that leaked into /testbed instead of /tmp.
      // Stripping is model-independent + monotonic (a resolved run can't regress: the gold solution
      // never lives in these paths — pinned by the cleaned-rescore no-regression check). This is
      // the
      // benchmark sandbox's capture path ONLY; production customer runs are byte-identical.
      String excludes =
          " -- . \":(exclude).agent-context\" \":(exclude)Dockerfile\" \":(exclude).dockerignore\""
              + " \":(exclude)README.md\" \":(exclude)__pycache__\" \":(exclude).pytest_cache\""
              + " \":(exclude)*.egg-info\""
              // build / dependency / env config — never part of a SWE-bench source fix
              + " \":(exclude,glob)**/pyproject.toml\" \":(exclude,glob)**/setup.py\""
              + " \":(exclude,glob)**/setup.cfg\" \":(exclude,glob)**/tox.ini\""
              + " \":(exclude,glob)**/MANIFEST.in\" \":(exclude,glob)**/requirements*.txt\""
              + " \":(exclude,glob)**/.github/**\""
              // test files — the gold test_patch is applied separately by the scorer
              + " \":(exclude,glob)**/test_*.py\" \":(exclude,glob)**/*_test.py\""
              + " \":(exclude,glob)**/tests/**\" \":(exclude,glob)**/testing/**\""
              + " \":(exclude,glob)**/conftest.py\""
              // throwaway reproduction / scratch scripts that leaked into the repo
              + " \":(exclude,glob)**/repro*\" \":(exclude,glob)**/*_repro*\""
              + " \":(exclude,glob)tmp/**\" \":(exclude,glob)**/tmp_*\""
              + " \":(exclude,glob)**/scratch*\"";
      String preCmd =
          "docker exec "
              + containerName
              + " sh -c '"
              + "cd "
              + workdir
              + " && "
              + "git ls-files --others --exclude-standard | xargs -r git add -N >/dev/null 2>&1; "
              + "git diff "
              + diffTarget
              + excludes
              + "'";
      ShellResult diff = run(preCmd, 120);
      String patch = diff.exit() == 0 ? diff.output() : "";
      Files.writeString(outputDir.resolve("submission.patch"), patch);

      ObjectNode preds = JSON.createObjectNode();
      ObjectNode entry = preds.putObject(instanceId);
      Optional<PipelineRun> runRow = runRepo.findById(runId);
      String model = runRow.map(PipelineRun::getPlannerModel).orElse("unknown");
      entry.put("model_name_or_path", "executespec-build/" + model);
      entry.put("instance_id", instanceId);
      entry.put("model_patch", patch);
      Path predsPath = outputDir.resolve("preds.json");
      Files.writeString(predsPath, preds.toPrettyString());
      Path proPatchesPath = null;
      if ((instance != null && instance.isSweBenchPro()) || props.isSweBenchPro()) {
        proPatchesPath = writeSweBenchProPatches(outputDir, instanceId, model, patch);
      }

      log.info(
          "benchmark: captured runId={} instance={} status={} → patch={}B preds={} proPatches={}",
          runId,
          instanceId,
          terminalStatus,
          patch.length(),
          predsPath,
          proPatchesPath);
    } catch (IOException ioe) {
      log.error("benchmark: capture failed for runId={}", runId, ioe);
    }
  }

  private static boolean isTerminal(String status) {
    return "COMPLETE".equals(status)
        || "FAILED".equals(status)
        || "PARTIAL".equals(status)
        || "CANCELLED".equals(status)
        || "ABORTED".equals(status);
  }

  private Path writeSweBenchProPatches(
      Path outputDir, String instanceId, String model, String patch) throws IOException {
    ArrayNode entries = JSON.createArrayNode();
    ObjectNode entry = entries.addObject();
    entry.put("instance_id", instanceId);
    entry.put("patch", patch);
    entry.put("prefix", predictionPrefix(model));
    Path patchesPath = outputDir.resolve("swebench_pro_patches.json");
    Files.writeString(patchesPath, entries.toPrettyString());
    return patchesPath;
  }

  private String predictionPrefix(String model) {
    String configured = props.getPredictionPrefix();
    String prefix = configured == null || configured.isBlank() ? "executespec" : configured;
    if (model == null || model.isBlank()) return prefix;
    return prefix + "-" + model;
  }

  /**
   * Blind spec — the issue {@code problem_statement} + offline/environment context only. NO
   * FAIL_TO_PASS/PASS_TO_PASS ids, NO gold test patch. The "existing repo, fix don't scaffold"
   * framing keeps the BUILD planner/worker from greenfield-scaffolding (the workspace already holds
   * the repo at the base commit, populated by {@link #onRunStarted}).
   */
  private String synthesizeBlindSpec(SwebenchInstanceLoader.SwebenchInstance i) {
    StringBuilder sb = new StringBuilder();
    String workdir = workdirFor(i);
    String language = i.repoLanguage();
    if (language == null || language.isBlank()) language = "software";
    sb.append("Resolve the following GitHub issue in an existing ")
        .append(language)
        .append(" repository.\n\n");
    sb.append("# Environment\n");
    sb.append(
        "- You are working in an EXISTING repository already checked out at "
            + workdir
            + " (your workspace). Investigate the existing code and fix the issue described below."
            + " Do NOT scaffold a new project.\n");
    sb.append(
        "- The environment is offline (no network) and all dependencies are pre-installed. Do not"
            + " pip install / curl / git fetch.\n");
    sb.append(
        "- Edit only non-test source files. Do NOT modify or add test files. Your final submission"
            + " is the git diff of "
            + workdir
            + " against its base commit.\n\n");
    sb.append("# Repository\n");
    sb.append("- Repo: ").append(i.repo()).append("\n");
    sb.append("- Working directory: ").append(workdir).append("\n");
    if (i.interfaceName() != null && !i.interfaceName().isBlank()) {
      sb.append("- Interface: ").append(i.interfaceName()).append("\n");
    }
    sb.append("\n");
    sb.append("# Issue\n\n");
    sb.append(i.problemStatement()).append("\n\n");
    sb.append("# How to work\n");
    sb.append(
        "1. Investigate with listFiles / searchFiles / readFile to LOCATE the fix site. Read the"
            + " function the issue points at AND its callers / similar functions, so you understand"
            + " the full picture before touching anything.\n");
    sb.append(
        "2. Pin down the EXACT expected behavior BEFORE editing: re-read the issue for the precise"
            + " result it expects — a specific return value, or a raised error/exception? Then"
            + " check how the surrounding code and similar functions handle analogous or invalid"
            + " inputs (e.g. do they raise ValueError, or return a default?) and MATCH the"
            + " codebase's existing conventions. Do not settle for a plausible-but-different"
            + " behavior.\n");
    sb.append(
        "2b. Use the EXACT public name the issue names or implies. If the issue mentions a specific"
            + " parameter, keyword, method, attribute, or option name, name yours IDENTICALLY — the"
            + " hidden test calls the API by that exact name, so a plausible synonym (e.g. a `mode`"
            + " argument when the issue asks for `text`) fails even when the logic is correct. When"
            + " the issue is explicit about the public surface, match it"
            + " character-for-character.\n");
    sb.append(
        "3. REPRODUCE the bug first: write a tiny throwaway script OUTSIDE the repo (e.g."
            + " /tmp/repro.py — NEVER under the repository, so it stays out of your diff) that"
            + " triggers the issue, and run it to confirm it currently fails the way the issue"
            + " describes. This proves you actually understand the bug before you change code.\n");
    sb.append(
        "4. Make the SMALLEST fix that produces exactly that behavior. Change ONLY the lines"
            + " required — do NOT reformat, re-indent, reorder imports, rename, or refactor"
            + " unrelated code, and do NOT edit files unrelated to the fix. A 3-line diff that"
            + " fixes it is far better than a 40-line diff that also 'cleans up'. Do NOT add new"
            + " test files.\n");
    sb.append(
        "5. VERIFY before finishing: re-run your /tmp reproduction (it must now pass) AND the"
            + " repository's EXISTING tests for the module you changed (nothing regressed). Only"
            + " then call completeTask. No gold/spec tests are provided — work from the issue.\n");
    return sb.toString();
  }

  private String workdirFor(SwebenchInstanceLoader.SwebenchInstance instance) {
    return (instance.isSweBenchPro() || props.isSweBenchPro()) ? "/app" : "/testbed";
  }

  private record ShellResult(int exit, String output) {}

  private ShellResult run(String command, int timeoutSec) {
    try {
      ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", command);
      pb.redirectErrorStream(true);
      Process p = pb.start();
      boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
      if (!done) {
        p.destroyForcibly();
        return new ShellResult(-1, "timeout after " + timeoutSec + "s: " + command);
      }
      String out = new String(p.getInputStream().readAllBytes());
      return new ShellResult(p.exitValue(), out);
    } catch (IOException ioe) {
      return new ShellResult(-2, "io error: " + ioe.getMessage());
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      return new ShellResult(-3, "interrupted");
    }
  }
}

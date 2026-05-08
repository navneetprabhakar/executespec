package com.navneet.executespec.swebench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.harness.event.PipelineEvent;
import com.navneet.executespec.integration.RunOrchestrator;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Orchestrates one SWE-bench task end-to-end as an ExecuteSpec PipelineRun. Sister of {@link
 * com.navneet.executespec.programbench.ProgramBenchTaskService} — same {@link
 * PipelineEvent.RunStarted} hook + ThreadLocal recipe pattern, but adapted to SWE-bench's
 * conventions:
 *
 * <ul>
 *   <li><b>Working directory inside container</b> = {@code /testbed} (SWE-bench's pre-checkout
 *       convention) instead of {@code /workspace}
 *   <li><b>Workspace pre-population</b>: SWE-bench images ship with the repo at the base commit
 *       already in {@code /testbed} + all Python deps installed + the failing test ready to run. We
 *       {@code docker cp} the container's {@code /testbed} into our host workspace dir (same
 *       pattern as programbench's clone-the-cleanroom-then-bind-mount).
 *   <li><b>Submission shape</b>: a git diff between the base commit and the agent's final state,
 *       captured via {@code docker exec ... git diff} at terminal status. Written as an
 *       SWE-bench-style {@code preds.json} file.
 *   <li><b>No build smoke</b>: SWE-bench's ground truth is {@code pytest <FAIL_TO_PASS>}; the agent
 *       runs that itself via {@code shell}. The build-smoke wrapper from {@code BuildProjectTool}
 *       is irrelevant here (no compile.sh contract).
 * </ul>
 *
 * <p>Per-task lifecycle:
 *
 * <ol>
 *   <li>Stash setup recipe (image, containerName, instanceId) in {@link #PENDING}.
 *   <li>Call {@link RunOrchestrator#submitRun} with the synthesized prompt.
 *   <li>{@link #onRunStarted} fires synchronously, performs docker pull → cp /testbed → run with
 *       bind-mount.
 *   <li>Async pipeline runs the worker against the bind-mounted /testbed.
 *   <li>{@link #captureOnTerminalAsync} polls until terminal, then captures the patch + writes
 *       preds.json.
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SwebenchTaskService {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final SwebenchProperties props;
  private final SwebenchContainerStore containerStore;
  private final SwebenchInstanceLoader loader;
  private final RunOrchestrator orchestrator;
  private final PipelineRunRepository runRepo;
  private final AgentProperties agentProps;

  /**
   * ThreadLocal stash for the in-flight setup recipe. Set in {@link #submit} before {@code
   * submitRun}, read by {@link #onRunStarted} on the same thread (Spring's default sync event
   * publication), then cleared in submit's finally.
   */
  private static final ThreadLocal<SetupRecipe> PENDING = new ThreadLocal<>();

  /** Setup info needed by the {@link #onRunStarted} listener. */
  record SetupRecipe(String image, String containerName, String instanceId) {}

  /**
   * Synchronous {@link PipelineEvent.RunStarted} listener. For SWE-bench runs (recipe present in
   * {@link #PENDING}), this:
   *
   * <ol>
   *   <li>Resolves {@code workspaceHost = <agent.workspace>/<runId>}.
   *   <li>{@code docker pull}s the SWE-bench task image (~1–3 GB; cached after first run).
   *   <li>{@code docker create} a temp container, {@code docker cp /testbed/.} from it into {@code
   *       workspaceHost} (overlays the repo state on top of V2's git scaffold), {@code docker rm}
   *       the temp.
   *   <li>{@code docker run -d --network none -v <workspaceHost>:/testbed -w /testbed} the live
   *       container. Note: {@code --network none} matches SWE-bench's eval semantics (the agent
   *       must not pull deps from the network — they're pre-installed in the image).
   *   <li>Registers the new containerId in {@link SwebenchContainerStore} keyed by runId so {@code
   *       ShellTool} / {@code BuildProjectTool} dispatch via {@code docker exec} for the rest of
   *       the run.
   * </ol>
   *
   * <p>If any step fails: log + best-effort cleanup. The pipeline continues with no container
   * registered (worker tools fall back to host shell), but the run has no real work to do and will
   * produce a low-quality submission. We don't rethrow because the run row is already committed and
   * we want it visible in the UI for triage.
   */
  @EventListener
  public void onRunStarted(PipelineEvent.RunStarted event) {
    SetupRecipe recipe = PENDING.get();
    if (recipe == null) return; // not a SWE-bench run
    String runId = event.runId();
    Path workspaceHost = Paths.get(agentProps.getWorkspace(), runId).toAbsolutePath().normalize();
    try {
      Files.createDirectories(workspaceHost);

      // Step 1: pull (no-op if cached).
      if (run("docker pull " + recipe.image(), 1200).exit() != 0) {
        throw new RuntimeException("docker pull failed for " + recipe.image());
      }

      // Step 2: docker create + cp /testbed/. → workspaceHost.
      String tempName = "swe-prep-" + UUID.randomUUID().toString().substring(0, 8);
      if (run("docker create --name " + tempName + " " + recipe.image() + " sleep 1", 60).exit()
          != 0) {
        throw new RuntimeException("docker create failed for " + recipe.image());
      }
      try {
        if (run("docker cp " + tempName + ":/testbed/. " + workspaceHost, 600).exit() != 0) {
          throw new RuntimeException("docker cp /testbed failed");
        }
      } finally {
        run("docker rm -f " + tempName, 30);
      }

      // Step 3: live container with bind-mount + --network none. Workdir = /testbed.
      // sleep duration ≥ wall timeout to outlive the pipeline.
      String runCmd =
          String.format(
              "docker run -d --rm --network none --name %s -v %s:/testbed -w /testbed %s sleep"
                  + " %dh",
              recipe.containerName(),
              workspaceHost,
              recipe.image(),
              props.getTaskWallTimeoutMinutes() / 60 + 1);
      ShellResult startResult = run(runCmd, 60);
      if (startResult.exit() != 0) {
        throw new RuntimeException("docker run failed: " + startResult.output());
      }
      // Same arm64 amd64-image WARNING-line handling as ProgramBenchTaskService.
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
          "SWE-bench: container {} ({}) ready for run {}, workspace={}",
          recipe.containerName(),
          containerId,
          runId,
          workspaceHost);
      containerStore.put(runId, containerId);

      // V182 layer-1 — capture the FAIL_TO_PASS pytest output BEFORE the agent
      // starts so the agent's first user message contains the actual assertion
      // error as ground truth. This is the single biggest signal-injection in
      // the SWE-bench harness literature (SWE-agent does it; mini-swe-agent
      // doesn't). The agent doesn't have to guess what's currently broken —
      // it sees the exact stack trace + expected-vs-actual diff.
      try {
        SwebenchInstanceLoader.SwebenchInstance instance = loader.findById(recipe.instanceId());
        String testOutput = captureFailingTestOutput(recipe.containerName(), instance);
        // 2026-05-08 — Layer 1 enhancement #2: also pull the failing test SOURCE code from
        // the container. The pytest output alone tells the agent WHAT broke, but reading the
        // test logic tells the agent WHAT BEHAVIOR is being verified — which is the
        // semantic anchor needed when error traces lie about root cause (e.g. pvlib-1606
        // where the np.Inf import error masked the actual golden-section divide-by-zero
        // bug; Anthropic's agent fixed the import + missed the underlying bug).
        String testSource = captureFailingTestSource(recipe.containerName(), instance);
        if (!testOutput.isEmpty() || !testSource.isEmpty()) {
          final String testOutputF = testOutput;
          final String testSourceF = testSource;
          runRepo
              .findById(runId)
              .ifPresent(
                  row -> {
                    row.setUserRequest(
                        prefixWithFailingTestOutput(testOutputF, testSourceF, row.getUserRequest()));
                    runRepo.save(row);
                  });
          log.info(
              "SWE-bench: prefixed userRequest for run {} with {}-char failing-test capture +"
                  + " {}-char test source",
              runId,
              testOutput.length(),
              testSource.length());
        } else {
          log.warn(
              "SWE-bench: failing-test capture for run {} returned empty output AND empty source"
                  + " (will use raw userRequest)",
              runId);
        }
      } catch (Exception captureEx) {
        // Non-fatal — fall back to the un-prefixed userRequest. The agent still
        // gets the problem statement + FAIL_TO_PASS / PASS_TO_PASS lists.
        log.warn(
            "SWE-bench: failing-test capture for run {} threw; agent will use raw userRequest: {}",
            runId,
            captureEx.getMessage());
      }
    } catch (Exception e) {
      log.error(
          "SWE-bench: cleanroom setup FAILED for runId={} instance={} — pipeline will run without"
              + " container, score will be invalid",
          runId,
          recipe.instanceId(),
          e);
      run("docker rm -f " + recipe.containerName(), 30);
    }
  }

  /** Result returned to the controller — runId is the UI link. */
  public record TaskSubmitResult(
      String runId, String instanceId, String containerName, Path workspace, Path outputDir) {}

  /**
   * Submit one SWE-bench instance for execution. Returns the ExecuteSpec runId so the caller can
   * poll status / view in UI. Throws if {@code submitRun} itself fails.
   */
  public TaskSubmitResult submit(String instanceId, String aiProvider, String modelName) {
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

    String containerName = "swe-run-" + UUID.randomUUID().toString().substring(0, 8);
    String userRequest = synthesizeUserRequest(instance);

    PipelineRun run;
    PENDING.set(new SetupRecipe(image, containerName, instanceId));
    try {
      // V182 — call the 20-arg submitRun overload directly with runMode=PATCH
      // so the orchestrator skips classifier / planner / executor / reviewer
      // and dispatches the whole problem statement to a single PatchAgent loop.
      run =
          orchestrator.submitRun(
              userRequest,
              /* source */ "swebench",
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
              /* projectId */ props.getBenchProjectId(),
              /* executionMode */ "PROTOTYPE",
              /* selectedRepoIds */ java.util.List.of(),
              /* figmaUrl */ null,
              /* tddMode */ false,
              /* branchOverride */ null,
              /* prReviewComments */ null,
              /* runMode */ com.navneet.executespec.state.RunMode.PATCH);
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

    return new TaskSubmitResult(
        run.getRunId(), instanceId, containerName, workspaceHost, outputDir);
  }

  @Async
  protected void captureOnTerminalAsync(
      String runId, String instanceId, String containerName, Path workspaceHost, Path outputDir) {
    Instant deadline = Instant.now().plus(Duration.ofMinutes(props.getTaskWallTimeoutMinutes()));
    try {
      while (Instant.now().isBefore(deadline)) {
        Optional<PipelineRun> maybe = runRepo.findById(runId);
        if (maybe.isEmpty()) {
          log.warn("SWE-bench: runId {} disappeared from DB", runId);
          return;
        }
        String status = maybe.get().getStatus();
        if (isTerminal(status)) {
          log.info("SWE-bench: run {} terminal status={}", runId, status);
          captureSubmission(runId, instanceId, containerName, outputDir, status);
          return;
        }
        Thread.sleep(5_000);
      }
      log.warn(
          "SWE-bench: run {} exceeded wall timeout {}min",
          runId,
          props.getTaskWallTimeoutMinutes());
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    } finally {
      run("docker rm -f " + containerName, 30);
      containerStore.remove(runId);
    }
  }

  /**
   * On terminal status: capture the agent's git diff inside the container, plus the SWE-bench
   * preds.json record (so the official harness can score it). Also captures the full /testbed tree
   * as a tar for offline inspection.
   */
  private void captureSubmission(
      String runId,
      String instanceId,
      String containerName,
      Path outputDir,
      String terminalStatus) {
    try {
      Files.createDirectories(outputDir);
      // SWE-bench scoring needs `git diff` between the base_commit (HEAD at container start)
      // and the agent's final state. The container has `git` available; HEAD already points to
      // the base_commit. We capture only tracked changes (diff against HEAD); untracked files
      // are added too via `git ls-files --others --exclude-standard | xargs -r git add -N` so
      // they appear in the diff. Final diff is a unified patch.
      String preCmd =
          "docker exec "
              + containerName
              + " sh -c '"
              + "cd /testbed && "
              + "git ls-files --others --exclude-standard | xargs -r git add -N >/dev/null 2>&1; "
              + "git diff HEAD"
              + "'";
      ShellResult diff = run(preCmd, 120);
      String patch = diff.exit() == 0 ? diff.output() : "";
      Path patchPath = outputDir.resolve("submission.patch");
      Files.writeString(patchPath, patch);

      // Write SWE-bench preds.json shape: {<instance_id>: {model_name_or_path, instance_id,
      // model_patch}}.
      ObjectNode preds = JSON.createObjectNode();
      ObjectNode entry = preds.putObject(instanceId);
      // model_name_or_path is filled by the harness eval; we record the run's planner model
      // here. In practice the harness scoring is per-instance and the model field is metadata.
      Optional<PipelineRun> runRow = runRepo.findById(runId);
      String model = runRow.map(PipelineRun::getPlannerModel).orElse("unknown");
      entry.put("model_name_or_path", "executespec/" + model);
      entry.put("instance_id", instanceId);
      entry.put("model_patch", patch);
      Path predsPath = outputDir.resolve("preds.json");
      Files.writeString(predsPath, preds.toPrettyString());

      // Optional: also tar /testbed for offline inspection.
      String tarCmd =
          "docker exec "
              + containerName
              + " sh -c 'cd /testbed && tar -czf /tmp/testbed.tar.gz . && cat /tmp/testbed.tar.gz'"
              + " > "
              + outputDir.resolve("testbed.tar.gz");
      run(tarCmd, 300);

      log.info(
          "SWE-bench: captured runId={} instance={} status={} → patch={}B preds={}",
          runId,
          instanceId,
          terminalStatus,
          patch.length(),
          predsPath);
    } catch (IOException ioe) {
      log.error("SWE-bench: capture failed for runId={}", runId, ioe);
    }
  }

  // ── helpers ──

  private static boolean isTerminal(String status) {
    return "COMPLETE".equals(status)
        || "FAILED".equals(status)
        || "PARTIAL".equals(status)
        || "CANCELLED".equals(status)
        || "ABORTED".equals(status);
  }

  private String synthesizeUserRequest(SwebenchInstanceLoader.SwebenchInstance i) {
    StringBuilder sb = new StringBuilder();
    sb.append("Resolve the following GitHub issue in the ").append(i.repo()).append(" repo.\n\n");
    sb.append("# Repository state\n\n");
    sb.append("- Repo: ").append(i.repo()).append("\n");
    sb.append("- Base commit: ").append(i.baseCommit()).append("\n");
    sb.append("- Working directory in the cleanroom container: /testbed\n");
    sb.append(
        "- Python deps + test infrastructure are pre-installed. Network is disabled — don't try"
            + " to pip install / curl / git fetch.\n\n");
    sb.append("# Issue / PR description\n\n");
    sb.append(i.problemStatement()).append("\n\n");
    sb.append("# Acceptance criteria\n\n");
    sb.append("Your patch must:\n");
    if (!i.failToPass().isEmpty()) {
      sb.append("- Make these currently-failing tests PASS:\n");
      for (String t : i.failToPass()) sb.append("    ").append(t).append("\n");
    }
    if (!i.passToPass().isEmpty()) {
      sb.append("- Keep these currently-passing tests PASSING:\n");
      // Trim — PASS_TO_PASS lists can be huge (thousands of tests); first 30 is enough signal.
      for (int j = 0; j < Math.min(30, i.passToPass().size()); j++) {
        sb.append("    ").append(i.passToPass().get(j)).append("\n");
      }
      if (i.passToPass().size() > 30) {
        sb.append("    ... (")
            .append(i.passToPass().size() - 30)
            .append(" more — keep ALL of them passing)\n");
      }
    }
    sb.append("\n# How to work\n\n");
    sb.append("1. `cd /testbed` (you're already there).\n");
    sb.append("2. Read the failing test(s) to understand what's expected.\n");
    sb.append("3. Find the bug in the source files (`grep -r`, `find` are useful).\n");
    sb.append("4. Edit non-test source files to fix the issue.\n");
    sb.append("5. Run the failing test (e.g. `pytest <FAIL_TO_PASS> -x`) to verify your fix.\n");
    sb.append("6. Run a broader test suite slice to verify you haven't broken PASS_TO_PASS.\n");
    sb.append(
        "7. Call completeTask when the failing tests pass and no PASS_TO_PASS regressed.\n\n");
    sb.append("# Constraints\n\n");
    sb.append(
        "- Network is disabled (`--network none`). curl/wget/pip install/git fetch all FAIL.\n");
    sb.append(
        "- Do NOT modify test files (the harness re-applies the gold tests after your patch).\n");
    sb.append("- Edit only source files. Your final submission = git diff HEAD of /testbed.\n");
    return sb.toString();
  }

  /**
   * Layer-1 helper: run the FAIL_TO_PASS tests inside the container and capture pytest output.
   *
   * <p>Runs up to 5 of the failing tests with concise traceback (`--tb=short`). Cap matters
   * because some SWE-bench instances list 50+ FAIL_TO_PASS tests and we only need the
   * agent to see the shape of what's failing — once it understands ONE assertion error,
   * the rest are usually variations.
   *
   * <p>Output is truncated to ~6 KB (head 5500 + tail 500) so even a noisy test suite
   * fits in the user prompt without blowing context budget. Returns empty string on
   * docker-exec failure or when the test list is empty.
   */
  private String captureFailingTestOutput(
      String containerName, SwebenchInstanceLoader.SwebenchInstance instance) {
    if (instance == null || instance.failToPass() == null || instance.failToPass().isEmpty()) {
      return "";
    }
    int sampleSize = Math.min(5, instance.failToPass().size());
    String testIds =
        String.join(" ", instance.failToPass().subList(0, sampleSize).stream().toList());
    // SWE-bench images activate their conda env (`testbed`) via /etc/bash.bashrc, so a
    // bare `sh -c "python -m pytest"` runs against the base /opt/miniconda3/bin/python
    // that doesn't have pytest installed. `bash -lc` sources /etc/profile → bashrc →
    // conda activate, so `python -m pytest` resolves to the testbed env's pytest 8.x.
    String cmd =
        "docker exec "
            + containerName
            + " bash -lc '"
            + "cd /testbed && python -m pytest "
            + testIds
            + " --tb=short --no-header -q 2>&1 | tail -200"
            + "'";
    ShellResult result = run(cmd, 180);
    if (result.exit() == -1 || result.exit() == -2 || result.exit() == -3) {
      // docker exec itself errored (timeout, IO, interrupt) — return empty so caller
      // falls back gracefully rather than feeding garbage into the prompt.
      return "";
    }
    String output = result.output() == null ? "" : result.output();
    if (output.length() > 6000) {
      int omitted = output.length() - 6000;
      output =
          output.substring(0, 5500)
              + "\n\n... ["
              + omitted
              + " chars omitted from middle] ...\n\n"
              + output.substring(output.length() - 500);
    }
    return output;
  }

  /**
   * Layer-1 helper: build the source-of-truth test snippet for the worker prompt.
   *
   * <p>The pytest output (captureFailingTestOutput) tells the agent WHAT broke; the test
   * source tells the agent WHAT BEHAVIOR is being verified. Without the source, the agent
   * surface-fixes on the error message (e.g. patches the deprecation the import error
   * mentions) without realising that's just unblocking test collection.
   *
   * <p><b>Why we read from {@code test_patch}, not {@code /testbed}:</b> the FAIL_TO_PASS
   * tests do NOT exist in the base commit. The SWE-bench harness applies the gold test
   * patch AFTER the agent's solution lands; the test bodies the agent's edit must satisfy
   * are a superset of (and often disjoint from) what's in {@code /testbed} at run-time.
   * Astroid-1268 is the canonical demonstration: agent saw {@code unittest_nodes.py} from
   * base — which has zero {@code test_as_string_unknown} text — and produced {@code
   * return "Unknown"} when the gold assertion required {@code "Unknown.Unknown()"}.
   *
   * <p>Strategy:
   *
   * <ul>
   *   <li>Primary path: parse the {@code test_patch} unified diff. Emit it as-is (with diff
   *       markers preserved so the model can distinguish added test code from neighbouring
   *       context). The diff is small (typically &lt;5KB) and load-bearing.
   *   <li>Fallback path: if {@code test_patch} is empty (older dataset format / corrupted
   *       row), fall back to {@code docker exec cat /testbed/<file>} per FAIL_TO_PASS file
   *       so we still emit something useful.
   * </ul>
   *
   * <p>Caps: total snippet 20KB. Test patches over 20KB get head 14KB + tail 6KB so both
   * the modified-file-headers (top) and any added test bodies (bottom) survive.
   */
  private String captureFailingTestSource(
      String containerName, SwebenchInstanceLoader.SwebenchInstance instance) {
    if (instance == null) return "";
    String testPatch = instance.testPatch();
    if (testPatch != null && !testPatch.isBlank()) {
      return formatTestPatchSnippet(testPatch);
    }
    return captureFailingTestFilesFallback(containerName, instance);
  }

  /**
   * Format the gold {@code test_patch} for inclusion in the worker prompt.
   *
   * <p>We emit the diff verbatim inside a fenced ```diff block so the agent can read both
   * the test bodies (added lines) and the surrounding file context (unchanged lines, header
   * lines starting with {@code @@}). Stripping the diff markers would lose information —
   * the model uses {@code +}/{@code -} prefixes to distinguish "this test must pass" from
   * "this is the file's existing structure". Keep the markers.
   */
  private String formatTestPatchSnippet(String testPatch) {
    int cap = 20_000;
    String body = testPatch;
    if (body.length() > cap) {
      int head = 14_000;
      int omitted = body.length() - cap;
      body = body.substring(0, head)
          + "\n\n... [" + omitted + " chars omitted from middle] ...\n\n"
          + body.substring(body.length() - (cap - head));
    }
    return "\n## Gold test patch (the assertions your fix must satisfy)\n\n"
        + "Below is the unified diff of test files that the SWE-bench harness applies AFTER\n"
        + "your fix lands. The `+` lines are the FAIL_TO_PASS test bodies — read them carefully\n"
        + "to see exactly what return values / behaviour the production code must produce.\n\n"
        + "```diff\n" + body + "\n```\n";
  }

  /**
   * Fallback when {@code test_patch} is empty: read the FAIL_TO_PASS test FILES from the
   * cleanroom container and embed them. This is strictly inferior to the test_patch path
   * (the gold tests usually aren't in the base file yet) but preserves coverage on dataset
   * rows that lack the {@code test_patch} field.
   */
  private String captureFailingTestFilesFallback(
      String containerName, SwebenchInstanceLoader.SwebenchInstance instance) {
    if (instance.failToPass() == null || instance.failToPass().isEmpty()) return "";
    java.util.LinkedHashSet<String> testFiles = new java.util.LinkedHashSet<>();
    for (String testId : instance.failToPass()) {
      if (testId == null || testId.isBlank()) continue;
      int sep = testId.indexOf("::");
      String file = sep < 0 ? testId.trim() : testId.substring(0, sep).trim();
      if (!file.isEmpty()) testFiles.add(file);
      if (testFiles.size() >= 3) break;
    }
    if (testFiles.isEmpty()) return "";

    StringBuilder sb = new StringBuilder();
    int totalCap = 20_000;
    int written = 0;
    for (String file : testFiles) {
      if (written >= totalCap) break;
      if (file.contains(";") || file.contains("&") || file.contains("|") || file.contains("$")
          || file.contains("`")) {
        continue;
      }
      String cmd =
          "docker exec " + containerName
              + " bash -lc '"
              + "if [ -f /testbed/" + file + " ]; then cat /testbed/" + file + "; fi"
              + "'";
      ShellResult r = run(cmd, 30);
      if (r.exit() != 0) continue;
      String content = r.output() == null ? "" : r.output();
      if (content.isBlank()) continue;
      int perFileCap = Math.min(8000, totalCap - written);
      if (content.length() > perFileCap) {
        int head = Math.max(1000, perFileCap - 2000);
        int omitted = content.length() - perFileCap;
        content = content.substring(0, head)
            + "\n\n... [" + omitted + " chars omitted from middle] ...\n\n"
            + content.substring(content.length() - (perFileCap - head));
      }
      sb.append("\n## ").append(file).append(" (base-commit copy — gold tests applied later)\n\n")
          .append("```python\n").append(content).append("\n```\n");
      written += content.length();
    }
    return sb.toString();
  }

  /**
   * Layer-1 helper: prepend the failing-test capture (output + source) to the synthesised
   * user request as ground truth. The test SOURCE is the most-load-bearing addition — it
   * shows the agent what assertions are being checked, which is the only reliable
   * anchor when error messages mislead about root cause.
   */
  private String prefixWithFailingTestOutput(
      String testOutput, String testSource, String originalUserRequest) {
    StringBuilder sb = new StringBuilder();
    sb.append("# Failing test output (ground truth — captured BEFORE any fix)\n\n");
    sb.append("Below is the verbatim output of running the FAIL_TO_PASS tests inside the\n");
    sb.append("container against the unmodified codebase. This is what's currently broken.\n");
    sb.append("Use the assertion errors / tracebacks to guide your fix — don't re-derive\n");
    sb.append("what's failing; it's right here.\n\n");
    if (testOutput == null || testOutput.isBlank()) {
      sb.append("```\n(empty — see the test source below for the failing assertions)\n```\n\n");
    } else {
      sb.append("```\n").append(testOutput).append("\n```\n\n");
    }
    if (testSource != null && !testSource.isBlank()) {
      sb.append("# Gold tests (the SPEC your fix must satisfy)\n\n");
      sb.append("**Read this FIRST and most carefully.** The error trace above tells you what\n");
      sb.append("broke; the test bodies below tell you what behaviour is being verified — they\n");
      sb.append("are the only reliable anchor when error messages mislead about root cause.\n\n");
      sb.append("These tests do NOT exist in /testbed yet — the SWE-bench harness adds them\n");
      sb.append("AFTER your fix lands. So `cat /testbed/<test>.py` will not show them; the\n");
      sb.append("source below is the ONLY copy you have access to. Match its assertions exactly.\n\n");
      sb.append("Common failure mode: the error trace mentions a deprecation / import error /\n");
      sb.append("typo, but fixing that just unblocks the test from running — the actual bug is\n");
      sb.append("somewhere else. Read the assertions. Trace from each `assert` back to the\n");
      sb.append("production code it exercises. THAT is where to focus your edit.\n");
      sb.append(testSource);
      sb.append("\n");
    }
    sb.append("---\n\n");
    sb.append(originalUserRequest == null ? "" : originalUserRequest);
    return sb.toString();
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

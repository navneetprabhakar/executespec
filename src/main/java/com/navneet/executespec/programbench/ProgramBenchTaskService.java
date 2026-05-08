package com.navneet.executespec.programbench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
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
 * Orchestrates one ProgramBench task end-to-end as an ExecuteSpec PipelineRun.
 *
 * <p>Per-task lifecycle:
 *
 * <ol>
 *   <li><b>Stash setup recipe</b> — image / pre-allocated container name / taskId — into a {@link
 *       ThreadLocal} keyed by the calling Tomcat thread.
 *   <li><b>Submit run</b> — synthesize a "reimplement the binary" prompt; call {@link
 *       RunOrchestrator#submitRun} with the synthetic Bench Project/Org/User. {@code
 *       RunOrchestrator} persists the row, calls {@code WorkspaceInitService.ensureRunWorkspace}
 *       which creates {@code <agent.workspace>/<runId>/} with a fresh git repo + .gitignore +
 *       .gitattributes, then publishes {@link PipelineEvent.RunStarted} synchronously on the same
 *       thread.
 *   <li><b>Bootstrap container</b> — the synchronous {@link #onRunStarted} listener picks up the
 *       recipe from {@link ThreadLocal} and the runId from the event, then performs {@code docker
 *       pull} → {@code docker create + cp /workspace/.} into {@code <agent.workspace>/<runId>}
 *       (overlaying the cleanroom files on top of the V2 git scaffold) → {@code docker run -d --rm
 *       --network none -v <agent.workspace>/<runId>:/workspace}. Registers the new containerId in
 *       {@link ProgramBenchContainerStore} keyed by runId so {@code ShellTool} + {@code
 *       BuildProjectTool} dispatch through {@code docker exec} for the rest of the run.
 *   <li><b>Pipeline starts</b> — {@code RunOrchestrator} returns from the listener and dispatches
 *       the async pipeline thread. The worker now sees a unified {@code /workspace} (in-container
 *       via shell tools, host-fs via Read/Write/Edit/ListFiles tools) — both pointing at the same
 *       directory.
 *   <li><b>Wait + capture</b> — async background poll until terminal; on terminal {@code docker cp}
 *       the workspace out, tar at archive root (the layout {@code programbench eval} expects),
 *       remove the container.
 * </ol>
 *
 * <p><b>Why the workspace path was unified (2026-05-07).</b> The previous design bind-mounted a
 * separate host directory ({@code /var/programbench/workspaces/<task-epoch>/}) into the container.
 * The V2 worker's host-side tools (Read/Write/Edit/ListFiles) wrote to the V2 workspace ({@code
 * /opt/app/workspace/<runId>/}) — a DIFFERENT host directory. Files written by the worker never
 * appeared inside the container, so the build-smoke test ran {@code chmod +x ./compile.sh &&
 * ./compile.sh} against an empty {@code /workspace} and failed with "No such file or directory" on
 * every run. Verified against 3 cmatrix runs 2026-05-07 (all PARTIAL, score=30). Unifying the paths
 * means every tool — host-fs and container-fs — sees the same files.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProgramBenchTaskService {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  private final ProgramBenchProperties props;
  private final ProgramBenchContainerStore containerStore;
  private final RunOrchestrator orchestrator;
  private final PipelineRunRepository runRepo;
  private final AgentProperties agentProps;

  /**
   * ThreadLocal slot for the cleanroom setup recipe. Set inside {@link #submit} before calling
   * {@code orchestrator.submitRun(...)}; the {@link #onRunStarted} listener reads it on the same
   * thread (Spring's default event publication is synchronous), then performs docker pull → cp →
   * run against {@code <agent.workspace>/<runId>} so the bind-mounted /workspace and the V2
   * worker's host-fs write target are the SAME directory.
   */
  private static final ThreadLocal<SetupRecipe> PENDING = new ThreadLocal<>();

  /**
   * Setup info needed by the {@link #onRunStarted} listener. The container name is pre-allocated in
   * {@link #submit} so the caller can pass it through to {@link #captureOnTerminalAsync}
   * deterministically (the listener may register a different containerId, but the human-readable
   * name we use for {@code docker rm -f} is stable).
   */
  record SetupRecipe(String image, String containerName, String taskId) {}

  /**
   * Synchronous event listener — fires on {@link PipelineEvent.RunStarted} which is published by
   * {@code RunOrchestrator.submitRun} immediately after persisting the run row + initialising the
   * V2 workspace, and before the async pipeline thread is dispatched.
   *
   * <p>For programbench runs (recipe present in {@link #PENDING} on this thread), this listener:
   *
   * <ol>
   *   <li>resolves {@code workspaceHost = <agent.workspace>/<runId>} — the same directory V2's
   *       {@code WorkspaceInitService.ensureRunWorkspace} just populated with .git, .gitignore,
   *       .gitattributes
   *   <li>{@code docker pull}s the cleanroom image
   *   <li>{@code docker create} a temp container, {@code docker cp /workspace/.} from it into
   *       {@code workspaceHost} (overlays cleanroom files on top of the V2 scaffold), {@code docker
   *       rm} the temp
   *   <li>{@code docker run -d --rm --network none -v <workspaceHost>:/workspace} → live container
   *   <li>registers the new containerId in {@link ProgramBenchContainerStore} keyed by runId
   * </ol>
   *
   * <p>If any step fails, the run row is already committed; we log + best-effort {@code rm -f} the
   * (possibly half-started) container, leaving the cid unregistered. The pipeline will then run
   * with {@code containerStore.get(runId) == null} → ShellTool/BuildProjectTool fall back to host
   * shell against the (mostly empty) V2 scaffold, which produces a low-score submission that {@link
   * #captureSubmission} packages as-is. This keeps the run visible in the UI on setup failure for
   * operator triage.
   */
  @EventListener
  public void onRunStarted(PipelineEvent.RunStarted event) {
    SetupRecipe recipe = PENDING.get();
    if (recipe == null) return; // not a programbench run
    String runId = event.runId();
    Path workspaceHost = Paths.get(agentProps.getWorkspace(), runId).toAbsolutePath().normalize();
    try {
      Files.createDirectories(workspaceHost);

      // Step 1: pull (no-op if cached).
      if (run("docker pull " + recipe.image(), 600).exit() != 0) {
        throw new RuntimeException("docker pull failed for " + recipe.image());
      }

      // Step 2: docker create + cp /workspace/. → workspaceHost. Files overlay on top of V2's
      // .git/.gitignore/.gitattributes from ensureRunWorkspace.
      String tempName = "pb-prep-" + UUID.randomUUID().toString().substring(0, 8);
      if (run("docker create --name " + tempName + " " + recipe.image() + " sleep 1", 60).exit()
          != 0) {
        throw new RuntimeException("docker create failed for " + recipe.image());
      }
      try {
        if (run("docker cp " + tempName + ":/workspace/. " + workspaceHost, 300).exit() != 0) {
          throw new RuntimeException("docker cp failed");
        }
      } finally {
        run("docker rm -f " + tempName, 30);
      }

      // Step 3: live container with bind-mount + --network none. Sleeps long enough to outlive
      // the pipeline (taskWallTimeoutMinutes / 60 + 1 hours).
      String runCmd =
          String.format(
              "docker run -d --rm --network none --name %s -v %s:/workspace -w /workspace %s sleep"
                  + " %dh",
              recipe.containerName(),
              workspaceHost,
              recipe.image(),
              props.getTaskWallTimeoutMinutes() / 60 + 1);
      ShellResult startResult = run(runCmd, 60);
      if (startResult.exit() != 0) {
        throw new RuntimeException("docker run failed: " + startResult.output());
      }
      // Extract the container ID from the LAST non-blank line of output. On arm64 hosts
      // (Apple Silicon dev macs) running an amd64 cleanroom image, docker emits a
      // "WARNING: requested image's platform (linux/amd64) does not match the detected host
      // platform (linux/arm64/v8)..." line on stderr that's merged into stdout BEFORE the
      // cid. A naive `output().trim()` then captures the warning as the cid, every
      // subsequent `docker exec <warning>` returns "404 page not found", and the agent
      // can't run any shell command. Caught locally 2026-05-07 against the Gemini fix
      // verification — worker stagnated trying to invoke shell while the container was
      // actually running fine under its `--name pb-run-...` alias.
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
          "ProgramBench: container {} ({}) ready for run {}, workspace={}",
          recipe.containerName(),
          containerId,
          runId,
          workspaceHost);

      // Step 4: register so ShellTool + BuildProjectTool dispatch via docker exec.
      containerStore.put(runId, containerId);
    } catch (Exception e) {
      log.error(
          "ProgramBench: cleanroom setup FAILED for runId={} task={} — pipeline will run without"
              + " container, score will be invalid",
          runId,
          recipe.taskId(),
          e);
      // best-effort cleanup of any half-started container
      run("docker rm -f " + recipe.containerName(), 30);
      // intentionally not rethrowing — RunOrchestrator's persistRun has already committed,
      // and we want the run row to surface in the UI on setup failure.
    }
  }

  /**
   * Result returned to the controller — runId is the UI link. {@code workspace} is the unified host
   * path (= {@code <agent.workspace>/<runId>}) which both the worker tools and the bind-mounted
   * /workspace point at.
   */
  public record TaskSubmitResult(
      String runId, String taskId, String containerName, Path workspace) {}

  /**
   * Submit one ProgramBench task. Returns the ExecuteSpec runId so the caller can poll status /
   * view in UI. Throws if {@code submitRun} itself fails; cleanroom setup failures are logged but
   * do not block run creation (see {@link #onRunStarted}).
   */
  public TaskSubmitResult submit(String taskId, String aiProvider, String modelName) {
    if (!props.isEnabled()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "programbench is disabled (set programbench.enabled=true to use)");
    }
    if (taskId == null || taskId.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "taskId required");
    }

    TaskMeta meta = readTaskYaml(taskId);
    String image = imageNameFor(taskId);
    String provider =
        aiProvider != null && !aiProvider.isBlank() ? aiProvider : props.getDefaultProvider();

    Path outputDir = Paths.get(props.getOutputBase(), taskId + "-" + Instant.now().toEpochMilli());
    try {
      Files.createDirectories(outputDir);
    } catch (IOException ioe) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "failed to create output dir: " + ioe.getMessage());
    }

    // Pre-allocate the live container name so submit() can pass it through to the capture
    // helper deterministically (the listener generates a different containerId on `docker run`,
    // but the human-readable --name we use for `docker rm -f` is stable across both call sites).
    String containerName = "pb-run-" + UUID.randomUUID().toString().substring(0, 8);

    // 2026-05-08 — pre-discover the cleanroom toolchain (libraries, headers, package-config
    // entries, language toolchains) and inject the inventory into the user prompt. Cmatrix runs
    // 2026-05-07 had Anthropic + Gemini producing real cmatrix.c source code with ncursesw
    // linking, but their compile.sh failed inside the eval container (probably missing
    // pkg-config or wrong -l flag). Surfacing what's actually available before the agent picks
    // its build approach should close the gap. Pull is idempotent — the listener does it
    // again as a no-op. Discovery runs in a throwaway --rm container so no state leaks.
    String toolchainInventory = discoverToolchain(image);

    // Stash recipe → submitRun → V2 ensureRunWorkspace → RunStarted event → listener does
    // docker pull/create/cp/run with workspaceHost = <agent.workspace>/<runId> (= same host
    // dir V2's worker tools write to). After listener returns, async pipeline starts.
    String userRequest = synthesizeUserRequest(meta, toolchainInventory);
    PipelineRun run;
    PENDING.set(new SetupRecipe(image, containerName, taskId));
    try {
      run =
          orchestrator.submitRun(
              userRequest,
              /* source */ "programbench",
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
              /* executionMode */ "PROTOTYPE");
    } catch (Exception e) {
      // submitRun failed — the listener may or may not have fired (depends on where it failed).
      // Best-effort: remove any container the listener may have started.
      run("docker rm -f " + containerName, 30);
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "submitRun failed: " + e.getMessage(), e);
    } finally {
      PENDING.remove();
    }

    // Now we know runId; compute the unified workspace path for capture.
    Path workspaceHost =
        Paths.get(agentProps.getWorkspace(), run.getRunId()).toAbsolutePath().normalize();

    // Async background captures the submission on terminal status.
    captureOnTerminalAsync(run.getRunId(), containerName, workspaceHost, outputDir, taskId);

    return new TaskSubmitResult(run.getRunId(), taskId, containerName, workspaceHost);
  }

  @Async
  protected void captureOnTerminalAsync(
      String runId, String containerName, Path workspaceHost, Path outputDir, String taskId) {
    Instant deadline = Instant.now().plus(Duration.ofMinutes(props.getTaskWallTimeoutMinutes()));
    try {
      while (Instant.now().isBefore(deadline)) {
        Optional<PipelineRun> maybe = runRepo.findById(runId);
        if (maybe.isEmpty()) {
          log.warn("ProgramBench: runId {} disappeared from DB", runId);
          return;
        }
        String status = maybe.get().getStatus();
        if (isTerminal(status)) {
          log.info("ProgramBench: run {} terminal status={}", runId, status);
          captureSubmission(runId, containerName, workspaceHost, outputDir, taskId, status);
          return;
        }
        Thread.sleep(5_000);
      }
      log.warn(
          "ProgramBench: run {} exceeded wall timeout {}min",
          runId,
          props.getTaskWallTimeoutMinutes());
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    } finally {
      // Always clean up the container + store, even on timeout.
      run("docker rm -f " + containerName, 30);
      containerStore.remove(runId);
    }
  }

  private void captureSubmission(
      String runId,
      String containerName,
      Path workspaceHost,
      Path outputDir,
      String taskId,
      String terminalStatus) {
    Path taskDir = outputDir.resolve(taskId);
    Path submissionDir = taskDir.resolve("submission");
    try {
      Files.createDirectories(submissionDir);
      // docker cp guarantees the container's view of /workspace; with the unified bind-mount
      // workspaceHost == /workspace anyway, but capturing through docker is consistent across
      // both the success path and any setup-failure path where the cid wasn't registered.
      run("docker cp " + containerName + ":/workspace/. " + submissionDir.toAbsolutePath(), 300);
      // Tar with files at archive root — programbench eval expects this layout.
      Path archive = taskDir.resolve("submission.tar.gz");
      run("tar -C " + submissionDir + " -czf " + archive + " .", 120);
      log.info(
          "ProgramBench: captured runId={} task={} status={} → {}",
          runId,
          taskId,
          terminalStatus,
          archive);
    } catch (IOException ioe) {
      log.error("ProgramBench: capture failed for runId={}", runId, ioe);
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

  private TaskMeta readTaskYaml(String taskId) {
    Path tasksDir =
        !props.getTasksDir().isBlank()
            ? Paths.get(props.getTasksDir())
            : Paths.get(
                "/opt/programbench-bench/.venv/lib/python3.11/site-packages/programbench/data/tasks");
    Path yaml = tasksDir.resolve(taskId).resolve("task.yaml");
    if (!Files.isRegularFile(yaml)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "task.yaml not found at " + yaml);
    }
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> map = YAML.readValue(yaml.toFile(), Map.class);
      return new TaskMeta(
          taskId,
          String.valueOf(map.getOrDefault("repository", "unknown")),
          String.valueOf(map.getOrDefault("commit", "unknown")),
          String.valueOf(map.getOrDefault("language", "unknown")),
          String.valueOf(map.getOrDefault("difficulty", "unknown")));
    } catch (IOException ioe) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "failed to parse " + yaml + ": " + ioe.getMessage());
    }
  }

  private String imageNameFor(String taskId) {
    return props.getDockerOrg() + "/" + taskId.replace("__", "_1776_") + ":task_cleanroom";
  }

  private String synthesizeUserRequest(TaskMeta m, String toolchainInventory) {
    String inventoryBlock =
        toolchainInventory == null || toolchainInventory.isBlank()
            ? "(toolchain inventory unavailable — discover via dpkg / pkg-config / which once your"
                + " worker container is up)"
            : toolchainInventory.trim();
    return String.format(
        """
Reimplement the compiled program at /workspace from scratch in %s, matching its
observable behavior as closely as possible. The original repository was %s at commit %s
but you do NOT have internet access — you cannot look it up.

# Cleanroom toolchain inventory (pre-discovered for this task)

Use this list when picking your build approach. Don't link against a library that
isn't here. Don't `apt install` (--network none). If a header you need is missing,
choose a different language path.

%s

# Build contract — IMPORTANT

The evaluation harness builds your submission by running:
    chmod +x ./compile.sh && ./compile.sh

from /workspace. Your submission MUST contain a `compile.sh` at /workspace root that
builds your sources to /workspace/executable. The eval WIPES /workspace before running
compile.sh and then extracts your submission tar — so compile.sh must produce a FRESH
executable from your source. ExecuteSpec's build smoke also enforces this (a no-op
compile.sh that leaves the cleanroom binary untouched fails fast).

Examples (pick one matching your source language and the inventory above):

    # C with ncursesw (check the inventory: pkg-config --libs ncursesw / libncursesw5-dev)
    #!/bin/bash
    set -e
    cd $(dirname $0)
    gcc *.c -o executable $(pkg-config --cflags --libs ncursesw 2>/dev/null || echo "-lncursesw")

    # Rust
    #!/bin/bash
    set -e
    cd $(dirname $0)
    cargo build --release --offline
    cp target/release/<binname> executable

    # Go
    #!/bin/bash
    set -e
    cd $(dirname $0)
    go build -o executable .

# How to work

1. Confirm the toolchain inventory above matches what you find via `dpkg -l | grep <lib>`
   or `pkg-config --list-all` if anything is unclear.
2. Explore /workspace — README, man pages, --help / --version output, examples.
3. Run the binary with representative inputs to understand behavior, output format,
   edge cases, and especially error paths (invalid arguments, --help, --version).
4. Write source files in /workspace using the language's standard project layout.
5. Write compile.sh, picking link flags from the inventory. RUN it via buildProject
   and verify the executable was rebuilt and the build smoke passes.
6. Test edge cases: invalid args, --help / --version, exit codes — these matter.
7. Iterate until your binary's behavior matches the original on representative inputs.

# Constraints

- Cleanroom: --network none. curl/wget/git clone/pip install all FAIL — don't try.
- The /workspace tree at completion time is your submission.
- Don't write source files outside /workspace.
""",
        m.language(), m.repository(), m.commit(), inventoryBlock);
  }

  /**
   * Pre-discover the cleanroom toolchain by running a short diagnostic script in a throwaway
   * container off the task image. The output is injected into the user prompt so the agent picks
   * the correct {@code -l<lib>} / {@code pkg-config} invocation from iter 1 instead of guessing.
   *
   * <p>Best-effort — on any failure (image pull, docker run, timeout) returns an empty inventory
   * and the agent falls back to discovering it themselves. Wall-bounded at 60s to keep the
   * synchronous submit path fast even on cold-cache pulls.
   */
  String discoverToolchain(String image) {
    String discoveryScript =
        // dpkg lib + dev packages (1-line each)
        "echo '## installed -dev / lib packages';"
            + " dpkg -l 2>/dev/null | awk '/^ii/ && $2 ~ /(^lib|-dev$)/ {print $2 \" \" $3}' "
            + " | head -50 || true; "
            + "echo '';"
            + " echo '## pkg-config entries';"
            + " pkg-config --list-all 2>/dev/null | sort | head -30 || true;"
            + " echo '';"
            + " echo '## language toolchains';"
            + " for t in gcc clang cc make cmake cargo rustc go python3 ruby node npm java javac"
            + " mvn lua perl ghc; do"
            + " V=$(\"$t\" --version 2>/dev/null | head -1); [ -n \"$V\" ] && echo \"$t: $V\";"
            + " done;"
            + " echo '';"
            + " echo '## /usr/include top-level';"
            + " ls /usr/include 2>/dev/null | head -40 || true;";
    String runCmd =
        "docker run --rm --network none --entrypoint /bin/sh "
            + image
            + " -c "
            + "'"
            + discoveryScript.replace("'", "'\\''")
            + "'";
    ShellResult r = run(runCmd, 60);
    if (r.exit() != 0) {
      log.warn(
          "ProgramBench: toolchain discovery failed (exit={}, output={}); proceeding without"
              + " inventory",
          r.exit(),
          r.output().substring(0, Math.min(200, r.output().length())));
      return "";
    }
    String out = r.output();
    // Cap inventory at 4 KB so the user prompt stays bounded.
    if (out.length() > 4_000) {
      out = out.substring(0, 4_000) + "\n[truncated " + (out.length() - 4_000) + " more chars]\n";
    }
    return out;
  }

  private record TaskMeta(
      String taskId, String repository, String commit, String language, String difficulty) {}

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

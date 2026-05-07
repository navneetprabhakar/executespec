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
 *   <li><b>Bootstrap container</b> — pull cleanroom image; {@code docker create} + {@code docker
 *       cp} {@code /workspace} contents to a host dir; {@code docker run -d --rm --network none -v
 *       hostDir:/workspace} → live container, bind-mounted workspace.
 *   <li><b>Submit run</b> — synthesize a "reimplement the binary" prompt; call {@link
 *       RunOrchestrator#submitRun} with the synthetic Bench Project/Org/User and the host workspace
 *       path. Get back a {@link PipelineRun}.
 *   <li><b>Stash containerId</b> via the {@link PipelineEvent.RunStarted} listener. We pre-stash
 *       the cid in a ThreadLocal before calling {@code submitRun}; {@code RunOrchestrator}
 *       publishes the event synchronously after persisting the run row but before kicking off the
 *       async pipeline thread, so the listener runs on the same thread and atomically transfers the
 *       cid into {@link ProgramBenchContainerStore} keyed by the new runId. Race-free: the worker
 *       thread doesn't observe an empty store between submit and first tool call.
 *   <li><b>Wait + capture</b> — async background poll until terminal; on terminal {@code docker cp}
 *       the workspace out, tar it as {@code submission.tar.gz} with files at archive root (the
 *       layout {@code programbench eval} expects), then remove the container.
 * </ol>
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

  /**
   * ThreadLocal slot for the just-bootstrapped container ID. Set inside {@link #submit} before
   * calling {@code orchestrator.submitRun(...)}; the {@link #onRunStarted} listener picks it up
   * synchronously on the same thread (Spring's default event publication is sync) and transfers it
   * to the {@link ProgramBenchContainerStore} keyed by the new runId.
   */
  private static final ThreadLocal<String> PENDING_CID = new ThreadLocal<>();

  /**
   * Synchronous event listener — fires on {@link PipelineEvent.RunStarted} which is published by
   * {@code RunOrchestrator.submitRun} immediately after persisting the run row and before the async
   * pipeline thread is dispatched. If a cid is sitting in {@link #PENDING_CID} on this thread, this
   * is a programbench run and we stash the cid for that runId.
   */
  @EventListener
  public void onRunStarted(PipelineEvent.RunStarted event) {
    String cid = PENDING_CID.get();
    if (cid != null) {
      containerStore.put(event.runId(), cid);
      log.debug(
          "ProgramBench: stashed cid={} for runId={} via RunStarted event", cid, event.runId());
    }
  }

  /** Result returned to the controller — runId is the UI link. */
  public record TaskSubmitResult(String runId, String taskId, String containerId, Path workspace) {}

  /**
   * Submit one ProgramBench task. Returns the ExecuteSpec runId so the caller can poll status /
   * view in UI. Throws if container bootstrap or submitRun fails.
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

    Path workspaceHost =
        Paths.get(props.getWorkspaceBase(), taskId + "-" + Instant.now().toEpochMilli());
    Path outputDir = Paths.get(props.getOutputBase(), taskId + "-" + Instant.now().toEpochMilli());
    try {
      Files.createDirectories(workspaceHost);
      Files.createDirectories(outputDir);
    } catch (IOException ioe) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "failed to create workspace dirs: " + ioe.getMessage());
    }

    // Step 1: pull (no-op if already cached).
    if (run("docker pull " + image, 600).exit() != 0) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "docker pull failed for " + image);
    }

    // Step 2: copy /workspace from cleanroom image into hostDir.
    String tempName = "pb-prep-" + UUID.randomUUID().toString().substring(0, 8);
    if (run("docker create --name " + tempName + " " + image + " sleep 1", 60).exit() != 0) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "docker create failed");
    }
    try {
      if (run("docker cp " + tempName + ":/workspace/. " + workspaceHost, 300).exit() != 0) {
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "docker cp failed");
      }
    } finally {
      run("docker rm -f " + tempName, 30);
    }

    // Step 3: start the live container with bind-mount + --network none.
    String liveName = "pb-run-" + UUID.randomUUID().toString().substring(0, 8);
    String runCmd =
        String.format(
            "docker run -d --rm --network none --name %s -v %s:/workspace -w /workspace %s sleep"
                + " %dh",
            liveName,
            workspaceHost.toAbsolutePath(),
            image,
            props.getTaskWallTimeoutMinutes() / 60 + 1);
    ShellResult startResult = run(runCmd, 60);
    if (startResult.exit() != 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "docker run failed: " + startResult.output());
    }
    String containerId = startResult.output().trim();
    log.info(
        "ProgramBench: container {} ({}) ready, workspace={}",
        liveName,
        containerId,
        workspaceHost);

    // Step 4: submit the run with cid pre-stashed in ThreadLocal so the synchronous
    // RunStarted event listener (onRunStarted, above) transfers it into the store
    // before the async pipeline thread starts.
    String userRequest = synthesizeUserRequest(meta);
    PipelineRun run;
    PENDING_CID.set(containerId);
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
      // submitRun failed — kill the container we already started.
      run("docker rm -f " + liveName, 30);
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "submitRun failed: " + e.getMessage(), e);
    } finally {
      PENDING_CID.remove();
    }

    // Step 5: async background captures the submission on terminal status.
    captureOnTerminalAsync(run.getRunId(), liveName, workspaceHost, outputDir, taskId);

    return new TaskSubmitResult(run.getRunId(), taskId, containerId, workspaceHost);
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
      // docker cp guarantees the container's view of /workspace; in case bind-mount has any
      // staleness on the host side, capture from the container directly.
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

  private String synthesizeUserRequest(TaskMeta m) {
    return String.format(
        """
        Reimplement the compiled program at /workspace from scratch in %s, matching its
        observable behavior as closely as possible. The original repository was %s at commit %s
        but you do NOT have internet access — you cannot look it up.

        # Build contract — IMPORTANT

        The evaluation harness builds your submission by running:
            chmod +x ./compile.sh && ./compile.sh

        from /workspace. Your submission MUST contain a `compile.sh` at /workspace root that
        builds your sources to /workspace/executable. Examples:

            # C
            #!/bin/bash
            set -e
            cd $(dirname $0)
            gcc *.c -o executable -lncursesw

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

        1. Explore /workspace — README, man pages, --help / --version output, examples
        2. Run the binary with representative inputs to understand behavior, output format,
           edge cases, and especially error paths (invalid arguments, --help, --version)
        3. Write source files in /workspace using the language's standard project layout
        4. Write compile.sh and verify it produces a working executable matching the original
        5. Test edge cases: invalid args, --help / --version, exit codes — these matter
        6. Iterate until your binary's behavior matches the original on representative inputs

        # Constraints

        - Cleanroom: --network none. curl/wget/git clone/pip install all FAIL — don't try.
        - The /workspace tree at completion time is your submission.
        - Don't write source files outside /workspace.
        """,
        m.language(), m.repository(), m.commit());
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

package com.navneet.executespec.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * RR-034 (2026-07-22, P1) — bounded final-commit of a run workspace, extracted out of the {@code
 * RunOrchestrator} god class.
 *
 * <p>The previous inline implementation ignored the {@code git add} wait boolean, never killed a
 * timed-out process, ran {@code git commit} with NO timeout, and left the merged stdout/stderr pipe
 * undrained. A stuck Git hook/index operation or pipe backpressure could pin the orchestration
 * worker indefinitely and still log a misleading exit. Both invocations here are bounded and killed
 * on timeout, output is discarded so no pipe can back up, and a failed/timed-out {@code git add}
 * skips the commit (gates publication) rather than committing an unknown index.
 */
@Component
@Slf4j
public class WorkspaceGitCommitService {

  private static final long ADD_TIMEOUT_SECONDS = 30;
  private static final long COMMIT_TIMEOUT_SECONDS = 60;

  /** Best-effort bounded commit of everything in {@code workspace}. Never throws. */
  public void commitWorkspaceOutputs(String runId, Path workspace) {
    if (workspace == null || !Files.isDirectory(workspace.resolve(".git"))) return;
    try {
      GitProcessResult add = runBoundedGit(workspace, ADD_TIMEOUT_SECONDS, "git", "add", "-A");
      if (!add.completed()) {
        log.warn(
            "WorkspaceGitCommitService run={} commit skipped — 'git add' exceeded {}s and was"
                + " killed",
            runId,
            ADD_TIMEOUT_SECONDS);
        return;
      }
      if (add.exitCode() != 0) {
        log.warn(
            "WorkspaceGitCommitService run={} commit skipped — 'git add' failed exit={}",
            runId,
            add.exitCode());
        return;
      }
      GitProcessResult commit =
          runBoundedGit(
              workspace,
              COMMIT_TIMEOUT_SECONDS,
              "git",
              "commit",
              "--allow-empty",
              "-m",
              "V2 run output for " + runId);
      if (!commit.completed()) {
        log.warn(
            "WorkspaceGitCommitService run={} commit exceeded {}s and was killed",
            runId,
            COMMIT_TIMEOUT_SECONDS);
        return;
      }
      log.info("WorkspaceGitCommitService run={} final commit exit={}", runId, commit.exitCode());
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      log.warn("WorkspaceGitCommitService run={} commit interrupted", runId);
    } catch (Exception e) {
      log.warn("WorkspaceGitCommitService run={} commit failed: {}", runId, e.getMessage());
    }
  }

  /** Typed outcome of a bounded Git invocation; {@code completed=false} means it was killed. */
  record GitProcessResult(boolean completed, int exitCode) {}

  /**
   * Run a Git command with a hard wall-clock bound. Output is discarded (never piped) so a
   * verbose/blocking child cannot cause pipe backpressure that pins the caller; on timeout the
   * whole process is force-destroyed and reaped.
   */
  GitProcessResult runBoundedGit(Path workspace, long timeoutSeconds, String... args)
      throws IOException, InterruptedException {
    Process p =
        new ProcessBuilder(args)
            .directory(workspace.toFile())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    boolean done = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
    if (!done) {
      p.destroyForcibly();
      p.waitFor(5, TimeUnit.SECONDS);
      return new GitProcessResult(false, -1);
    }
    return new GitProcessResult(true, p.exitValue());
  }
}

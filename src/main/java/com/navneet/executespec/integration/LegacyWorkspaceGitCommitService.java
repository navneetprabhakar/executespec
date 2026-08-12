package com.navneet.executespec.integration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Temporary rolling-deployment compatibility for persisted {@code LEGACY_V1} BUILD runs only.
 * Shared-kernel v2 is statically forbidden from this host-Git path and prepares immutable JGit
 * objects at publication admission instead. Delete after the previous/candidate matrix and v1 drain
 * prove no executable legacy run remains.
 */
@Component
@Slf4j
public class LegacyWorkspaceGitCommitService {

  public void commit(String runId, Path workspace) {
    if (workspace == null || !Files.isDirectory(workspace.resolve(".git"))) return;
    try {
      Result add =
          run(
              workspace,
              30,
              "git",
              "-c",
              "core.hooksPath=/dev/null",
              "-c",
              "core.fsmonitor=false",
              "add",
              "-A");
      if (!add.completed() || add.exitCode() != 0) {
        log.warn("Legacy BUILD run={} staging failed; publication remains unprepared", runId);
        return;
      }
      Result commit =
          run(
              workspace,
              60,
              "git",
              "-c",
              "core.hooksPath=/dev/null",
              "-c",
              "core.fsmonitor=false",
              "-c",
              "commit.gpgSign=false",
              "commit",
              "--allow-empty",
              "-m",
              "Legacy V1 run output for " + runId);
      if (!commit.completed() || commit.exitCode() != 0) {
        log.warn("Legacy BUILD run={} commit failed; publication remains unprepared", runId);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      log.warn("Legacy BUILD run={} commit interrupted", runId);
    } catch (Exception failure) {
      log.warn("Legacy BUILD run={} commit failed: {}", runId, failure.getMessage());
    }
  }

  private static Result run(Path workspace, long timeoutSeconds, String... command)
      throws Exception {
    Process process =
        new ProcessBuilder(command)
            .directory(workspace.toFile())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      process.waitFor(5, TimeUnit.SECONDS);
      return new Result(false, -1);
    }
    return new Result(true, process.exitValue());
  }

  private record Result(boolean completed, int exitCode) {}
}

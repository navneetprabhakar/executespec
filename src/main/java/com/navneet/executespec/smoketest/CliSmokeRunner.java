package com.navneet.executespec.smoketest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Minimal smoke runner for {@code runtimeSurface=CLI} projects.
 *
 * <p>V1 test generation (ApiProber) targets live HTTP endpoints and is a no-op for CLI programs.
 * Until the full stdin/stdout fixture harness lands (planner emits {@code cliTestCases}, runner
 * compares expected vs actual stdout), this class provides a baseline signal: invoke the project's
 * best-guess entry point with no stdin, capture exit code + head of stdout/stderr, and surface the
 * result to logs so operators can tell "CLI run actually executed" apart from "CLI run skipped
 * entirely".
 *
 * <p>Entry-point inference is intentionally narrow — file-marker lookup only, no heuristics over
 * source code. If we can't confidently guess the entry, we return a {@link Result#skipped()} result
 * rather than guessing.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CliSmokeRunner {

  /** Hard cap on CLI invocation time. Most CLI smoke invocations finish in <1s. */
  private static final int TIMEOUT_SECONDS = 15;

  /** Truncate captured output so a chatty CLI can't blow up memory / logs. */
  private static final int OUTPUT_HEAD_BYTES = 2_048;

  public Result run(Path workspaceRoot) {
    Optional<String> cmd = inferEntryCommand(workspaceRoot);
    if (cmd.isEmpty()) {
      log.info("CliSmokeRunner: no entry-point heuristic matched in {}, skipping", workspaceRoot);
      return Result.skipped();
    }
    return invoke(workspaceRoot, cmd.get());
  }

  private Optional<String> inferEntryCommand(Path root) {
    if (Files.exists(root.resolve("main.py"))) return Optional.of("python3 main.py");
    if (Files.exists(root.resolve("cli.py"))) return Optional.of("python3 cli.py");
    if (Files.exists(root.resolve("app.py"))) return Optional.of("python3 app.py");
    if (Files.exists(root.resolve("index.js"))) return Optional.of("node index.js");
    if (Files.exists(root.resolve("cli.js"))) return Optional.of("node cli.js");
    if (Files.exists(root.resolve("Cargo.toml"))) return Optional.of("cargo run --quiet");
    if (Files.exists(root.resolve("go.mod"))) return Optional.of("go run .");
    return Optional.empty();
  }

  private Result invoke(Path workspaceRoot, String command) {
    long start = System.currentTimeMillis();
    try {
      ProcessBuilder pb = new ProcessBuilder(List.of("/bin/sh", "-c", command));
      pb.directory(workspaceRoot.toFile());
      Map<String, String> env = pb.environment();
      String inheritedPath = env.get("PATH");
      String home = env.get("HOME");
      env.clear();
      String extraPath =
          "/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin";
      env.put(
          "PATH",
          inheritedPath == null || inheritedPath.isBlank()
              ? extraPath
              : inheritedPath + ":" + extraPath);
      if (home != null) env.put("HOME", home);
      pb.redirectErrorStream(true);

      Process proc = pb.start();
      boolean finished = proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      if (!finished) {
        proc.destroyForcibly();
        long elapsed = System.currentTimeMillis() - start;
        log.warn("CliSmokeRunner TIMEOUT command='{}' after {}s", command, TIMEOUT_SECONDS);
        return new Result(
            Status.TIMEOUT, -1, "timed out after " + TIMEOUT_SECONDS + "s", command, elapsed);
      }
      byte[] head = proc.getInputStream().readNBytes(OUTPUT_HEAD_BYTES);
      int exit = proc.exitValue();
      long elapsed = System.currentTimeMillis() - start;
      Status status = exit == 0 ? Status.PASSED : Status.FAILED;
      log.info(
          "CliSmokeRunner {} command='{}' exit={} durationMs={} outputBytes={}",
          status,
          command,
          exit,
          elapsed,
          head.length);
      return new Result(status, exit, new String(head), command, elapsed);
    } catch (IOException e) {
      log.warn("CliSmokeRunner IO error command='{}': {}", command, e.getMessage());
      return new Result(
          Status.ERROR, -1, e.getMessage(), command, System.currentTimeMillis() - start);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Result(
          Status.ERROR, -1, "interrupted", command, System.currentTimeMillis() - start);
    }
  }

  public enum Status {
    PASSED,
    FAILED,
    TIMEOUT,
    ERROR,
    SKIPPED
  }

  public record Result(
      Status status, int exitCode, String outputHead, String command, long durationMs) {
    public static Result skipped() {
      return new Result(Status.SKIPPED, 0, "", null, 0L);
    }
  }
}

package com.navneet.executespec.smoketest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.navneet.executespec.sandbox.SandboxExecResult;
import com.navneet.executespec.sandboxroute.SandboxCommandRoutingService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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
public class CliSmokeRunner {

  /** Hard cap on CLI invocation time. Most CLI smoke invocations finish in <1s. */
  private static final int TIMEOUT_SECONDS = 15;

  /** Truncate captured output so a chatty CLI can't blow up memory / logs. */
  private static final int OUTPUT_HEAD_BYTES = 2_048;

  private final SandboxCommandRoutingService sandboxRouting;

  public CliSmokeRunner() {
    this(null);
  }

  @Autowired
  public CliSmokeRunner(SandboxCommandRoutingService sandboxRouting) {
    this.sandboxRouting = sandboxRouting;
  }

  public Result run(Path workspaceRoot) {
    return run(null, workspaceRoot);
  }

  public Result run(String runId, Path workspaceRoot) {
    Optional<List<String>> cmd = inferEntryCommand(workspaceRoot);
    if (cmd.isEmpty()) {
      log.info("CliSmokeRunner: no entry-point heuristic matched in {}, skipping", workspaceRoot);
      return Result.skipped();
    }
    if (sandboxRouting != null && sandboxRouting.routingEnabled()) {
      if (runId == null || runId.isBlank()) {
        return new Result(
            Status.ERROR, -1, "sandbox routing requires a run id", display(cmd.get()), 0L);
      }
      return invokeSandbox(runId, workspaceRoot, cmd.get());
    }
    return invokeHost(workspaceRoot, cmd.get());
  }

  private Optional<List<String>> inferEntryCommand(Path root) {
    if (root == null) return Optional.empty();
    if (Files.exists(root.resolve("main.py"))) return Optional.of(List.of("python3", "main.py"));
    if (Files.exists(root.resolve("cli.py"))) return Optional.of(List.of("python3", "cli.py"));
    if (Files.exists(root.resolve("app.py"))) return Optional.of(List.of("python3", "app.py"));
    if (Files.exists(root.resolve("index.js"))) return Optional.of(List.of("node", "index.js"));
    if (Files.exists(root.resolve("cli.js"))) return Optional.of(List.of("node", "cli.js"));
    if (Files.exists(root.resolve("Cargo.toml")))
      return Optional.of(List.of("cargo", "run", "--quiet"));
    if (Files.exists(root.resolve("go.mod"))) return Optional.of(List.of("go", "run", "."));
    return Optional.empty();
  }

  private Result invokeSandbox(String runId, Path workspaceRoot, List<String> command) {
    long start = System.currentTimeMillis();
    SandboxExecResult result =
        sandboxRouting.execute(
            runId,
            workspaceRoot.toAbsolutePath().normalize(),
            command,
            java.time.Duration.ofSeconds(TIMEOUT_SECONDS));
    String output = SandboxCommandRoutingService.combinedOutput(result);
    if (output.length() > OUTPUT_HEAD_BYTES) {
      output = output.substring(0, OUTPUT_HEAD_BYTES);
    }
    Status status =
        switch (result.status()) {
          case COMPLETED -> result.exitCode() == 0 ? Status.PASSED : Status.FAILED;
          case TIMED_OUT -> Status.TIMEOUT;
          case UNAVAILABLE, REFUSED -> Status.ERROR;
        };
    return new Result(
        status,
        result.exitCode(),
        result.unexecuted() ? result.detail() : output,
        display(command),
        System.currentTimeMillis() - start);
  }

  private Result invokeHost(Path workspaceRoot, List<String> command) {
    long start = System.currentTimeMillis();
    String displayed = display(command);
    try {
      ProcessBuilder pb = new ProcessBuilder(command);
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
        log.warn("CliSmokeRunner TIMEOUT command='{}' after {}s", displayed, TIMEOUT_SECONDS);
        return new Result(
            Status.TIMEOUT, -1, "timed out after " + TIMEOUT_SECONDS + "s", displayed, elapsed);
      }
      byte[] head = proc.getInputStream().readNBytes(OUTPUT_HEAD_BYTES);
      int exit = proc.exitValue();
      long elapsed = System.currentTimeMillis() - start;
      Status status = exit == 0 ? Status.PASSED : Status.FAILED;
      log.info(
          "CliSmokeRunner {} command='{}' exit={} durationMs={} outputBytes={}",
          status,
          displayed,
          exit,
          elapsed,
          head.length);
      return new Result(status, exit, new String(head), displayed, elapsed);
    } catch (IOException e) {
      log.warn("CliSmokeRunner IO error command='{}': {}", displayed, e.getMessage());
      return new Result(
          Status.ERROR, -1, e.getMessage(), displayed, System.currentTimeMillis() - start);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Result(
          Status.ERROR, -1, "interrupted", displayed, System.currentTimeMillis() - start);
    }
  }

  private static String display(List<String> command) {
    return String.join(" ", command);
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

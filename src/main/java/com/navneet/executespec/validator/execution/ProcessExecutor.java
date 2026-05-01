package com.navneet.executespec.validator.execution;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ProcessExecutor {

  /** Allowlist of binaries this executor is permitted to run. */
  private static final Set<String> ALLOWED_BINARIES =
      Set.of(
          // Version control
          "git",
          // File inspection (read-only)
          "ls",
          "cat",
          "head",
          "tail",
          "find",
          "grep",
          "wc",
          // Build tools
          "mvn",
          "gradle",
          "npm",
          "npx",
          "node",
          "yarn",
          "pip",
          "pip3",
          "python",
          "python3",
          "pytest",
          "go",
          "cargo",
          "rustc",
          // Code quality
          "ruff",
          "mypy",
          "eslint",
          "tsc",
          "javac",
          // Shell utilities (safe)
          "echo",
          "env",
          "which",
          "date",
          "pwd",
          // Docker (for test execution container)
          "docker");

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class ProcessCommand {
    private List<String> args;
    private Path workingDir;
    @Builder.Default private Duration timeout = Duration.ofSeconds(30);
    @Builder.Default private Map<String, String> environment = Map.of();
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class ProcessResult {
    private int exitCode;
    private String output;
    private Duration duration;
    private boolean timedOut;
    private String error;

    public boolean isSuccess() {
      return exitCode == 0 && !timedOut && error == null;
    }

    public static ProcessResult timeout(Duration timeout) {
      return ProcessResult.builder()
          .exitCode(-1)
          .output("Process timed out after " + timeout.toSeconds() + "s")
          .timedOut(true)
          .duration(timeout)
          .build();
    }

    public static ProcessResult error(Exception e) {
      return ProcessResult.builder()
          .exitCode(-1)
          .output("")
          .error(e.getMessage())
          .duration(Duration.ZERO)
          .build();
    }
  }

  public ProcessResult execute(ProcessCommand command) {
    // Allowlist enforcement — reject any binary not in the permitted set
    if (command.getArgs() != null && !command.getArgs().isEmpty()) {
      String binary = Path.of(command.getArgs().get(0)).getFileName().toString();
      if (!ALLOWED_BINARIES.contains(binary)) {
        log.warn("[ProcessExecutor] Blocked disallowed binary: {}", binary);
        return ProcessResult.builder()
            .exitCode(-1)
            .output("BLOCKED: binary '" + binary + "' is not in the allowed list")
            .error("Blocked by ProcessExecutor allowlist")
            .duration(Duration.ZERO)
            .build();
      }
    }

    Instant start = Instant.now();
    try {
      ProcessBuilder pb =
          new ProcessBuilder(command.getArgs())
              .directory(command.getWorkingDir().toFile())
              .redirectErrorStream(true);

      pb.environment().putAll(command.getEnvironment());

      Process process = pb.start();

      String output;
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(process.getInputStream()))) {
        output = reader.lines().collect(Collectors.joining("\n"));
      }

      boolean completed = process.waitFor(command.getTimeout().toMillis(), TimeUnit.MILLISECONDS);

      Duration elapsed = Duration.between(start, Instant.now());

      if (!completed) {
        process.destroyForcibly();
        log.warn(
            "Process timed out after {}s: {}",
            command.getTimeout().toSeconds(),
            String.join(" ", command.getArgs()));
        return ProcessResult.timeout(command.getTimeout());
      }

      return ProcessResult.builder()
          .exitCode(process.exitValue())
          .output(output)
          .duration(elapsed)
          .timedOut(false)
          .build();

    } catch (java.io.IOException e) {
      // Binary not found (e.g. mypy, ruff not installed) — expected for optional toolchains
      log.debug("Process not found: {} — {}", String.join(" ", command.getArgs()), e.getMessage());
      return ProcessResult.error(e);
    } catch (Exception e) {
      log.error("Process execution failed: {}", String.join(" ", command.getArgs()), e);
      return ProcessResult.error(e);
    }
  }
}

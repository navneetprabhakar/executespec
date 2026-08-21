package com.navneet.executespec.contracts.breaking;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.navneet.executespec.sandbox.SandboxExecResult;
import com.navneet.executespec.sandboxroute.SandboxCommandRoutingService;
import com.navneet.executespec.tool.ExecutionContext;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * E2.7 — shared CLI runner used by the OpenAPI / Proto / GraphQL classifiers. Encapsulates:
 * temp-file write, subprocess exec with bounded timeout, stdout capture, and graceful CLI-not-found
 * degradation (returns null stdout so the caller emits an UNCLASSIFIED diff with a clear "install
 * the CLI" hint).
 *
 * <p>Lives as a Spring component so production can configure CLI paths via {@code application.yml}
 * once the CLIs ship in the worker container.
 */
@Component
@Slf4j
public class CliBreakingChangeRunner {

  private final SandboxCommandRoutingService sandboxRouting;

  public CliBreakingChangeRunner() {
    this(null);
  }

  @Autowired
  public CliBreakingChangeRunner(SandboxCommandRoutingService sandboxRouting) {
    this.sandboxRouting = sandboxRouting;
  }

  /** Result of a CLI invocation. */
  public record CliResult(
      boolean executed, int exitCode, String stdout, String stderr, String unavailableReason) {
    public boolean ok() {
      return executed && exitCode == 0;
    }
  }

  /**
   * Write {@code oldContent} + {@code newContent} to two temp files + shell out to {@code argvFn}
   * (which receives the temp paths and returns the full argv).
   *
   * <p>Caller is responsible for parsing {@code stdout}; this runner only handles the I/O
   * choreography + timeout + cleanup.
   */
  public CliResult run(
      String oldContent,
      String newContent,
      String tempPrefix,
      java.util.function.BiFunction<Path, Path, List<String>> argvFn,
      long timeoutSeconds) {
    Path oldFile = null;
    Path newFile = null;
    try {
      oldFile = Files.createTempFile(tempPrefix + "-old-", ".tmp");
      newFile = Files.createTempFile(tempPrefix + "-new-", ".tmp");
      Files.writeString(oldFile, oldContent == null ? "" : oldContent, StandardCharsets.UTF_8);
      Files.writeString(newFile, newContent == null ? "" : newContent, StandardCharsets.UTF_8);

      List<String> argv = argvFn.apply(oldFile, newFile);
      ProcessBuilder pb = new ProcessBuilder(argv);
      pb.redirectErrorStream(false);
      Process p;
      try {
        p = pb.start();
      } catch (IOException ex) {
        // CLI not on PATH — graceful degradation.
        String hint = argv.isEmpty() ? "(empty argv)" : argv.get(0);
        return new CliResult(
            false,
            -1,
            null,
            null,
            "CLI '" + hint + "' not available on PATH (" + ex.getMessage() + ")");
      }

      boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
      if (!finished) {
        p.destroyForcibly();
        return new CliResult(
            true, -1, "", "(timeout)", "CLI exceeded " + timeoutSeconds + "s timeout");
      }
      String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
      return new CliResult(true, p.exitValue(), stdout, stderr, null);
    } catch (Exception ex) {
      log.warn("[CliBreakingChangeRunner] failed: {}", ex.getMessage());
      return new CliResult(false, -1, null, null, "runner failed: " + ex.getMessage());
    } finally {
      cleanup(oldFile);
      cleanup(newFile);
    }
  }

  /**
   * Exact-generation BUILD entrypoint. Temp inputs live inside the disposable workspace and the
   * classifier runs only through its verification capability; host and ordinary RUN fallback are
   * forbidden.
   */
  public CliResult run(
      ExecutionContext context,
      String oldContent,
      String newContent,
      String tempPrefix,
      java.util.function.BiFunction<Path, Path, List<String>> argvFn,
      long timeoutSeconds) {
    if (context == null || context.verificationSandboxAuthority() == null) {
      return new CliResult(false, -1, null, null, "VERIFICATION_SANDBOX_AUTHORITY_REQUIRED");
    }
    if (sandboxRouting == null) {
      return new CliResult(false, -1, null, null, "verification sandbox routing unavailable");
    }
    Path tempDir = null;
    Path oldFile = null;
    Path newFile = null;
    try {
      tempDir = Files.createTempDirectory(context.workspaceRoot(), ".es-contract-diff-");
      oldFile = Files.createTempFile(tempDir, tempPrefix + "-old-", ".tmp");
      newFile = Files.createTempFile(tempDir, tempPrefix + "-new-", ".tmp");
      Files.writeString(oldFile, oldContent == null ? "" : oldContent, StandardCharsets.UTF_8);
      Files.writeString(newFile, newContent == null ? "" : newContent, StandardCharsets.UTF_8);
      Path containerOld = containerPath(context.workspaceRoot(), oldFile);
      Path containerNew = containerPath(context.workspaceRoot(), newFile);
      List<String> argv = argvFn.apply(containerOld, containerNew);
      SandboxExecResult result =
          sandboxRouting.executeVerification(
              context.verificationSandboxAuthority(),
              argv,
              java.time.Duration.ofSeconds(timeoutSeconds));
      return switch (result.status()) {
        case COMPLETED ->
            new CliResult(true, result.exitCode(), result.stdout(), result.stderr(), null);
        case TIMED_OUT ->
            new CliResult(
                true,
                -1,
                result.stdout(),
                result.stderr(),
                "CLI exceeded " + timeoutSeconds + "s timeout");
        case UNAVAILABLE, REFUSED -> new CliResult(false, -1, null, null, result.detail());
      };
    } catch (Exception ex) {
      log.warn("[CliBreakingChangeRunner] verification failed: {}", ex.getMessage());
      return new CliResult(false, -1, null, null, "runner failed: " + ex.getMessage());
    } finally {
      cleanup(oldFile);
      cleanup(newFile);
      cleanup(tempDir);
    }
  }

  private static Path containerPath(Path workspace, Path file) {
    Path normalizedWorkspace = workspace.toAbsolutePath().normalize();
    Path normalizedFile = file.toAbsolutePath().normalize();
    if (!normalizedFile.startsWith(normalizedWorkspace)) {
      throw new IllegalArgumentException("classifier temp path escaped verification workspace");
    }
    return Path.of("/workspace").resolve(normalizedWorkspace.relativize(normalizedFile));
  }

  private static void cleanup(Path p) {
    if (p != null) {
      try {
        Files.deleteIfExists(p);
      } catch (IOException ignore) {
      }
    }
  }
}

package com.navneet.executespec.smoketest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.navneet.executespec.programbench.ProgramBenchContainerStore;
import com.navneet.executespec.smoketest.TestCommandResolver.TestCommand;
import com.navneet.executespec.swebench.SwebenchContainerStore;
import com.navneet.executespec.testgen.model.TestExecutionSummary;
import com.navneet.executespec.tool.ShellSandboxPolicy;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs the worker-written tests <i>in the code workspace</i> after a BUILD-mode run completes, so
 * the verifiedScore TESTS slot reads a real passed/total instead of an LLM coverage guess.
 * Rationale: RL-2026-05-001.
 *
 * <p>This is the BUILD-mode sibling of {@link com.navneet.executespec.testgen.HeadlessTestExecutor}
 * — but that one runs a <i>separately generated</i> Maven test project against a preview; this one
 * runs whatever tests the worker actually committed, in any supported framework (see {@link
 * TestCommandResolver}).
 *
 * <p><b>Security.</b> Generated tests are untrusted code. We never execute free-form worker text:
 * the command is derived from on-disk markers by {@link TestCommandResolver}, validated through
 * {@link ShellSandboxPolicy}, and dispatched inside the {@code --network none} cleanroom container
 * when one exists (SWE-bench / ProgramBench). In plain BUILD mode no cleanroom container exists, so
 * the tests run on the host shell — the <i>same</i> trust boundary {@code buildProject} already
 * crosses every run when it compiles worker code. The hardened dispatch here mirrors {@link
 * com.navneet.executespec.tool.BuildProjectTool} exactly.
 *
 * <p><b>Fail-safe.</b> Any path that can't produce a trustworthy count — no recognized runner, a
 * blocked command, a timeout, an IO error, or unparseable output — returns {@link Optional#empty()}
 * so the caller falls back to LLM-judged coverage. A wrong hard count is worse than no count.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class WorkspaceTestRunner {

  private static final String EXTRA_PATH =
      "/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin";

  private final TestCommandResolver resolver;
  private final ShellSandboxPolicy sandboxPolicy;
  private final ProgramBenchContainerStore programBenchContainers;
  private final SwebenchContainerStore swebenchContainers;

  /**
   * Resolve, validate, run, and parse the workspace test suite. Never throws — every failure mode
   * collapses to {@link Optional#empty()}.
   */
  public Optional<TestExecutionSummary> runTests(String runId, Path workspaceRoot) {
    if (workspaceRoot == null) return Optional.empty();

    Optional<TestCommand> resolved = resolver.resolve(workspaceRoot);
    if (resolved.isEmpty()) {
      log.debug(
          "WorkspaceTestRunner run={} no recognized test runner — fallback to coverage", runId);
      return Optional.empty();
    }
    TestCommand cmd = resolved.get();

    // Defense in depth: the command is resolver-derived (not worker text), but validate it anyway
    // so a future resolver change can never execute something the shell sandbox would block.
    ShellSandboxPolicy.ValidationResult v =
        sandboxPolicy.validate(cmd.command(), workspaceRoot.toString());
    if (!v.permitted()) {
      log.warn(
          "WorkspaceTestRunner run={} test command blocked by sandbox: {} ({})",
          runId,
          cmd.command(),
          v.reason());
      return Optional.empty();
    }

    String containerId = null;
    String workdir = null;
    if (swebenchContainers != null) {
      String c = swebenchContainers.get(runId);
      if (c != null) {
        containerId = c;
        workdir = swebenchContainers.getWorkdir(runId);
      }
    }
    if (containerId == null && programBenchContainers != null) {
      String c = programBenchContainers.get(runId);
      if (c != null) {
        containerId = c;
        workdir = "/workspace";
      }
    }

    Instant start = Instant.now();
    try {
      ProcessBuilder pb;
      if (containerId != null) {
        pb =
            new ProcessBuilder(
                List.of(
                    "docker", "exec", "-w", workdir, containerId, "/bin/sh", "-c", cmd.command()));
      } else {
        pb = new ProcessBuilder(List.of("/bin/sh", "-c", cmd.command()));
        pb.directory(workspaceRoot.toFile());
      }
      hardenEnv(pb);
      pb.redirectErrorStream(true);

      Process proc = pb.start();
      boolean finished = proc.waitFor(cmd.timeoutSeconds(), TimeUnit.SECONDS);
      if (!finished) {
        proc.destroyForcibly();
        log.warn(
            "WorkspaceTestRunner run={} test command exceeded {}s — fallback to coverage: {}",
            runId,
            cmd.timeoutSeconds(),
            cmd.command());
        return Optional.empty();
      }
      String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      int exit = proc.exitValue();

      Optional<TestExecutionSummary> parsed = TestOutputParser.parse(cmd.framework(), output, exit);
      parsed.ifPresent(
          s -> s.setExecutionDurationMs(Duration.between(start, Instant.now()).toMillis()));
      log.info(
          "WorkspaceTestRunner run={} framework={} source={} → {}",
          runId,
          cmd.framework(),
          cmd.source(),
          parsed
              .map(s -> s.getPassed() + "/" + s.getTotalTests() + " passed")
              .orElse("unparseable (fallback to LLM coverage)"));
      return parsed;
    } catch (IOException e) {
      log.warn(
          "WorkspaceTestRunner run={} IO error — fallback to coverage: {}", runId, e.getMessage());
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }

  /**
   * Mirror of {@link com.navneet.executespec.tool.BuildProjectTool}'s PATH/HOME/JAVA_HOME
   * hardening.
   */
  private static void hardenEnv(ProcessBuilder pb) {
    Map<String, String> env = pb.environment();
    String inheritedPath = env.get("PATH");
    String home = env.get("HOME");
    String javaHome = env.get("JAVA_HOME");
    env.clear();
    env.put(
        "PATH",
        inheritedPath == null || inheritedPath.isBlank()
            ? EXTRA_PATH
            : inheritedPath + ":" + EXTRA_PATH);
    if (home != null) env.put("HOME", home);
    if (javaHome != null) env.put("JAVA_HOME", javaHome);
  }
}

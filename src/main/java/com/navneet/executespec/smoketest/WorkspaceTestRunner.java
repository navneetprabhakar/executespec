package com.navneet.executespec.smoketest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

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
 * blocked command, a timeout, an IO error, or unparseable output — returns an explicit rejected
 * verification verdict. The legacy optional view remains available for non-acceptance consumers.
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
   * Detailed run result (harness-resolution task 8): the parsed counts PLUS the raw output tail and
   * command, so callers (VBC refusals, the auto-verify observation) can show the model WHAT failed
   * instead of only how many. {@code outputTail} is bounded to {@value #DETAIL_TAIL_CHARS} chars.
   */
  public record DetailedResult(
      TestExecutionSummary summary, String outputTail, String command, int exitCode) {}

  static final int DETAIL_TAIL_CHARS = 3_000;

  private static final Pattern COMPILE_FAILURE =
      Pattern.compile(
          "(?is)(compilation error|compilation failed|cannot find symbol|compile(?:r)? error|"
              + "typescript error|syntaxerror:|build failed|build failure|could not compile|"
              + "undefined:|error\\[e\\d+\\]|package .+ is not in std|javac.+error)");
  private static final Pattern MISSING_TOOLCHAIN =
      Pattern.compile(
          "(?is)((?:/bin/)?(?:sh|bash):.*(?:command )?not found|"
              + "(?:exec|spawn).*?(?:enoent|no such file or directory))");

  /**
   * Resolve, validate, run, and parse the workspace test suite. Never throws — every failure mode
   * collapses to {@link Optional#empty()}.
   */
  public Optional<TestExecutionSummary> runTests(String runId, Path workspaceRoot) {
    return runTestsDetailed(runId, workspaceRoot).map(DetailedResult::summary);
  }

  /** Detailed sibling of {@link #runTests} — same fail-safe contract. */
  public Optional<DetailedResult> runTestsDetailed(String runId, Path workspaceRoot) {
    ExecutableVerification verification = verify(runId, workspaceRoot);
    if (verification.summary() == null) return Optional.empty();
    return Optional.of(
        new DetailedResult(
            verification.summary(),
            verification.outputTail(),
            verification.command(),
            verification.exitCode()));
  }

  /**
   * Run the workspace verifier and return one explicit fail-closed terminal verdict. This is the E2
   * acceptance seam; callers must accept only {@link ExecutableVerification.Status#PASSED}.
   */
  public ExecutableVerification verify(String runId, Path workspaceRoot) {
    if (workspaceRoot == null) {
      return verdict(
          ExecutableVerification.Status.MISSING_EVIDENCE,
          ExecutableVerification.FailureStage.EVIDENCE,
          false,
          null,
          null,
          null,
          null,
          null,
          0,
          "",
          "Workspace path is absent.");
    }

    Optional<TestCommand> resolved = resolver.resolve(workspaceRoot);
    if (resolved.isEmpty()) {
      log.warn("WorkspaceTestRunner run={} no recognized executable verifier", runId);
      return verdict(
          ExecutableVerification.Status.MISSING_EVIDENCE,
          ExecutableVerification.FailureStage.EVIDENCE,
          false,
          null,
          null,
          null,
          null,
          null,
          0,
          "",
          "No recognized test runner was found.");
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
      return verdict(
          ExecutableVerification.Status.BLOCKED,
          ExecutableVerification.FailureStage.SANDBOX,
          false,
          null,
          cmd,
          null,
          null,
          null,
          0,
          "",
          "Resolved verifier command was blocked by the sandbox: " + v.reason());
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
    Process proc = null;
    FutureTask<byte[]> outputRead = null;
    String containerPidFile = null;
    try {
      ProcessBuilder pb;
      if (containerId != null) {
        containerPidFile = containerPidFile(runId);
        pb =
            new ProcessBuilder(
                List.of(
                    "docker",
                    "exec",
                    "-w",
                    workdir,
                    containerId,
                    "/bin/sh",
                    "-c",
                    wrapContainerCommand(cmd.command(), containerPidFile)));
      } else {
        pb = new ProcessBuilder(List.of("/bin/sh", "-c", cmd.command()));
        pb.directory(workspaceRoot.toFile());
      }
      hardenEnv(pb);
      pb.redirectErrorStream(true);

      try {
        proc = pb.start();
      } catch (IOException e) {
        log.warn("WorkspaceTestRunner run={} process did not start: {}", runId, e.getMessage());
        return verdict(
            ExecutableVerification.Status.EXECUTION_ERROR,
            ExecutableVerification.FailureStage.EXECUTION,
            false,
            false,
            cmd,
            null,
            null,
            null,
            Duration.between(start, Instant.now()).toMillis(),
            "",
            "Verifier process could not be started: " + e.getClass().getSimpleName());
      }
      Process startedProcess = proc;
      outputRead = new FutureTask<>(() -> startedProcess.getInputStream().readAllBytes());
      Thread.ofVirtual().name("workspace-verifier-output-" + runId).start(outputRead);
      boolean finished = proc.waitFor(cmd.timeoutSeconds(), TimeUnit.SECONDS);
      if (!finished) {
        cleanupContainerProcess(containerId, containerPidFile);
        destroyProcessTree(proc);
        String timeoutOutput = readOutput(outputRead);
        log.warn(
            "WorkspaceTestRunner run={} test command exceeded {}s: {}",
            runId,
            cmd.timeoutSeconds(),
            cmd.command());
        return verdict(
            ExecutableVerification.Status.TIMEOUT,
            ExecutableVerification.FailureStage.TIMEOUT,
            true,
            false,
            cmd,
            null,
            null,
            tail(timeoutOutput),
            Duration.between(start, Instant.now()).toMillis(),
            tail(timeoutOutput),
            "Verifier exceeded " + cmd.timeoutSeconds() + " seconds; process tree terminated.");
      }
      String output = new String(outputRead.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8);
      int exit = proc.exitValue();

      Optional<TestExecutionSummary> parsed = TestOutputParser.parse(cmd.framework(), output, exit);
      if (parsed.isEmpty() && cmd.framework() == TestCommandResolver.Framework.SUREFIRE) {
        // `mvn -q test` suppresses the stdout "Tests run:" summary; the surefire/gradle XML reports
        // are written regardless, so recover the real pass/fail from them. Without this, a run
        // whose
        // tests FAILED was scored UNKNOWN (no penalty, no failed-tests cap). RL-2026-06-049.
        parsed = parseXmlReports(workspaceRoot);
      }
      long durationMs = Duration.between(start, Instant.now()).toMillis();
      parsed.ifPresent(s -> s.setExecutionDurationMs(durationMs));
      log.info(
          "WorkspaceTestRunner run={} framework={} source={} → {}",
          runId,
          cmd.framework(),
          cmd.source(),
          parsed
              .map(s -> s.getPassed() + "/" + s.getTotalTests() + " passed")
              .orElse("unparseable"));
      final String tail = tail(output);
      if (exit == 127 || MISSING_TOOLCHAIN.matcher(output.strip()).find()) {
        return verdict(
            ExecutableVerification.Status.MISSING_TOOLCHAIN,
            ExecutableVerification.FailureStage.TOOLCHAIN,
            true,
            false,
            cmd,
            null,
            exit,
            tail,
            durationMs,
            tail,
            "Required test toolchain is unavailable.");
      }
      // A runner can print a partial test summary before compilation fails in another module.
      // Compilation therefore owns the verdict before parsed assertion counts are considered.
      if (exit != 0 && COMPILE_FAILURE.matcher(output).find()) {
        return verdict(
            ExecutableVerification.Status.COMPILE_FAILED,
            ExecutableVerification.FailureStage.COMPILE,
            true,
            false,
            cmd,
            parsed.orElse(null),
            exit,
            tail,
            durationMs,
            tail,
            "Compilation failed before executable tests completed.");
      }
      if (parsed.isPresent()) {
        TestExecutionSummary summary = parsed.get();
        if (summary.getTotalTests() <= 0) {
          return verdict(
              ExecutableVerification.Status.EMPTY_TESTS,
              ExecutableVerification.FailureStage.TEST_DISCOVERY,
              true,
              false,
              cmd,
              summary,
              exit,
              tail,
              durationMs,
              tail,
              "Verifier ran but discovered zero tests.");
        }
        if (summary.getPassed() == 0 && summary.getFailed() == 0 && summary.getErrors() == 0) {
          return verdict(
              ExecutableVerification.Status.EMPTY_TESTS,
              ExecutableVerification.FailureStage.TEST_DISCOVERY,
              true,
              false,
              cmd,
              summary,
              exit,
              tail,
              durationMs,
              tail,
              "Verifier ran but no test assertion executed (all tests were skipped).");
        }
        if (exit == 0
            && summary.getPassed() > 0
            && summary.getFailed() == 0
            && summary.getErrors() == 0) {
          return verdict(
              ExecutableVerification.Status.PASSED,
              ExecutableVerification.FailureStage.NONE,
              true,
              true,
              cmd,
              summary,
              exit,
              tail,
              durationMs,
              tail,
              summary.getPassed() + "/" + summary.getTotalTests() + " tests passed.");
        }
        return verdict(
            ExecutableVerification.Status.ASSERTION_FAILED,
            ExecutableVerification.FailureStage.TEST_ASSERTION,
            true,
            false,
            cmd,
            summary,
            exit,
            tail,
            durationMs,
            tail,
            summary.getPassed() + "/" + summary.getTotalTests() + " tests passed.");
      }
      return verdict(
          ExecutableVerification.Status.MALFORMED_OUTPUT,
          ExecutableVerification.FailureStage.OUTPUT_PARSE,
          true,
          false,
          cmd,
          null,
          exit,
          tail,
          durationMs,
          tail,
          "Verifier output did not contain trustworthy test counts.");
    } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
      log.warn("WorkspaceTestRunner run={} IO error: {}", runId, e.getMessage());
      return verdict(
          ExecutableVerification.Status.EXECUTION_ERROR,
          ExecutableVerification.FailureStage.EXECUTION,
          true,
          false,
          cmd,
          null,
          null,
          null,
          Duration.between(start, Instant.now()).toMillis(),
          "",
          "Verifier process could not be executed: " + e.getClass().getSimpleName());
    } catch (InterruptedException e) {
      cleanupContainerProcess(containerId, containerPidFile);
      if (proc != null) destroyProcessTree(proc);
      if (outputRead != null) outputRead.cancel(true);
      Thread.currentThread().interrupt();
      return verdict(
          ExecutableVerification.Status.INTERRUPTED,
          ExecutableVerification.FailureStage.INTERRUPTED,
          true,
          false,
          cmd,
          null,
          null,
          null,
          Duration.between(start, Instant.now()).toMillis(),
          "",
          "Verifier execution was interrupted.");
    }
  }

  private static ExecutableVerification verdict(
      ExecutableVerification.Status status,
      ExecutableVerification.FailureStage stage,
      boolean testsRun,
      Boolean testsPassed,
      TestCommand cmd,
      TestExecutionSummary summary,
      Integer exitCode,
      String ignored,
      long durationMs,
      String outputTail,
      String detail) {
    return new ExecutableVerification(
        status,
        stage,
        testsRun,
        testsPassed,
        summary,
        cmd == null ? null : cmd.framework().name(),
        cmd == null ? null : cmd.source(),
        cmd == null ? null : cmd.command(),
        exitCode,
        durationMs,
        outputTail,
        detail);
  }

  private static void destroyProcessTree(Process proc) {
    proc.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
    proc.destroyForcibly();
    try {
      proc.waitFor(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  static String containerPidFile(String runId) {
    String safe = runId == null ? "unknown" : runId.replaceAll("[^A-Za-z0-9._-]", "_");
    return "/tmp/executespec-verifier-" + safe + ".pid";
  }

  static String wrapContainerCommand(String command, String pidFile) {
    return "echo $$ > "
        + shellQuote(pidFile)
        + "; trap \"rm -f "
        + shellQuote(pidFile)
        + "\" EXIT; /bin/sh -c "
        + shellQuote(command);
  }

  private static void cleanupContainerProcess(String containerId, String pidFile) {
    if (containerId == null || pidFile == null) return;
    String script =
        "kill_tree() { for child in $(ps -eo pid=,ppid= | awk -v p=\"$1\" '$2 == p {print $1}'); do"
            + " kill_tree \"$child\"; done; kill -KILL \"$1\" 2>/dev/null || true; }; if [ -f "
            + shellQuote(pidFile)
            + " ]; then "
            + "pid=$(cat "
            + shellQuote(pidFile)
            + "); "
            + "kill_tree \"$pid\"; rm -f "
            + shellQuote(pidFile)
            + "; fi";
    try {
      Process cleanup =
          new ProcessBuilder("docker", "exec", containerId, "/bin/sh", "-c", script)
              .redirectErrorStream(true)
              .start();
      if (!cleanup.waitFor(5, TimeUnit.SECONDS)) cleanup.destroyForcibly();
    } catch (IOException e) {
      log.warn("Unable to clean verifier process in container {}: {}", containerId, e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String shellQuote(String value) {
    return "'" + value.replace("'", "'\"'\"'") + "'";
  }

  private static String readOutput(FutureTask<byte[]> outputRead) {
    try {
      return new String(outputRead.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8);
    } catch (Exception e) {
      outputRead.cancel(true);
      return "";
    }
  }

  private static String tail(String output) {
    if (output == null || output.isEmpty()) return "";
    return output.length() <= DETAIL_TAIL_CHARS
        ? output
        : "... [head omitted] ...\n" + output.substring(output.length() - DETAIL_TAIL_CHARS);
  }

  /**
   * XML-report fallback: scan the workspace for surefire/gradle {@code TEST-*.xml} reports and sum
   * their counts. The reliable signal when {@code mvn -q test} suppressed the stdout summary.
   * Bounded (depth 8, ≤2000 files) and fail-safe (any IO issue → empty). RL-2026-06-049.
   */
  private static Optional<TestExecutionSummary> parseXmlReports(Path root) {
    java.util.List<String> xmls = new java.util.ArrayList<>();
    try (java.util.stream.Stream<Path> walk = java.nio.file.Files.walk(root, 8)) {
      walk.filter(
              p -> {
                String s = p.toString();
                return p.getFileName().toString().startsWith("TEST-")
                    && s.endsWith(".xml")
                    && (s.contains("surefire-reports") || s.contains("test-results"));
              })
          .limit(2000)
          .forEach(
              p -> {
                try {
                  xmls.add(java.nio.file.Files.readString(p));
                } catch (IOException ignored) {
                  // skip an unreadable report rather than fail the whole recovery
                }
              });
    } catch (IOException | RuntimeException e) {
      return Optional.empty();
    }
    return TestOutputParser.parseJunitXmlReports(xmls);
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

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
   * Detailed run result (harness-resolution task 8): the parsed counts PLUS the raw output tail and
   * command, so callers (VBC refusals, the auto-verify observation) can show the model WHAT failed
   * instead of only how many. {@code outputTail} is bounded to {@value #DETAIL_TAIL_CHARS} chars.
   */
  public record DetailedResult(
      TestExecutionSummary summary, String outputTail, String command, int exitCode) {}

  static final int DETAIL_TAIL_CHARS = 3_000;

  /**
   * Resolve, validate, run, and parse the workspace test suite. Never throws — every failure mode
   * collapses to {@link Optional#empty()}.
   */
  public Optional<TestExecutionSummary> runTests(String runId, Path workspaceRoot) {
    return runTestsDetailed(runId, workspaceRoot).map(DetailedResult::summary);
  }

  /** Detailed sibling of {@link #runTests} — same fail-safe contract. */
  public Optional<DetailedResult> runTestsDetailed(String runId, Path workspaceRoot) {
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

    // RR-284 — JUnit XML on disk carries no attempt identity and the workspace outlives the
    // command. Reset the report directories before launching so a command that dies before writing
    // any report cannot have the previous attempt's green XML scored as its own. Only the
    // frameworks whose counts we recover from XML need this; keeping the blast radius narrow means
    // a pytest/jest/cargo/go run is byte-identical to before.
    boolean reportsReset = true;
    if (cmd.framework() == TestCommandResolver.Framework.SUREFIRE) {
      reportsReset = WorkspaceTestProvenance.clearReports(workspaceRoot);
      if (!reportsReset) {
        log.warn(
            "WorkspaceTestRunner run={} could not reset test report directories — XML recovery"
                + " will be treated as unproven",
            runId);
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
      boolean fromXmlRecovery = false;
      boolean xmlProvenanceProven = false;
      if (parsed.isEmpty() && cmd.framework() == TestCommandResolver.Framework.SUREFIRE) {
        // `mvn -q test` suppresses the stdout "Tests run:" summary; the surefire/gradle XML reports
        // are written regardless, so recover the real pass/fail from them. Without this, a run
        // whose
        // tests FAILED was scored UNKNOWN (no penalty, no failed-tests cap). RL-2026-06-049.
        //
        // RR-284 — only reports that provably belong to THIS attempt are admitted, and only when
        // the pre-launch reset succeeded. Otherwise the recovered counts describe some other run.
        fromXmlRecovery = true;
        WorkspaceTestProvenance.Admission admission =
            WorkspaceTestProvenance.admitFreshReports(workspaceRoot, start);
        if (admission.staleReportsPresent()) {
          log.warn(
              "WorkspaceTestRunner run={} discarded test reports that predate this attempt", runId);
        }
        parsed = TestOutputParser.parseJunitXmlReports(admission.reports());
        xmlProvenanceProven = reportsReset && !admission.staleReportsPresent();
      }

      // RR-283 — the process exit code is the framework's own verdict and outranks whatever the
      // summary line says. A suite can print "all passed" and still exit non-zero because of a
      // coverage threshold, a plugin/compilation error, a package-level failure or a teardown
      // error. Publishing that as a green deterministic count is the exact false-positive this
      // scorer exists to prevent, so an exit/summary contradiction is UNAVAILABLE, not green.
      if (parsed.isPresent()
          && !WorkspaceTestProvenance.countsExplainExitStatus(
              exit, parsed.get().getFailed(), parsed.get().getErrors())) {
        log.warn(
            "WorkspaceTestRunner run={} command exited {} while reporting zero failures —"
                + " discarding the count as unproven: {}",
            runId,
            exit,
            cmd.command());
        return Optional.empty();
      }

      final boolean executionProven = fromXmlRecovery ? xmlProvenanceProven : true;
      parsed.ifPresent(
          s -> {
            s.setExecutionDurationMs(Duration.between(start, Instant.now()).toMillis());
            // RR-1660 — the freshness/provenance token the evidence ledger reads. TRUE only when
            // this attempt's exit status was checked and every parsed report provably belongs to
            // it. Never FALSE-by-omission: an unproven recovery is recorded as such.
            s.setExecutionVerified(executionProven);
            s.setUnverifiedReason(
                executionProven
                    ? null
                    : "test report freshness could not be proven for this attempt");
          });
      log.info(
          "WorkspaceTestRunner run={} framework={} source={} → {}",
          runId,
          cmd.framework(),
          cmd.source(),
          parsed
              .map(s -> s.getPassed() + "/" + s.getTotalTests() + " passed")
              .orElse("unparseable (fallback to LLM coverage)"));
      final String tail =
          output.length() <= DETAIL_TAIL_CHARS
              ? output
              : "... [head omitted] ...\n" + output.substring(output.length() - DETAIL_TAIL_CHARS);
      return parsed.map(s -> new DetailedResult(s, tail, cmd.command(), exit));
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

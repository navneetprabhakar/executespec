package com.navneet.executespec.smoketest;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.navneet.executespec.programbench.ProgramBenchContainerStore;
import com.navneet.executespec.sandbox.SandboxExecResult;
import com.navneet.executespec.sandboxroute.SandboxCommandRoutingService;
import com.navneet.executespec.sandboxroute.VerificationSandboxAuthority;
import com.navneet.executespec.smoketest.TestCommandResolver.TestCommand;
import com.navneet.executespec.swebench.SwebenchContainerStore;
import com.navneet.executespec.testgen.BoundedProcess;
import com.navneet.executespec.testgen.model.TestExecutionSummary;
import com.navneet.executespec.tool.ExecutionContext;
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
   * RR-282 / RR-1674 — generated tests are executable repository content, so the plain BUILD-mode
   * host shell path above is the same host-RCE surface {@code buildProject} had. When {@link
   * SandboxCommandRoutingService#routingEnabled()} is on, the suite runs in the run's sandbox; when
   * the sandbox cannot run it, this returns {@link Optional#empty()} — no count, and no host retry.
   * Both sandbox flags are seeded false, so the default path is unchanged.
   */
  private final SandboxCommandRoutingService sandboxRouting;

  /**
   * Detailed run result (harness-resolution task 8): the parsed counts PLUS the raw output tail and
   * command, so callers (VBC refusals, the auto-verify observation) can show the model WHAT failed
   * instead of only how many. {@code outputTail} is bounded to {@value #DETAIL_TAIL_CHARS} chars.
   */
  public record DetailedResult(
      TestExecutionSummary summary, String outputTail, String command, int exitCode) {}

  public enum ExecutionStatus {
    PASSED,
    PRODUCT_FAILED,
    UNSUPPORTED_FRAMEWORK,
    POLICY_DENIED,
    TIMED_OUT,
    LAUNCH_FAILED,
    MALFORMED_EVIDENCE,
    TOOLCHAIN_UNAVAILABLE
  }

  /** Typed result that keeps environment failure separate from product failure. */
  public record ExecutionEvidence(
      ExecutionStatus status,
      DetailedResult result,
      String detail,
      String command,
      boolean executed) {

    public boolean productFailure() {
      return status == ExecutionStatus.PRODUCT_FAILED;
    }

    static ExecutionEvidence unavailable(
        ExecutionStatus status, String detail, String command, boolean executed) {
      return new ExecutionEvidence(status, null, detail, command, executed);
    }
  }

  static final int DETAIL_TAIL_CHARS = 3_000;

  /**
   * Resolve, validate, run, and parse the workspace test suite. Never throws — every failure mode
   * collapses to {@link Optional#empty()}.
   */
  public Optional<TestExecutionSummary> runTests(String runId, Path workspaceRoot) {
    return runTestsDetailed(runId, workspaceRoot).map(DetailedResult::summary);
  }

  /**
   * Whether a supported test framework is detected in the workspace, independent of whether it ran
   * or produced parseable counts. Lets a caller distinguish "no runner here" from "a runner exists
   * but produced no trustworthy count" — because {@link #runTests} collapses both to {@link
   * Optional#empty()}, and reporting the second as the first tells the model its tests don't exist
   * when in fact the suite failed to compile / crashed / hit a coverage gate / could not be run.
   */
  public boolean frameworkDetected(Path workspaceRoot) {
    return workspaceRoot != null && resolver.resolve(workspaceRoot).isPresent();
  }

  /** Detailed sibling of {@link #runTests} — same fail-safe contract. */
  public Optional<DetailedResult> runTestsDetailed(String runId, Path workspaceRoot) {
    return Optional.ofNullable(executeTests(runId, workspaceRoot, null).result());
  }

  /** Exact physical-verification sibling; a bound authority forbids host and RUN-sandbox paths. */
  public Optional<DetailedResult> runTestsDetailed(ExecutionContext context) {
    return Optional.ofNullable(executeTests(context).result());
  }

  public ExecutionEvidence executeTests(ExecutionContext context) {
    return executeTests(
        context.runId(), context.workspaceRoot(), context.verificationSandboxAuthority());
  }

  private ExecutionEvidence executeTests(
      String runId, Path workspaceRoot, VerificationSandboxAuthority verificationAuthority) {
    if (workspaceRoot == null) {
      return ExecutionEvidence.unavailable(
          ExecutionStatus.TOOLCHAIN_UNAVAILABLE, "workspace root is absent", null, false);
    }

    Optional<TestCommand> resolved = resolver.resolve(workspaceRoot);
    if (resolved.isEmpty()) {
      log.debug(
          "WorkspaceTestRunner run={} no recognized test runner — fallback to coverage", runId);
      return ExecutionEvidence.unavailable(
          ExecutionStatus.UNSUPPORTED_FRAMEWORK, "no recognized test framework", null, false);
    }
    TestCommand cmd = resolved.get();

    // Defense in depth: the command is resolver-derived (not worker text), but validate it anyway
    // so a future resolver change can never execute something the shell sandbox would block.
    ShellSandboxPolicy.ValidationResult v =
        sandboxPolicy.validate(commandForPolicyValidation(cmd.command()), workspaceRoot.toString());
    if (!v.permitted()) {
      log.warn(
          "WorkspaceTestRunner run={} test command blocked by sandbox: {} ({})",
          runId,
          cmd.command(),
          v.reason());
      return ExecutionEvidence.unavailable(
          ExecutionStatus.POLICY_DENIED, v.reason(), cmd.command(), false);
    }

    String containerId = null;
    String workdir = null;
    if (verificationAuthority == null && swebenchContainers != null) {
      String c = swebenchContainers.get(runId);
      if (c != null) {
        containerId = c;
        workdir = swebenchContainers.getWorkdir(runId);
      }
    }
    if (verificationAuthority == null && containerId == null && programBenchContainers != null) {
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
      String output;
      int exit;
      // RR-282 / RR-1674 — sandbox dispatch, ahead of any host ProcessBuilder. Bench cleanrooms
      // keep their own container boundary and are left exactly as they were.
      if (verificationAuthority != null) {
        if (sandboxRouting == null) {
          log.warn("WorkspaceTestRunner run={} verification routing unavailable", runId);
          return ExecutionEvidence.unavailable(
              ExecutionStatus.TOOLCHAIN_UNAVAILABLE,
              "verification sandbox routing unavailable",
              cmd.command(),
              false);
        }
        SandboxExecResult sandboxed =
            sandboxRouting.executeVerificationShell(
                verificationAuthority, cmd.command(), Duration.ofSeconds(cmd.timeoutSeconds()));
        if (!sandboxed.completed()) {
          log.warn(
              "WorkspaceTestRunner run={} verification sandbox status={} ({})",
              runId,
              sandboxed.status(),
              sandboxed.detail());
          ExecutionStatus status =
              switch (sandboxed.status()) {
                case TIMED_OUT -> ExecutionStatus.TIMED_OUT;
                case REFUSED -> ExecutionStatus.POLICY_DENIED;
                case UNAVAILABLE -> ExecutionStatus.TOOLCHAIN_UNAVAILABLE;
                case COMPLETED -> throw new IllegalStateException("completed handled above");
              };
          return ExecutionEvidence.unavailable(status, sandboxed.detail(), cmd.command(), false);
        }
        output = SandboxCommandRoutingService.combinedOutput(sandboxed);
        exit = sandboxed.exitCode();
      } else if (containerId == null && sandboxRouting != null && sandboxRouting.routingEnabled()) {
        SandboxExecResult sandboxed =
            sandboxRouting.executeShell(
                runId, workspaceRoot, cmd.command(), Duration.ofSeconds(cmd.timeoutSeconds()));
        if (!sandboxed.completed()) {
          // Fail-safe, never fail-open: an unavailable, refused or timed-out sandbox yields NO
          // test count. The caller falls back to LLM-judged coverage, which is the documented
          // behaviour for "cannot produce a trustworthy count" — it does NOT re-run on the host.
          log.warn(
              "WorkspaceTestRunner run={} sandbox status={} ({}) — no count; the suite was NOT run"
                  + " on the application host",
              runId,
              sandboxed.status(),
              sandboxed.detail());
          ExecutionStatus status =
              switch (sandboxed.status()) {
                case TIMED_OUT -> ExecutionStatus.TIMED_OUT;
                case REFUSED -> ExecutionStatus.POLICY_DENIED;
                case UNAVAILABLE -> ExecutionStatus.TOOLCHAIN_UNAVAILABLE;
                case COMPLETED -> throw new IllegalStateException("completed handled above");
              };
          return ExecutionEvidence.unavailable(status, sandboxed.detail(), cmd.command(), false);
        }
        output = SandboxCommandRoutingService.combinedOutput(sandboxed);
        exit = sandboxed.exitCode();
      } else {
        ProcessBuilder pb;
        if (containerId != null) {
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
                      cmd.command()));
        } else {
          pb = new ProcessBuilder(List.of("/bin/sh", "-c", cmd.command()));
          pb.directory(workspaceRoot.toFile());
        }
        hardenEnv(pb);
        pb.redirectErrorStream(true);

        BoundedProcess.Outcome outcome =
            BoundedProcess.run(pb, java.time.Duration.ofSeconds(cmd.timeoutSeconds()));
        if (!outcome.finished()) {
          log.warn(
              "WorkspaceTestRunner run={} test command exceeded {}s — fallback to coverage: {}",
              runId,
              cmd.timeoutSeconds(),
              cmd.command());
          return ExecutionEvidence.unavailable(
              ExecutionStatus.TIMED_OUT,
              "test command exceeded " + cmd.timeoutSeconds() + "s",
              cmd.command(),
              true);
        }
        output = outcome.output();
        exit = outcome.exitCode();
      }

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

      // A non-zero runner exit is deterministic red evidence even when the runner crashed before
      // printing a framework summary (missing dependency, collection error, segmentation fault,
      // coverage gate, etc.). Treat it as "at least one failed" instead of falling back to an LLM
      // coverage guess, which previously allowed a red workspace to end CLEAN/75.
      if (parsed.isEmpty() && exit != 0) {
        TestExecutionSummary failed = new TestExecutionSummary();
        failed.setTotalTests(1);
        failed.setPassed(0);
        failed.setFailed(1);
        failed.setErrors(0);
        failed.setSkipped(0);
        failed.setExecutionVerified(true);
        failed.setUnverifiedReason("runner exited non-zero before an exact count was available");
        parsed = Optional.of(failed);
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
        return ExecutionEvidence.unavailable(
            ExecutionStatus.MALFORMED_EVIDENCE,
            "runner exit status contradicts parsed counts",
            cmd.command(),
            true);
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
            // This runner always exercises the repository-local suite in the frozen workspace.
            // Freshness is independent from boundary: callers must not mistake this for a live
            // preview/functional journey, but they can persist it as deterministic code-level
            // evidence instead of leaving the scope null/legacy-unknown.
            s.setEvidenceScope(TestExecutionSummary.EvidenceScope.WORKSPACE_TEST_SUITE);
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
      if (parsed.isEmpty()) {
        return ExecutionEvidence.unavailable(
            ExecutionStatus.MALFORMED_EVIDENCE,
            "test output did not contain trustworthy counts",
            cmd.command(),
            true);
      }
      if (!executionProven) {
        return ExecutionEvidence.unavailable(
            ExecutionStatus.MALFORMED_EVIDENCE,
            "test report freshness could not be proven for this attempt",
            cmd.command(),
            true);
      }
      DetailedResult detail = new DetailedResult(parsed.get(), tail, cmd.command(), exit);
      boolean failed = exit != 0 || parsed.get().getFailed() > 0 || parsed.get().getErrors() > 0;
      return new ExecutionEvidence(
          failed ? ExecutionStatus.PRODUCT_FAILED : ExecutionStatus.PASSED,
          detail,
          null,
          cmd.command(),
          true);
    } catch (Exception e) {
      log.warn(
          "WorkspaceTestRunner run={} process error — fallback to coverage: {}",
          runId,
          e.getMessage());
      return ExecutionEvidence.unavailable(
          ExecutionStatus.LAUNCH_FAILED, e.getMessage(), cmd.command(), false);
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

  /**
   * The resolver may select a workspace virtual-environment interpreter so generated Python tests
   * see their installed dependencies. The generic shell policy correctly rejects arbitrary
   * path-qualified executables, so validate the fixed resolver-owned pytest shape as its canonical
   * interpreter instead. The executed command remains resolver-derived; no model text reaches this
   * path.
   */
  static String commandForPolicyValidation(String command) {
    if (command.startsWith(".venv/bin/python -m pytest ")
        || command.startsWith("venv/bin/python -m pytest ")) {
      return "python3" + command.substring(command.indexOf(" -m pytest "));
    }
    return command;
  }
}

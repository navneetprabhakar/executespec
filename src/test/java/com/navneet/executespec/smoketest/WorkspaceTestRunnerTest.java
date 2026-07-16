package com.navneet.executespec.smoketest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.navneet.executespec.smoketest.ExecutableVerification.FailureStage;
import com.navneet.executespec.smoketest.ExecutableVerification.Status;
import com.navneet.executespec.smoketest.TestCommandResolver.Framework;
import com.navneet.executespec.smoketest.TestCommandResolver.TestCommand;
import com.navneet.executespec.tool.ShellSandboxPolicy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** E2 executable-verification contract: every untrusted or absent outcome fails closed. */
class WorkspaceTestRunnerTest {

  @Test
  void noRecognizedRunner_isMissingEvidence_andLegacyViewRemainsEmpty(@TempDir Path workspace) {
    WorkspaceTestRunner runner =
        new WorkspaceTestRunner(new TestCommandResolver(), null, null, null);

    ExecutableVerification result = runner.verify("run-1", workspace);

    assertThat(result.status()).isEqualTo(Status.MISSING_EVIDENCE);
    assertThat(result.failureStage()).isEqualTo(FailureStage.EVIDENCE);
    assertThat(result.testsRun()).isFalse();
    assertThat(result.accepted()).isFalse();
    assertThat(runner.runTests("run-1", workspace)).isEmpty();
  }

  @Test
  void nullWorkspace_isMissingEvidence() {
    WorkspaceTestRunner runner =
        new WorkspaceTestRunner(new TestCommandResolver(), null, null, null);
    assertThat(runner.verify("run-1", null).status()).isEqualTo(Status.MISSING_EVIDENCE);
  }

  @Test
  void parsedPassingSuite_isAccepted(@TempDir Path workspace) {
    ExecutableVerification result =
        runner("printf '2 passed in 0.01s\\n'", 5, Framework.PYTEST).verify("run", workspace);

    assertThat(result.status()).isEqualTo(Status.PASSED);
    assertThat(result.testsRun()).isTrue();
    assertThat(result.testsPassed()).isTrue();
    assertThat(result.summary().getPassed()).isEqualTo(2);
    assertThat(result.accepted()).isTrue();
  }

  @Test
  void skippedTestsAreNeutralWhenAtLeastOneAssertionPassed(@TempDir Path workspace) {
    ExecutableVerification result =
        runner("printf '2 passed, 1 skipped in 0.01s\\n'", 5, Framework.PYTEST)
            .verify("run", workspace);

    assertThat(result.status()).isEqualTo(Status.PASSED);
    assertThat(result.summary().getSkipped()).isEqualTo(1);
  }

  @Test
  void allSkippedSuiteIsEmptyRatherThanPassing(@TempDir Path workspace) {
    ExecutableVerification result =
        runner("printf '3 skipped in 0.01s\\n'", 5, Framework.PYTEST).verify("run", workspace);

    assertThat(result.status()).isEqualTo(Status.EMPTY_TESTS);
    assertThat(result.accepted()).isFalse();
  }

  @Test
  void largeOutputIsDrainedWhileProcessRuns(@TempDir Path workspace) {
    String command =
        "i=0; while [ $i -lt 10000 ]; do printf 'padding-line\\n'; i=$((i+1)); done; "
            + "printf '2 passed in 0.01s\\n'";

    ExecutableVerification result =
        runner(command, 5, Framework.PYTEST).verify("run-large-output", workspace);

    assertThat(result.status()).isEqualTo(Status.PASSED);
    assertThat(result.outputTail())
        .hasSizeLessThanOrEqualTo(WorkspaceTestRunner.DETAIL_TAIL_CHARS + 30);
  }

  @Test
  void assertionFailure_isTypedAndRejected(@TempDir Path workspace) {
    ExecutableVerification result =
        runner("printf '1 passed, 1 failed in 0.01s\\n'; exit 1", 5, Framework.PYTEST)
            .verify("run", workspace);

    assertThat(result.status()).isEqualTo(Status.ASSERTION_FAILED);
    assertThat(result.failureStage()).isEqualTo(FailureStage.TEST_ASSERTION);
    assertThat(result.testsPassed()).isFalse();
    assertThat(result.accepted()).isFalse();
  }

  @Test
  void compileFailure_isTypedAndRejected(@TempDir Path workspace) {
    ExecutableVerification result =
        runner("printf 'COMPILATION ERROR: cannot find symbol\\n'; exit 1", 5, Framework.SUREFIRE)
            .verify("run", workspace);

    assertThat(result.status()).isEqualTo(Status.COMPILE_FAILED);
    assertThat(result.failureStage()).isEqualTo(FailureStage.COMPILE);
  }

  @Test
  void compileFailureOwnsVerdictEvenAfterPartialTestSummary(@TempDir Path workspace) {
    ExecutableVerification result =
        runner(
                "printf 'Tests run: 2, Failures: 0, Errors: 0, Skipped: 0\\n"
                    + "[ERROR] BUILD FAILURE\\n"
                    + "'; exit 1",
                5,
                Framework.SUREFIRE)
            .verify("run", workspace);

    assertThat(result.status()).isEqualTo(Status.COMPILE_FAILED);
  }

  @Test
  void commonGoAndRustCompileDiagnosticsAreClassified(@TempDir Path workspace) {
    ExecutableVerification go =
        runner("printf './main.go:3:2: undefined: Missing\\n'; exit 1", 5, Framework.GO)
            .verify("go", workspace);
    ExecutableVerification rust =
        runner(
                "printf 'error[E0425]: cannot find value x\\ncould not compile crate\\n'; exit 1",
                5,
                Framework.CARGO)
            .verify("rust", workspace);

    assertThat(go.status()).isEqualTo(Status.COMPILE_FAILED);
    assertThat(rust.status()).isEqualTo(Status.COMPILE_FAILED);
  }

  @Test
  void processStartFailureDoesNotClaimTestsRan(@TempDir Path workspace) {
    Path missing = workspace.resolve("does-not-exist");
    ExecutableVerification result =
        runner("printf '1 passed\\n'", 5, Framework.PYTEST).verify("run", missing);

    assertThat(result.status()).isEqualTo(Status.EXECUTION_ERROR);
    assertThat(result.testsRun()).isFalse();
  }

  @Test
  void containerWrapperRecordsPidAndQuotesCommand() {
    String pidFile = WorkspaceTestRunner.containerPidFile("run/id with spaces");
    String wrapped = WorkspaceTestRunner.wrapContainerCommand("printf '%s' ok", pidFile);

    assertThat(pidFile).isEqualTo("/tmp/executespec-verifier-run_id_with_spaces.pid");
    assertThat(wrapped)
        .contains("echo $$ >")
        .contains("trap \"rm -f")
        .contains("/bin/sh -c")
        .contains("'\"'\"'");
  }

  @Test
  void emptyTestDiscovery_isRejected(@TempDir Path workspace) {
    ExecutableVerification result =
        runner("printf 'no tests ran in 0.01s\\n'", 5, Framework.PYTEST).verify("run", workspace);

    assertThat(result.status()).isEqualTo(Status.EMPTY_TESTS);
    assertThat(result.failureStage()).isEqualTo(FailureStage.TEST_DISCOVERY);
    assertThat(result.testsPassed()).isFalse();
  }

  @Test
  void malformedOutput_isRejectedEvenWithZeroExit(@TempDir Path workspace) {
    ExecutableVerification result =
        runner("printf 'looks good but has no counts\\n'", 5, Framework.PYTEST)
            .verify("run", workspace);

    assertThat(result.status()).isEqualTo(Status.MALFORMED_OUTPUT);
    assertThat(result.failureStage()).isEqualTo(FailureStage.OUTPUT_PARSE);
  }

  @Test
  void missingToolchain_isTypedAndRejected(@TempDir Path workspace) {
    ExecutableVerification result =
        runner("definitely-not-a-real-executable", 5, Framework.PYTEST).verify("run", workspace);

    assertThat(result.status()).isEqualTo(Status.MISSING_TOOLCHAIN);
    assertThat(result.failureStage()).isEqualTo(FailureStage.TOOLCHAIN);
  }

  @Test
  void assertionMentioningMissingFile_isNotMisclassifiedAsMissingToolchain(
      @TempDir Path workspace) {
    ExecutableVerification result =
        runner(
                "printf 'FAILED test_file.py::test_read - FileNotFoundError: [Errno 2] No such file"
                    + " or directory\\n"
                    + "1 failed\\n"
                    + "'; exit 1",
                5,
                Framework.PYTEST)
            .verify("run", workspace);

    assertThat(result.status()).isEqualTo(Status.ASSERTION_FAILED);
    assertThat(result.failureStage()).isEqualTo(FailureStage.TEST_ASSERTION);
  }

  @Test
  void timeoutTerminatesChildProcessTree(@TempDir Path workspace) throws Exception {
    ExecutableVerification result =
        runner("sleep 30 & child=$!; echo $child > child.pid; wait $child", 1, Framework.PYTEST)
            .verify("run", workspace);

    assertThat(result.status()).isEqualTo(Status.TIMEOUT);
    assertThat(result.failureStage()).isEqualTo(FailureStage.TIMEOUT);
    long childPid = Long.parseLong(Files.readString(workspace.resolve("child.pid")).strip());
    Thread.sleep(100);
    assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
  }

  @Test
  void interruptionTerminatesChildProcessTree(@TempDir Path workspace) throws Exception {
    AtomicReference<ExecutableVerification> result = new AtomicReference<>();
    Thread verifier =
        Thread.ofPlatform()
            .start(
                () ->
                    result.set(
                        runner(
                                "sleep 30 & child=$!; echo $child > interrupted-child.pid; wait"
                                    + " $child",
                                60,
                                Framework.PYTEST)
                            .verify("run-interrupted", workspace)));
    Path childFile = workspace.resolve("interrupted-child.pid");
    for (int i = 0; i < 100 && !Files.exists(childFile); i++) Thread.sleep(10);
    assertThat(Files.exists(childFile)).isTrue();
    long childPid = Long.parseLong(Files.readString(childFile).strip());

    verifier.interrupt();
    verifier.join(5_000);

    assertThat(verifier.isAlive()).isFalse();
    assertThat(result.get().status()).isEqualTo(Status.INTERRUPTED);
    assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
  }

  @Test
  void blockedCommandNeverRuns(@TempDir Path workspace) {
    TestCommandResolver resolver = mock(TestCommandResolver.class);
    when(resolver.resolve(workspace))
        .thenReturn(
            Optional.of(new TestCommand("printf should-not-run", 5, Framework.PYTEST, "test")));
    ShellSandboxPolicy sandbox = mock(ShellSandboxPolicy.class);
    when(sandbox.validate(anyString(), anyString()))
        .thenReturn(new ShellSandboxPolicy.ValidationResult(false, "blocked"));

    ExecutableVerification result =
        new WorkspaceTestRunner(resolver, sandbox, null, null).verify("run", workspace);

    assertThat(result.status()).isEqualTo(Status.BLOCKED);
    assertThat(result.testsRun()).isFalse();
  }

  private static WorkspaceTestRunner runner(String command, int timeout, Framework framework) {
    TestCommandResolver resolver = mock(TestCommandResolver.class);
    when(resolver.resolve(org.mockito.ArgumentMatchers.any(Path.class)))
        .thenReturn(Optional.of(new TestCommand(command, timeout, framework, "test")));
    ShellSandboxPolicy sandbox = mock(ShellSandboxPolicy.class);
    when(sandbox.validate(anyString(), anyString()))
        .thenReturn(new ShellSandboxPolicy.ValidationResult(true, "ok"));
    return new WorkspaceTestRunner(resolver, sandbox, null, null);
  }
}

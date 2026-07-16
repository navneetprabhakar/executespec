package com.navneet.executespec.smoketest;

import com.navneet.executespec.testgen.model.TestExecutionSummary;

/**
 * Provider-independent executable verdict for a generated workspace.
 *
 * <p>Unlike the legacy optional test-count seam, every attempt has an explicit terminal status.
 * Only {@link Status#PASSED} is acceptance evidence; missing, empty, malformed, timed-out, or
 * failed execution is deliberately fail-closed.
 */
public record ExecutableVerification(
    Status status,
    FailureStage failureStage,
    Boolean testsRun,
    Boolean testsPassed,
    TestExecutionSummary summary,
    String framework,
    String source,
    String command,
    Integer exitCode,
    long durationMs,
    String outputTail,
    String detail) {

  public enum Status {
    PASSED,
    ASSERTION_FAILED,
    COMPILE_FAILED,
    TIMEOUT,
    EMPTY_TESTS,
    MALFORMED_OUTPUT,
    MISSING_TOOLCHAIN,
    MISSING_EVIDENCE,
    BLOCKED,
    EXECUTION_ERROR,
    INTERRUPTED
  }

  public enum FailureStage {
    NONE,
    TEST_ASSERTION,
    COMPILE,
    TIMEOUT,
    TEST_DISCOVERY,
    OUTPUT_PARSE,
    TOOLCHAIN,
    EVIDENCE,
    SANDBOX,
    EXECUTION,
    INTERRUPTED
  }

  public boolean accepted() {
    return status == Status.PASSED
        && Boolean.TRUE.equals(testsRun)
        && Boolean.TRUE.equals(testsPassed);
  }
}

package com.navneet.executespec.reviewer;

import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;
import com.navneet.executespec.smoketest.WorkspaceTestRunner;
import com.navneet.executespec.tool.ExecutionContext;

import lombok.extern.slf4j.Slf4j;

/** Fresh, exact-generation test evidence supplied before a read-only semantic attestation. */
@Slf4j
final class ReviewerVerificationEvidence {
  private ReviewerVerificationEvidence() {}

  static String capture(WorkspaceTestRunner runner, ExecutionContext context) {
    if (runner == null || context.verificationSandboxAuthority() == null)
      return "UNAVAILABLE: no attested test execution capability.";
    try {
      var authority = context.verificationSandboxAuthority();
      authority.assertLive();
      var trees = new CorrectionWorkspaceTransaction();
      String before = trees.captureGeneration(context.workspaceRoot());
      if (!before.equals(authority.generation())) return "UNAVAILABLE: review generation mismatch.";
      var evidence = runner.executeTests(context);
      authority.assertLive();
      if (!before.equals(trees.captureGeneration(context.workspaceRoot())))
        return "UNAVAILABLE: tests mutated the reviewed product generation.";
      return describe(evidence, before);
    } catch (RuntimeException failure) {
      log.warn(
          "Reviewer test evidence unavailable run={} failureType={}",
          context.runId(),
          failure.getClass().getSimpleName());
      return "UNAVAILABLE: server test execution could not be attested.";
    }
  }

  static String describe(WorkspaceTestRunner.ExecutionEvidence evidence, String generation) {
    if (evidence == null || !evidence.executed() || evidence.result() == null)
      return "UNAVAILABLE: server test execution produced no admissible result.";
    var result = evidence.result();
    var summary = result.summary();
    boolean passed =
        evidence.status() == WorkspaceTestRunner.ExecutionStatus.PASSED
            && result.exitCode() == 0
            && summary != null
            && summary.getPassed() > 0
            && summary.getTotalTests() > 0
            && summary.getFailed() == 0
            && summary.getErrors() == 0;
    if (!passed)
      return "NOT_PASSED: server test status="
          + evidence.status()
          + "; exit="
          + result.exitCode()
          + "; generation="
          + generation;
    return "Server independently executed the maintained workspace test suite on generation="
        + generation
        + "; exit=0; testsPassed="
        + summary.getPassed()
        + "; testsTotal="
        + summary.getTotalTests()
        + "; testsFailed=0; testsErrors=0. This is fresh server execution evidence, not a worker"
        + " journal claim. buildProject checks compilation; the test execution above is separate."
        + " Inspect the actual source and tests for contract defects even when tests pass. Do not"
        + " report tests as unexecuted solely because your read-only tool list omits runTests.";
  }
}

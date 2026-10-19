package com.navneet.executespec.reviewer;

import static org.assertj.core.api.Assertions.*;

import com.navneet.executespec.smoketest.WorkspaceTestRunner;
import com.navneet.executespec.testgen.model.TestExecutionSummary;

import org.junit.jupiter.api.Test;

class ReviewerVerificationEvidenceTest {
  private WorkspaceTestRunner.ExecutionEvidence evidence(
      int exit, int total, int passed, int failed) {
    var summary =
        TestExecutionSummary.builder().totalTests(total).passed(passed).failed(failed).build();
    return new WorkspaceTestRunner.ExecutionEvidence(
        WorkspaceTestRunner.ExecutionStatus.PASSED,
        new WorkspaceTestRunner.DetailedResult(summary, "output", "python3 -m pytest -q", exit),
        "",
        "python3 -m pytest -q",
        true);
  }

  @org.junit.jupiter.api.io.TempDir java.nio.file.Path root;

  @Test
  void exactCapabilityIsUsedAndMutatedProductEvidenceIsRejected() throws Exception {
    java.nio.file.Files.writeString(root.resolve("main.py"), "answer = 42\n");
    try (var git = org.eclipse.jgit.api.Git.init().setDirectory(root.toFile()).call()) {
      git.add().addFilepattern(".").call();
      git.commit().setMessage("fixture").setAuthor("Test", "test@example.invalid").call();
    }
    String generation =
        new com.navneet.executespec.integration.CorrectionWorkspaceTransaction()
            .captureGeneration(root);
    var authority =
        org.mockito.Mockito.mock(
            com.navneet.executespec.sandboxroute.VerificationSandboxAuthority.class);
    org.mockito.Mockito.when(authority.generation()).thenReturn(generation);
    org.mockito.Mockito.when(authority.runId()).thenReturn("run");
    org.mockito.Mockito.when(authority.workspaceRoot())
        .thenReturn(root.toAbsolutePath().normalize());
    var context =
        new com.navneet.executespec.tool.ExecutionContext("run", "review", root)
            .withVerificationSandboxAuthority(authority);
    var runner = org.mockito.Mockito.mock(WorkspaceTestRunner.class);
    org.mockito.Mockito.when(runner.executeTests(context)).thenReturn(evidence(0, 16, 16, 0));
    assertThat(ReviewerVerificationEvidence.capture(runner, context)).contains("testsPassed=16");
    org.mockito.Mockito.when(runner.executeTests(context))
        .thenAnswer(
            invocation -> {
              java.nio.file.Files.writeString(root.resolve("main.py"), "answer = 0\n");
              return evidence(0, 16, 16, 0);
            });
    assertThat(ReviewerVerificationEvidence.capture(runner, context))
        .startsWith("UNAVAILABLE: tests mutated");
  }

  @Test
  void freshPassingSuiteIsShownToReadOnlyAttestorsWithoutExposingMutationTools() {
    String proof = ReviewerVerificationEvidence.describe(evidence(0, 16, 16, 0), "a".repeat(64));
    assertThat(proof)
        .contains(
            "testsPassed=16",
            "testsTotal=16",
            "fresh server execution",
            "generation=" + "a".repeat(64));
    var request =
        ReviewerRequest.builder()
            .provider("openai")
            .model("gpt-6.1-sol")
            .context(
                new com.navneet.executespec.tool.ExecutionContext(
                    "run", "review", java.nio.file.Path.of(".").toAbsolutePath()))
            .readOnly(true)
            .serverVerificationEvidence(proof)
            .build();
    assertThat(ReviewerAgent.strictProtocolRecoveryRequest(request).serverVerificationEvidence())
        .isEqualTo(proof);
    assertThat(ReviewerAgent.composeReviewerUserPrompt(request, null))
        .contains("SERVER_VERIFICATION_EVIDENCE", "testsPassed=16", "READ-ONLY FINAL ATTESTATION");
    assertThat(ReviewerAgent.reviewerToolNames("reviewer-general", true))
        .doesNotContain("runTests", "shell", "writeFile", "editFile");
  }

  @Test
  void contradictoryExitEmptySuiteOrFailedTestsCannotBePresentedAsPassing() {
    for (var result :
        java.util.List.of(evidence(1, 16, 16, 0), evidence(0, 0, 0, 0), evidence(0, 16, 15, 1)))
      assertThat(ReviewerVerificationEvidence.describe(result, "a".repeat(64)))
          .startsWith("NOT_PASSED");
    assertThat(ReviewerVerificationEvidence.describe(null, "a".repeat(64)))
        .startsWith("UNAVAILABLE");
  }
}

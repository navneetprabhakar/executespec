package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;

import com.navneet.executespec.smoketest.WorkspaceTestRunner;
import com.navneet.executespec.testgen.model.TestExecutionSummary;

import org.junit.jupiter.api.Test;

class RunValidationKickoffCorrectionTest {

  private static final String GENERATION = "a".repeat(64);

  @Test
  void admitsGreenGenerationBoundWorkspaceSuiteAndPreservesCommandEvidence() {
    WorkspaceTestRunner.DetailedResult evidence =
        new WorkspaceTestRunner.DetailedResult(
            summary(8, 8, 0, 0, true), "Tests run: 8, Failures: 0, Errors: 0", "./mvnw test", 0);

    TestExecutionSummary admitted =
        RunValidationKickoff.admitCorrectionTestEvidence(
            GENERATION, GENERATION, GENERATION, evidence);
    var command = ValidationCommandEvidenceFactory.tests(evidence, Path.of("/workspace/run-1"));

    assertThat(admitted)
        .extracting(
            TestExecutionSummary::getPassed,
            TestExecutionSummary::getTotalTests,
            TestExecutionSummary::getExecutionVerified,
            TestExecutionSummary::getEvidenceScope)
        .containsExactly(8, 8, true, TestExecutionSummary.EvidenceScope.WORKSPACE_TEST_SUITE);
    assertThat(command.command()).isEqualTo("./mvnw test");
    assertThat(command.workingDirectory()).isEqualTo("/workspace/run-1");
    assertThat(command.exitCode()).isZero();
    assertThat(command.boundedOutput()).contains("Failures: 0");
  }

  @Test
  void rejectsMissingUnverifiedRedOrGenerationMismatchedProof() {
    assertThatThrownBy(
            () ->
                RunValidationKickoff.admitCorrectionTestEvidence(
                    GENERATION, GENERATION, GENERATION, null))
        .hasMessageContaining("CORRECTION_TESTS_UNAVAILABLE");

    var unverified =
        new WorkspaceTestRunner.DetailedResult(
            summary(8, 8, 0, 0, false), "unverified", "./mvnw test", 0);
    assertThatThrownBy(
            () ->
                RunValidationKickoff.admitCorrectionTestEvidence(
                    GENERATION, GENERATION, GENERATION, unverified))
        .hasMessageContaining("CORRECTION_TESTS_UNVERIFIED");

    var red =
        new WorkspaceTestRunner.DetailedResult(
            summary(8, 7, 1, 0, true), "one failure", "./mvnw test", 1);
    assertThatThrownBy(
            () ->
                RunValidationKickoff.admitCorrectionTestEvidence(
                    GENERATION, GENERATION, GENERATION, red))
        .hasMessageContaining("CORRECTION_TESTS_FAILED");

    var green =
        new WorkspaceTestRunner.DetailedResult(
            summary(8, 8, 0, 0, true), "green", "./mvnw test", 0);
    assertThatThrownBy(
            () ->
                RunValidationKickoff.admitCorrectionTestEvidence(
                    GENERATION, GENERATION, "b".repeat(64), green))
        .hasMessageContaining("CORRECTION_TEST_GENERATION_MISMATCH");
  }

  private static TestExecutionSummary summary(
      int total, int passed, int failed, int errors, boolean verified) {
    return TestExecutionSummary.builder()
        .totalTests(total)
        .passed(passed)
        .failed(failed)
        .errors(errors)
        .executionVerified(verified)
        .evidenceScope(TestExecutionSummary.EvidenceScope.WORKSPACE_TEST_SUITE)
        .build();
  }
}

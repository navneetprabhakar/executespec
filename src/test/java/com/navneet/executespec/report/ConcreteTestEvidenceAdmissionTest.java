package com.navneet.executespec.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.testgen.model.TestExecutionSummary;

import org.junit.jupiter.api.Test;

class ConcreteTestEvidenceAdmissionTest {
  private static final String GENERATION = "a".repeat(64);
  private static final Instant START = Instant.parse("2026-10-17T00:00:00Z");

  @Test
  void delayedOldGenerationCannotOverwriteCurrentReport() {
    var report = ValidationReport.builder().workspaceGeneration(GENERATION).build();
    var summary = evidence("b".repeat(64), START);
    assertThat(ConcreteTestEvidenceAdmission.mayReplace(report, summary)).isFalse();
    summary.setWorkspaceGeneration(GENERATION);
    assertThat(ConcreteTestEvidenceAdmission.mayReplace(report, summary)).isTrue();
  }

  @Test
  void launchOrderWinsOverDeliveryOrderAndDuplicatesDoNotSettleTwice() {
    var report =
        ValidationReport.builder()
            .workspaceGeneration(GENERATION)
            .concreteTestStartedAt(START)
            .concreteTestExecutionId("newer")
            .build();
    assertThat(
            ConcreteTestEvidenceAdmission.mayReplace(
                report, evidence(GENERATION, START.minusSeconds(1))))
        .isFalse();
    assertThat(ConcreteTestEvidenceAdmission.mayReplace(report, evidence(GENERATION, START)))
        .isFalse();
    assertThat(
            ConcreteTestEvidenceAdmission.mayReplace(
                report, evidence(GENERATION, START.plusSeconds(1))))
        .isTrue();
  }

  @Test
  void missingGenerationOrLaunchIdentityNeverCreatesProof() {
    var report = ValidationReport.builder().workspaceGeneration(GENERATION).build();
    assertThat(ConcreteTestEvidenceAdmission.mayReplace(report, TestExecutionSummary.empty()))
        .isFalse();
    assertThat(
            ConcreteTestEvidenceAdmission.mayReplace(
                new ValidationReport(), evidence(GENERATION, START)))
        .isFalse();
  }

  private static TestExecutionSummary evidence(String generation, Instant started) {
    return TestExecutionSummary.builder()
        .workspaceGeneration(generation)
        .executionId("execution")
        .executionStartedAt(started)
        .build();
  }
}

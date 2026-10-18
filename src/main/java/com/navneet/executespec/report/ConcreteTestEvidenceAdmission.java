package com.navneet.executespec.report;

import java.util.Objects;

import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.testgen.model.TestExecutionSummary;

/** Exact-generation and launch-order admission; invoked while holding the report row lock. */
final class ConcreteTestEvidenceAdmission {
  private ConcreteTestEvidenceAdmission() {}

  static boolean mayReplace(ValidationReport report, TestExecutionSummary summary) {
    // Historical, unbound reports remain readable but cannot acquire new execution proof.
    if (report.getWorkspaceGeneration() == null
        || !Objects.equals(report.getWorkspaceGeneration(), summary.getWorkspaceGeneration())
        || summary.getExecutionStartedAt() == null
        || summary.getExecutionId() == null
        || summary.getExecutionId().isBlank()) {
      return false;
    }
    if (report.getConcreteTestStartedAt() == null) return true;
    int order = summary.getExecutionStartedAt().compareTo(report.getConcreteTestStartedAt());
    return order > 0;
  }
}

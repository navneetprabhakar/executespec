package com.navneet.executespec.integration;

import java.util.List;
import java.util.Objects;

import com.navneet.executespec.reviewer.model.ReviewIssue;

/** Focused enterprise-enforcement adjustments to an otherwise resolved run terminal status. */
final class RunTerminalStatusPolicy {

  private RunTerminalStatusPolicy() {}

  static RunOrchestrator.StatusAndReason applyStandards(
      RunOrchestrator.StatusAndReason base, List<ReviewIssue> issues) {
    if (base == null || "FAILED".equals(base.status()) || issues == null) return base;
    boolean unavailable = ids(issues).anyMatch(id -> id.startsWith("SYNTH-STANDARD-UNAVAILABLE"));
    if (unavailable) {
      return new RunOrchestrator.StatusAndReason("PARTIAL", "STANDARDS_ENFORCEMENT_UNAVAILABLE");
    }
    boolean blocking = ids(issues).anyMatch(id -> id.startsWith("SYNTH-STANDARD-BLOCKING"));
    return blocking
        ? new RunOrchestrator.StatusAndReason("PARTIAL", "STANDARDS_BLOCKING_VIOLATION")
        : base;
  }

  static RunOrchestrator.StatusAndReason applyInvariants(
      RunOrchestrator.StatusAndReason base, List<ReviewIssue> issues) {
    if (base == null || "FAILED".equals(base.status()) || issues == null) return base;
    boolean unavailable = ids(issues).anyMatch(id -> id.startsWith("SYNTH-INVARIANT-UNAVAILABLE"));
    if (unavailable) {
      return new RunOrchestrator.StatusAndReason("PARTIAL", "INVARIANT_ENFORCEMENT_UNAVAILABLE");
    }
    boolean violated = ids(issues).anyMatch(id -> id.startsWith("SYNTH-INVARIANT-VIOLATION"));
    return violated
        ? new RunOrchestrator.StatusAndReason("PARTIAL", "ARCHITECTURE_INVARIANT_VIOLATION")
        : base;
  }

  private static java.util.stream.Stream<String> ids(List<ReviewIssue> issues) {
    return issues.stream().map(ReviewIssue::getId).filter(Objects::nonNull);
  }
}

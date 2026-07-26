package com.navneet.executespec.chat;

import java.util.Locale;

import com.navneet.executespec.report.entity.ValidationReport;

/** Deterministic last-mile guard for claims that the persisted report cannot support. */
final class ChatEvidenceTruthPolicy {

  private ChatEvidenceTruthPolicy() {}

  static String enforce(String reply, ValidationReport report) {
    if (reply == null || report == null || hasConcreteTestCount(report)) return reply;
    String normalized = reply.toLowerCase(Locale.ROOT).replace("`", "");
    if (normalized.contains("does not prove tests passed")
        || normalized.contains("does not prove that tests passed")
        || normalized.contains("doesn't prove tests passed")) return reply;
    boolean discussesBuildResult =
        normalized.contains("finalbuildpassed") || normalized.contains("build passed");
    boolean infersTestExecution =
        normalized.contains("pytest exited")
            || normalized.contains("pytest passed")
            || normalized.contains("tests exited")
            || normalized.contains("tests passed")
            || normalized.contains("test process exited")
            || normalized.contains("test suite passed");
    if (!discussesBuildResult || !infersTestExecution) return reply;
    return "`finalBuildPassed` proves only that the configured build command passed. This report "
        + "contains no concrete test count, so it does not prove that pytest or any test process "
        + "ran or passed; full behavioral correctness is not proven.";
  }

  private static boolean hasConcreteTestCount(ValidationReport report) {
    return report.getTestsTotal() != null && report.getTestsTotal() > 0;
  }
}

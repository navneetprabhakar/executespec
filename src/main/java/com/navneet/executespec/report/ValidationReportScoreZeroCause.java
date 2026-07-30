package com.navneet.executespec.report;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.deployment.preview.verify.BootHealthEvaluator.BootVerification;
import com.navneet.executespec.deployment.preview.verify.BootHealthEvaluator.Verdict;

/** Exact deterministic provenance for a newly generated zero validation headline. */
final class ValidationReportScoreZeroCause {

  static final int SCHEMA_VERSION = 1;

  private ValidationReportScoreZeroCause() {}

  static Evidence derive(
      int verifiedScore,
      boolean additiveFormula,
      int filesGenerated,
      Boolean buildPassed,
      int testsPassed,
      int testsTotal,
      Boolean testExecutionVerified,
      BootVerification bootVerification) {
    if (verifiedScore != 0) return null;
    if (filesGenerated == 0) {
      return new Evidence(
          SCHEMA_VERSION,
          additiveFormula ? "ADDITIVE_V2" : "MULTIPLICATIVE_LEGACY",
          List.of(
              new Cause(
                  "CODE_DELIVERED",
                  "validation_report.files_generated",
                  "No approved product-code diff was captured.")));
    }
    List<Cause> causes = new ArrayList<>();
    if (!additiveFormula) {
      if (bootVerification != null && bootVerification.verdict() == Verdict.BUILD_FAILED) {
        causes.add(
            new Cause(
                "BOOT_BUILD",
                "validation_report.boot_verdict",
                "The deterministic boot verifier reported BUILD_FAILED."));
      } else if (bootVerification == null && Boolean.FALSE.equals(buildPassed)) {
        causes.add(
            new Cause(
                "BUILD",
                "validation_report.final_build_passed",
                "The explicit final build result failed."));
      }
      if (Boolean.TRUE.equals(testExecutionVerified) && testsTotal > 0 && testsPassed <= 0) {
        causes.add(
            new Cause(
                "TESTS",
                "validation_report.tests_passed/tests_total/test_execution_verified",
                "The concrete test execution passed 0/" + testsTotal + "."));
      }
    }
    return causes.isEmpty()
        ? null
        : new Evidence(
            SCHEMA_VERSION,
            additiveFormula ? "ADDITIVE_V2" : "MULTIPLICATIVE_LEGACY",
            List.copyOf(causes));
  }

  static String toJson(ObjectMapper objectMapper, Evidence evidence) {
    if (evidence == null) return null;
    try {
      return objectMapper.writeValueAsString(evidence);
    } catch (JsonProcessingException impossible) {
      throw new IllegalStateException("Score-zero cause could not be serialized", impossible);
    }
  }

  record Evidence(int schemaVersion, String formula, List<Cause> causes) {
    Evidence {
      causes = causes == null ? List.of() : List.copyOf(causes);
    }
  }

  record Cause(String gate, String artifact, String reason) {}
}

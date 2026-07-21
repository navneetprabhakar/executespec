package com.navneet.executespec.reviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.navneet.executespec.reviewer.model.AccuracyReport;

import org.junit.jupiter.api.Test;

/**
 * RR-292 — execution-time / VCS requirements (commit / push / open PR / verify-by-running /
 * exit-code-0 / run-lint) used to be DELETED from the assessment because the post-run workspace
 * snapshot cannot show whether they happened. But deletion consults no VCS / command / build / test
 * / publication evidence, so a user who explicitly asked for a commit / push / PR / successful
 * verification would silently lose the requirement — removing its critical penalty and potentially
 * upgrading the score. They are now RETAINED as UNKNOWN with a marker, never dropped.
 */
class AccuracyAssessmentExecutionTimeFilterTest {

  private final AccuracyAssessmentService svc =
      new AccuracyAssessmentService(null, null, null, null, null);

  @Test
  void execution_time_requirements_are_retained_as_unknown_not_deleted() {
    AccuracyReport report = new AccuracyReport();
    List<String> missing = new ArrayList<>();
    missing.add("Commit the change with message \"fix bug\"");
    missing.add("Push to the feature branch");
    missing.add("Implement the POST /api/users endpoint"); // a genuine, non-execution requirement
    report.setMissingRequirements(missing);
    report.setUnknownRequirements(new ArrayList<>());

    svc.filterExecutionTimeRequirements("run-1", report);

    // The genuine functional requirement stays MISSING.
    assertEquals(1, report.getMissingRequirements().size());
    assertTrue(report.getMissingRequirements().get(0).contains("POST /api/users"));

    // The two execution-time requirements are retained as UNKNOWN — not silently deleted.
    assertEquals(2, report.getUnknownRequirements().size());
    assertTrue(
        report.getUnknownRequirements().stream().allMatch(r -> r.contains("execution-time/VCS")),
        "retained execution-time requirements must carry the not-verifiable marker");
    assertTrue(
        report.getUnknownRequirements().stream().anyMatch(r -> r.contains("Commit the change")));
    assertTrue(
        report.getUnknownRequirements().stream().anyMatch(r -> r.contains("Push to the feature")));
  }

  @Test
  void no_execution_time_requirements_leaves_lists_untouched() {
    AccuracyReport report = new AccuracyReport();
    report.setMissingRequirements(new ArrayList<>(List.of("Implement rate limiting")));
    report.setUnknownRequirements(new ArrayList<>());

    svc.filterExecutionTimeRequirements("run-2", report);

    assertEquals(1, report.getMissingRequirements().size());
    assertEquals(0, report.getUnknownRequirements().size());
  }
}

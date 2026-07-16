package com.navneet.executespec.scoring;

import java.util.Optional;

import com.navneet.executespec.report.ValidationReportService;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.testgen.event.TestExecutionCompletedEvent;
import com.navneet.executespec.testgen.model.TestExecutionSummary;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Pilot R6 follow-up — the validation report's {@code testCoverageScore} is initially computed from
 * {@link com.navneet.executespec.reviewer.model.AccuracyReport}, which runs BEFORE test generation
 * has a chance to land. That path always reports {@code NONE}, pegging every run's
 * testCoverageScore at 20.
 *
 * <p>This listener re-derives the score from the actual test execution summary once it arrives and
 * updates the persisted report in place. If no report exists yet (rare race), the update is
 * skipped; the next regeneration will pick up the new data.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TestCoverageRescorer {

  private final ValidationReportService validationReportService;

  @EventListener
  @Transactional
  public void onTestExecutionCompleted(TestExecutionCompletedEvent event) {
    TestExecutionSummary s = event.summary();
    if (s == null) return;

    Optional<ValidationReport> rescored =
        validationReportService.rescoreAfterConcreteTests(event.runId(), s);
    if (rescored.isEmpty()) {
      log.debug("TestCoverageRescorer: no report for run {} yet — skipping", event.runId());
      return;
    }

    ValidationReport report = rescored.get();
    log.info(
        "TestCoverageRescorer: run {} rescored verifiedScore={} rubricScore={} (total={}"
            + " passed={})",
        event.runId(),
        report.getVerifiedScore(),
        report.getRubricScore(),
        s.getTotalTests(),
        s.getPassed());
  }

  static int deriveScore(TestExecutionSummary s) {
    int total = s.getTotalTests();
    if (total <= 0) return 20;
    double ratio = (double) s.getPassed() / total;
    if (ratio >= 0.90) return 95;
    if (ratio >= 0.60) return 75;
    if (ratio >= 0.30) return 55;
    return 35;
  }
}

package com.navneet.executespec.testgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.testgen.model.TestExecutionSummary;
import com.navneet.executespec.testgen.model.TestPlan;
import com.navneet.executespec.testgen.model.TestPlanStatus;
import com.navneet.executespec.testgen.repository.TestPlanRepository;
import com.navneet.executespec.testgen.repository.TestResultRepository;

import org.junit.jupiter.api.Test;

/**
 * RR-1583 — the V277 fingerprint is now read, not just written: a plan whose workspace moved (or
 * whose binding cannot be verified) publishes an unverified summary instead of pass counts, and
 * never reaches the runner.
 */
class TestExecutionBindingTest {

  private final TestPlanRepository plans = mock(TestPlanRepository.class);
  private final TestResultRepository results = mock(TestResultRepository.class);
  private final SurefireReportParser parser = mock(SurefireReportParser.class);
  private final TestGenerationAdmissionService admission =
      mock(TestGenerationAdmissionService.class);

  private TestExecutionService service() {
    when(plans.save(any(TestPlan.class))).thenAnswer(inv -> inv.getArgument(0));
    return new TestExecutionService(plans, results, parser, mock(SseBroadcaster.class), admission);
  }

  private TestPlan plan(String workspaceFingerprint) {
    TestPlan plan =
        TestPlan.builder()
            .id(3L)
            .runId("run-bind")
            .workspaceFingerprint(workspaceFingerprint)
            .build();
    when(plans.findById(3L)).thenReturn(Optional.of(plan));
    return plan;
  }

  @Test
  void workspaceDriftMarksThePlanStaleAndPublishesNoCounts() {
    TestPlan plan = plan("planned-fp");
    when(admission.currentWorkspaceFingerprint("run-bind")).thenReturn("drifted-fp");

    TestExecutionSummary summary = service().executeTests("run-bind", 3L, "http://preview/");

    assertFalse(summary.isExecutionProven());
    assertEquals(0, summary.getTotalTests());
    assertEquals(0, summary.getPassed());
    assertNotNull(summary.getUnverifiedReason());
    assertEquals(TestPlanStatus.STALE, plan.getStatus());
    // The runner was never reached, so no report can be parsed or persisted.
    verify(parser, never()).admit(any(), any());
    verify(results, never()).saveAll(any());
  }

  @Test
  void aPlanWithNoFingerprintIsTreatedAsUnboundNotMatching() {
    TestPlan plan = plan(null);
    when(admission.currentWorkspaceFingerprint("run-bind")).thenReturn("some-fp");

    TestExecutionSummary summary = service().executeTests("run-bind", 3L, "http://preview/");

    assertFalse(summary.isExecutionProven());
    assertEquals(TestPlanStatus.STALE, plan.getStatus());
    verify(parser, never()).admit(any(), any());
  }

  @Test
  void anUncomputableCurrentFingerprintBlocksExecution() {
    TestPlan plan = plan("planned-fp");
    when(admission.currentWorkspaceFingerprint("run-bind")).thenReturn(null);

    TestExecutionSummary summary = service().executeTests("run-bind", 3L, "http://preview/");

    assertFalse(summary.isExecutionProven());
    assertEquals(TestPlanStatus.STALE, plan.getStatus());
    verify(parser, never()).admit(any(), any());
  }

  @Test
  void emptySummaryCarriesNoProvenanceClaim() {
    assertFalse(TestExecutionSummary.empty().isExecutionProven());
    assertEquals(null, TestExecutionSummary.empty().getExecutionVerified());
  }

  @Test
  void unavailableSummaryIsExplicitlyUnproven() {
    TestExecutionSummary summary = TestExecutionSummary.unavailable("exec-1", "runner exited 137");
    assertEquals(Boolean.FALSE, summary.getExecutionVerified());
    assertEquals("runner exited 137", summary.getUnverifiedReason());
    assertEquals(0, summary.getTotalTests());
  }
}

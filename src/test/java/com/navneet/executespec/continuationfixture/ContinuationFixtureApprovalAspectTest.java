package com.navneet.executespec.continuationfixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navneet.executespec.execution.model.RunContinuationDecision;
import com.navneet.executespec.integration.RunLifecycleService;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;

class ContinuationFixtureApprovalAspectTest {

  @Test
  void fixtureUsesProviderFreeApprovalAndOrdinaryRunProceedsUnchanged() throws Throwable {
    ContinuationFixtureService fixtures = mock(ContinuationFixtureService.class);
    ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
    var approval =
        new RunLifecycleService.ContinuationApproval(
            "anthropic", "claude-sonnet-5", RunContinuationDecision.STEP_UP_MODEL, false, false);
    when(fixtures.isFixtureRun("fixture-run")).thenReturn(true);
    when(fixtures.approveProviderFreeFixture("fixture-run", "fixture")).thenReturn(approval);
    when(fixtures.isFixtureRun("ordinary-run")).thenReturn(false);
    when(joinPoint.proceed()).thenReturn("ordinary");
    var aspect = new ContinuationFixtureApprovalAspect(fixtures);

    assertThat(aspect.approveOrProceed(joinPoint, "fixture-run", "fixture")).isSameAs(approval);
    verify(joinPoint, never()).proceed();
    assertThat(aspect.approveOrProceed(joinPoint, "ordinary-run", "task")).isEqualTo("ordinary");
    verify(joinPoint).proceed();
  }
}

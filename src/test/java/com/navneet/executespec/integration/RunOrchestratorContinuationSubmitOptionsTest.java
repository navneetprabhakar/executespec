package com.navneet.executespec.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.navneet.executespec.run.RunContinuationBenchmarkPriorFlag;
import com.navneet.executespec.run.RunContinuationConfirmationFlag;

import org.junit.jupiter.api.Test;

class RunOrchestratorContinuationSubmitOptionsTest {

  private final RunContinuationConfirmationFlag confirmationFlag =
      mock(RunContinuationConfirmationFlag.class);
  private final RunContinuationBenchmarkPriorFlag benchmarkPriorFlag =
      mock(RunContinuationBenchmarkPriorFlag.class);

  @Test
  void applyContinuationSubmitOptions_marksExplicitContinuationPolicyBeforeAsyncPipeline() {
    RunOrchestrator.applyContinuationSubmitOptions(
        "run-1", true, false, confirmationFlag, benchmarkPriorFlag);

    verify(confirmationFlag).mark("run-1");
    verify(benchmarkPriorFlag).mark("run-1", false);
  }

  @Test
  void applyContinuationSubmitOptions_leavesServerDefaultsWhenUnset() {
    RunOrchestrator.applyContinuationSubmitOptions(
        "run-2", false, null, confirmationFlag, benchmarkPriorFlag);

    verify(confirmationFlag, never()).mark("run-2");
    verify(benchmarkPriorFlag, never()).mark("run-2", true);
    verify(benchmarkPriorFlag, never()).mark("run-2", false);
  }

  @Test
  void normalizeLowComplexityWorkerStartTier_allowsOnlySupportedRunValues() {
    assertEquals(
        "LIGHTWEIGHT", RunOrchestrator.normalizeLowComplexityWorkerStartTier("lightweight"));
    assertEquals("WORKHORSE", RunOrchestrator.normalizeLowComplexityWorkerStartTier(" WORKHORSE "));
    assertNull(RunOrchestrator.normalizeLowComplexityWorkerStartTier(null));
    assertNull(RunOrchestrator.normalizeLowComplexityWorkerStartTier("cheapest"));
  }
}

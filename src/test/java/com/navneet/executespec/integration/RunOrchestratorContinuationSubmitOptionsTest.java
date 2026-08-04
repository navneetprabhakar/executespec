package com.navneet.executespec.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.file.Files;
import java.nio.file.Path;

import com.navneet.executespec.run.RunContinuationBenchmarkPriorFlag;
import com.navneet.executespec.run.RunContinuationConfirmationFlag;
import com.navneet.executespec.state.entity.PipelineRun;

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

  @Test
  void branchOverride_isCanonicalAndInvalidGitRefsFailBeforeDispatch() {
    assertEquals("feature/pr-12", RunOrchestrator.normalizeBranchOverride("  feature/pr-12  "));
    assertNull(RunOrchestrator.normalizeBranchOverride("  "));
    assertThrows(
        IllegalArgumentException.class,
        () -> RunOrchestrator.normalizeBranchOverride("feature/../main"));
  }

  @Test
  void durableResumeRequestsRetainTheExactPublicationBranch() {
    PipelineRun run =
        PipelineRun.builder()
            .runId("run-branch")
            .userRequest("fix CI")
            .aiProvider("FIREWORKS")
            .workerModel("glm-5.2-fw")
            .approvedExecutionMode("PRODUCTION")
            .branchOverride("feature/pr-12")
            .checkoutBranch("feature/pr-12")
            .build();

    assertEquals(
        "feature/pr-12", RunOrchestrator.RunPipelineRequest.fromPausedRow(run).checkoutBranch());
    assertEquals(
        "feature/pr-12",
        RunOrchestrator.RunPipelineRequest.fromApprovedPlanRow(run, null).checkoutBranch());
  }

  @Test
  void checkoutOnlyExecutionBranchSurvivesResumeWithoutPublicationAuthority() {
    PipelineRun run =
        PipelineRun.builder()
            .runId("run-checkout")
            .userRequest("build from staged source")
            .aiProvider("FIREWORKS")
            .workerModel("glm-5.2-fw")
            .approvedExecutionMode("PRODUCTION")
            .checkoutBranch("feature/source")
            .build();

    assertEquals(
        "feature/source", RunOrchestrator.RunPipelineRequest.fromPausedRow(run).checkoutBranch());
    assertEquals(
        "feature/source",
        RunOrchestrator.RunPipelineRequest.fromApprovedPlanRow(run, null).checkoutBranch());
    assertNull(run.getBranchOverride());
  }

  @Test
  void cloneBranchResolutionPrefersDurableCheckoutThenPublicationThenLegacyInput() {
    PipelineRun distinct =
        PipelineRun.builder()
            .checkoutBranch("feature/source")
            .branchOverride("feature/pr-16")
            .build();
    PipelineRun publicationOnly = PipelineRun.builder().branchOverride("feature/pr-16").build();

    assertEquals("feature/source", RunOrchestrator.resolveCheckoutBranch(distinct, "legacy"));
    assertEquals("feature/pr-16", RunOrchestrator.resolveCheckoutBranch(publicationOnly, "legacy"));
    assertEquals("legacy", RunOrchestrator.resolveCheckoutBranch(null, "legacy"));
  }

  @Test
  void canonicalBranchIsStampedOnInitialInsertBeforeAsyncDispatch() throws Exception {
    String source =
        Files.readString(
            Path.of("src/main/java/com/navneet/executespec/integration/RunOrchestrator.java"));
    int resolve = source.indexOf("RunBranchPublicationIntent.resolve(branchOverride, policy)");
    int normalize = source.indexOf("final String branchOverrideFinal", resolve);
    int stamp = source.indexOf(".branchOverride(branchOverrideFinal)", normalize);
    int checkoutStamp = source.indexOf(".checkoutBranch(checkoutBranchFinal)", stamp);
    int insert = source.indexOf("ReservedPipelineRunStore.persistInitial(", checkoutStamp);
    int dispatch = source.indexOf("pool.executor().execute(future)", insert);

    assertEquals(true, resolve > 0);
    assertEquals(true, normalize > resolve);
    assertEquals(true, stamp > normalize);
    assertEquals(true, checkoutStamp > stamp);
    assertEquals(true, insert > checkoutStamp);
    assertEquals(true, dispatch > insert);
  }
}

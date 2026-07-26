package com.navneet.executespec.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.Test;

class RunChatEvidenceTest {

  @Test
  void plannerFailureIncludesSpecificFailureRouteTokensAndArtifactState() {
    PipelineRun run =
        PipelineRun.builder()
            .runId("run-coherence-rejected")
            .status("FAILED")
            .statusReason("PLANNER_UNAVAILABLE")
            .runMode("BUILD")
            .failureReason("planning could not pass deterministic coherence validation")
            .aiProvider("DEEPSEEK")
            .requestedProvider("GEMINI")
            .plannerProvider("DEEPSEEK")
            .plannerActualModel("deepseek-v4-flash-native")
            .plannerInputTokens(158_604)
            .plannerOutputTokens(25_271)
            .totalInputTokens(158_604)
            .totalOutputTokens(25_271)
            .outlineInputTokens(42_000L)
            .outlineOutputTokens(7_000L)
            .coherenceInputTokens(116_604L)
            .coherenceOutputTokens(18_271L)
            .planOutlineJson("{\"tasks\":[{\"id\":\"T1\"}]}")
            .planJson(null)
            .actualCostUsd(0.19)
            .build();

    String evidence = RunChatEvidence.render(run);

    assertThat(evidence)
        .contains("- statusReason: PLANNER_UNAVAILABLE")
        .contains("- runMode: BUILD")
        .contains("- failureReason: planning could not pass deterministic coherence validation")
        .contains("- requestedProvider: GEMINI")
        .contains("- plannerEffectiveProvider: DEEPSEEK")
        .contains("- plannerEffectiveModel: deepseek-v4-flash-native")
        .contains("- plannerTokens: input=158604, output=25271")
        .contains("- providerResponseObserved: true")
        .contains("- plannerTokenAttributionIncomplete: false")
        .contains("outline=input=42000/output=7000")
        .contains("coherence=input=116604/output=18271")
        .contains(
            "planArtifacts: outlinePersisted=true, finalPlanPersisted=false,"
                + " requirementsPersisted=false")
        .contains("- workerCodeGenerationState: NOT_STARTED")
        .contains("- phaseDefinition: expansion is planning, not worker code generation");
  }

  @Test
  void absentEvidenceIsExplicitAndFailureReasonIsBounded() {
    PipelineRun empty =
        PipelineRun.builder()
            .runId("legacy")
            .status("FAILED")
            .failureReason("x".repeat(2_500))
            .build();

    String evidence = RunChatEvidence.render(empty);

    assertThat(evidence)
        .contains("- statusReason: (not recorded)")
        .contains("- plannerEffectiveProvider: (not recorded)")
        .contains("- plannerTokens: input=0, output=0")
        .contains("- providerResponseObserved: false")
        .contains("- plannerTokenAttributionIncomplete: false")
        .contains("… [truncated]");
    assertThat(evidence).doesNotContain("x".repeat(2_001));
  }

  @Test
  void zeroLegacyPlannerAggregateDoesNotErasePhaseAndArtifactEvidence() {
    PipelineRun run =
        PipelineRun.builder()
            .runId("run-legacy-attribution")
            .status("FAILED")
            .statusReason("PLANNER_UNAVAILABLE")
            .plannerInputTokens(0)
            .plannerOutputTokens(0)
            .totalInputTokens(158_604)
            .totalOutputTokens(25_271)
            .outlineInputTokens(5_457L)
            .outlineOutputTokens(3_699L)
            .planOutlineJson("{\"tasks\":[]}")
            .build();

    assertThat(RunChatEvidence.render(run))
        .contains("- providerResponseObserved: true")
        .contains("- plannerTokenAttributionIncomplete: true")
        .contains("outline=input=5457/output=3699")
        .contains("outlinePersisted=true");
  }

  @Test
  void finalPlanOrNonPlannerFailureDoesNotInventWorkerExecutionState() {
    PipelineRun acceptedPlan =
        PipelineRun.builder()
            .runId("run-worker-unknown")
            .runMode("BUILD")
            .status("FAILED")
            .statusReason("FINAL_BUILD_FAILED")
            .planJson("{\"tasks\":[{\"id\":\"T1\"}]}")
            .build();
    PipelineRun patch =
        PipelineRun.builder()
            .runId("run-patch")
            .runMode("PATCH")
            .status("FAILED")
            .statusReason("PLANNER_UNAVAILABLE")
            .build();

    assertThat(RunChatEvidence.render(acceptedPlan))
        .contains("- workerCodeGenerationState: UNKNOWN");
    assertThat(RunChatEvidence.render(patch)).contains("- workerCodeGenerationState: UNKNOWN");
  }

  @Test
  void projectedPlanValidationReasonProvesWorkerDidNotStart() {
    PipelineRun rejected =
        PipelineRun.builder()
            .runMode("BUILD")
            .status("FAILED")
            .statusReason("PLAN_VALIDATION_REJECTED")
            .failureReason("Plan validation rejected: missing producer")
            .planOutlineJson("{\"tasks\":[]}")
            .planJson(null)
            .build();

    assertThat(RunChatEvidence.render(rejected))
        .contains("- workerCodeGenerationState: NOT_STARTED");
  }
}

package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class ExplicitTaskCountContractTest {
  @Test
  void singleShotCannotEraseAnExplicitThreeTaskPlan() {
    var result =
        new ClassificationResult(
            PlanStrategy.SINGLE_SHOT,
            1,
            1,
            false,
            "small library",
            ClassificationResult.Source.LLM_VERIFIED,
            12,
            20,
            .001);
    var normalized =
        ExplicitTaskCountContract.normalizeClassification(
            result,
            "Build a Python library with three sequential plan tasks: bootstrap, behavior,"
                + " README.");
    assertThat(normalized.strategy()).isEqualTo(PlanStrategy.SMALL);
    assertThat(normalized.estimatedTaskCount()).isEqualTo(3);
    assertThat(normalized.costUsd()).isEqualTo(.001);
  }

  @Test
  void unrelatedNumbersAndOrdinaryTasksDoNotConstrainPlanning() {
    assertThat(
            ExplicitTaskCountContract.requestedCount(
                "Handle three tasks in the task manager with 2 endpoints"))
        .isZero();
    assertThat(ExplicitTaskCountContract.requestedCount("Use 12 plan tasks")).isEqualTo(12);
  }

  @Test
  void finalPlanRejectsScopeCollapse() {
    var plan =
        Plan.builder()
            .tasks(
                List.of(
                    PlannedTask.builder()
                        .id("T1")
                        .title("Library")
                        .description("Implement library")
                        .build()))
            .build();
    assertThatThrownBy(
            () ->
                TaskExecutionBriefCompiler.compileExecutable(
                    plan, "Use three sequential plan tasks"))
        .isInstanceOf(TaskExecutionBriefCompiler.ExecutablePlanContractException.class)
        .hasMessageContaining("plan task count=3");
  }
}

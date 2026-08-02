package com.navneet.executespec.api;

import java.util.ArrayList;
import java.util.List;

import com.navneet.executespec.api.dto.RunDetail;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.testgen.repository.TestPlanRepository;

/** Projects persisted per-agent token usage into the run-detail read model. */
final class RunAgentTokenProjection {

  private RunAgentTokenProjection() {}

  static List<RunDetail.AgentTokenEntry> project(
      PipelineRun run, TestPlanRepository testPlanRepository) {
    List<RunDetail.AgentTokenEntry> entries = new ArrayList<>();
    int plannerInput = value(run.getPlannerInputTokens());
    int plannerOutput = value(run.getPlannerOutputTokens());
    if (plannerInput > 0 || plannerOutput > 0) {
      entries.add(
          new RunDetail.AgentTokenEntry(
              "planner",
              run.getPlannerProvider(),
              run.getPlannerActualModel(),
              plannerInput,
              plannerOutput));
    }

    int reviewerInput = value(run.getReviewerInputTokens());
    int reviewerOutput = value(run.getReviewerOutputTokens());
    if (reviewerInput > 0 || reviewerOutput > 0) {
      entries.add(
          new RunDetail.AgentTokenEntry(
              "reviewer",
              run.getReviewerProvider(),
              run.getReviewerActualModel(),
              reviewerInput,
              reviewerOutput));
    }

    int compilerInput = value(run.getCompilerInputTokens());
    int compilerOutput = value(run.getCompilerOutputTokens());
    if (compilerInput > 0 || compilerOutput > 0) {
      entries.add(
          new RunDetail.AgentTokenEntry(
              "compiler",
              run.getCompilerProvider(),
              run.getCompilerActualModel(),
              compilerInput,
              compilerOutput));
    }

    testPlanRepository
        .findByRunId(run.getRunId())
        .ifPresent(
            testPlan -> {
              int plannerTestInput = value(testPlan.getPlannerInputTokens());
              int plannerTestOutput = value(testPlan.getPlannerOutputTokens());
              if (plannerTestInput > 0 || plannerTestOutput > 0) {
                entries.add(
                    new RunDetail.AgentTokenEntry(
                        "test-planner",
                        testPlan.getPlannerProvider(),
                        testPlan.getPlannerModel(),
                        plannerTestInput,
                        plannerTestOutput));
              }

              int workerTestInput = value(testPlan.getWorkerInputTokens());
              int workerTestOutput = value(testPlan.getWorkerOutputTokens());
              if (workerTestInput > 0 || workerTestOutput > 0) {
                entries.add(
                    new RunDetail.AgentTokenEntry(
                        "test-worker",
                        testPlan.getWorkerProvider(),
                        testPlan.getWorkerModel(),
                        workerTestInput,
                        workerTestOutput));
              }

              int reportInput = value(testPlan.getReportInputTokens());
              int reportOutput = value(testPlan.getReportOutputTokens());
              if (reportInput > 0 || reportOutput > 0) {
                entries.add(
                    new RunDetail.AgentTokenEntry(
                        "test-report-gen",
                        testPlan.getPlannerProvider(),
                        testPlan.getPlannerModel(),
                        reportInput,
                        reportOutput));
              }
            });
    return entries;
  }

  private static int value(Integer value) {
    return value == null ? 0 : value;
  }
}

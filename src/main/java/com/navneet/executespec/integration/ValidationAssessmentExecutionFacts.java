package com.navneet.executespec.integration;

import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.harness.ExecutionResult;
import com.navneet.executespec.harness.TaskOutcome;
import com.navneet.executespec.smoketest.WorkspaceTestRunner;
import com.navneet.executespec.tool.ExecutionContext;

import lombok.extern.slf4j.Slf4j;

/** Supplemental assessment facts, never authority to accept or publish a generation. */
@Slf4j
final class ValidationAssessmentExecutionFacts {
  private ValidationAssessmentExecutionFacts() {}

  static String capture(
      ExecutionContext context,
      Plan plan,
      ExecutionResult execution,
      WorkspaceTestRunner.DetailedResult tests,
      String before) {
    if (context == null || context.verificationSandboxAuthority() == null || before == null)
      return "";
    try {
      var authority = context.verificationSandboxAuthority();
      authority.assertLive();
      String after =
          new CorrectionWorkspaceTransaction().captureGeneration(context.workspaceRoot());
      if (!before.equals(authority.generation()) || !before.equals(after))
        return "UNAVAILABLE: execution evidence generation changed.";
      return describe(plan, execution, tests, before);
    } catch (RuntimeException unavailable) {
      log.warn(
          "Assessment execution facts unavailable run={} failureType={}",
          context.runId(),
          unavailable.getClass().getSimpleName());
      return "UNAVAILABLE: execution evidence could not be attested.";
    }
  }

  static String describe(
      Plan plan,
      ExecutionResult execution,
      WorkspaceTestRunner.DetailedResult tests,
      String generation) {
    StringBuilder facts =
        new StringBuilder("Server execution facts for generation=" + generation + ". ");
    boolean sequence =
        plan != null
            && execution != null
            && execution.overall() == ExecutionResult.Overall.ALL_SUCCESS
            && execution.perTask() != null
            && plan.tasks().size() == execution.perTask().size()
            && !plan.tasks().isEmpty();
    if (sequence) {
      for (int i = 0; i < plan.tasks().size(); i++) {
        var outcome = execution.perTask().get(i);
        if (!plan.tasks().get(i).id().equals(outcome.taskId())
            || (outcome.status() != TaskOutcome.Status.SUCCESS
                && outcome.status() != TaskOutcome.Status.SUCCESS_AFTER_RETRY)) sequence = false;
      }
    }
    if (sequence)
      facts
          .append("The sequential executor completed all ")
          .append(plan.tasks().size())
          .append(" planned tasks in plan order: ")
          .append(plan.tasks().stream().map(t -> t.id()).toList())
          .append(". ");
    else facts.append("Task sequence completion is not attested here. ");
    if (tests != null && tests.summary() != null) {
      var summary = tests.summary();
      boolean passed =
          tests.exitCode() == 0
              && summary.getPassed() > 0
              && summary.getTotalTests() > 0
              && summary.getFailed() == 0
              && summary.getErrors() == 0
              && summary.getSkipped() >= 0
              && summary.getPassed() + summary.getSkipped() == summary.getTotalTests();
      facts
          .append("Maintained workspace suite: exit=")
          .append(tests.exitCode())
          .append("; passed=")
          .append(summary.getPassed())
          .append("; total=")
          .append(summary.getTotalTests())
          .append("; provenPassing=")
          .append(passed)
          .append(". ");
    } else facts.append("No fresh maintained-suite execution result is available. ");
    return facts
        .append(
            "These facts demonstrate orchestration and suite execution, not independent semantic"
                + " coverage or product end-to-end verification. Still inspect code and tests"
                + " against every requirement.")
        .toString();
  }
}

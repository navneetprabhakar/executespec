package com.navneet.executespec.harness;

import java.util.List;
import java.util.Optional;

import com.navneet.executespec.engine.WorkerResult;
import com.navneet.executespec.report.TestExecutionTrust;
import com.navneet.executespec.testgen.model.TestExecutionSummary;

/** Trust policy for bounded recovered mutations that did not reach {@code completeTask}. */
final class RecoveredMutationVerificationPolicy {

  record Resolution(TaskOutcome.Status status, String warning, boolean verified) {}

  private RecoveredMutationVerificationPolicy() {}

  static Resolution resolve(
      WorkerResult worker, SmokeTestResult smoke, boolean softCompletion, String warning) {
    boolean verified = worker.salvagedUnverified() && smoke.testsVerified();
    TaskOutcome.Status status =
        softCompletion && !verified
            ? TaskOutcome.Status.COMPLETED_WITH_WARNING
            : TaskOutcome.Status.SUCCESS;
    return new Resolution(status, verified ? null : warning, verified);
  }

  static boolean completeTaskClaimed(WorkerResult worker) {
    return worker != null
        && worker.outcome() == WorkerResult.Outcome.COMPLETED
        && !worker.salvagedUnverified();
  }

  static boolean recoveredAndVerified(
      WorkerResult worker, boolean validationPassed, boolean statusClean) {
    return worker != null && worker.salvagedUnverified() && validationPassed && statusClean;
  }

  static void appendCompletedEvidence(
      List<String> completed,
      boolean completeTaskClaimed,
      int fileWrites,
      boolean validationPassed,
      boolean recoveredAndVerified) {
    if (completeTaskClaimed) completed.add("worker called completeTask");
    if (fileWrites > 0) {
      completed.add(fileWrites + " file write" + (fileWrites == 1 ? "" : "s"));
    }
    if (validationPassed) completed.add("smoke validation passed");
    if (recoveredAndVerified) {
      completed.add("recovered mutation passed fresh workspace tests");
    }
  }

  static boolean completionConfidenceLow(
      int fileWrites,
      boolean completeTaskClaimed,
      boolean recoveredAndVerified,
      boolean statusClean) {
    return fileWrites > 0 && ((!completeTaskClaimed && !recoveredAndVerified) || !statusClean);
  }

  static boolean goalCriteriaSatisfied(
      boolean completeTaskClaimed, boolean recoveredAndVerified, boolean requiredOutputsPresent) {
    return (completeTaskClaimed || recoveredAndVerified) && requiredOutputsPresent;
  }

  static boolean freshGreenTests(Optional<TestExecutionSummary> summary) {
    return summary.isPresent()
        && summary.get().hasProvenTestExecution()
        && summary.get().getEvidenceScope()
            == TestExecutionSummary.EvidenceScope.WORKSPACE_TEST_SUITE
        && summary.get().getFailed() == 0
        && summary.get().getErrors() == 0
        && TestExecutionTrust.successfulPopulation(
            summary.get().getPassed(), summary.get().getSkipped(), summary.get().getTotalTests());
  }
}

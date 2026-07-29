package com.navneet.executespec.harness;

import java.util.Map;

import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.engine.WorkerResult;
import com.navneet.executespec.execution.model.GoalDonenessDecision;
import com.navneet.executespec.execution.model.RunContinuationDecision;

/**
 * Immutable continuation authority plus the task-simplicity evidence used by step-down policy.
 *
 * <p>Keeping the evidence with the rest of the per-decision scope prevents simplicity plumbing from
 * spreading across the already-large executor.
 */
record ContinuationConfirmationScope(
    boolean confirmed,
    RunContinuationDecision action,
    String provider,
    String model,
    TaskSimplicityClassifier.Signal simplicity) {

  ContinuationConfirmationScope(
      boolean confirmed, RunContinuationDecision action, String provider, String model) {
    this(confirmed, action, provider, model, null);
  }

  static ContinuationConfirmationScope from(ExecutionRequest request) {
    if (request == null || !Boolean.TRUE.equals(request.userConfirmedExpensiveContinuation())) {
      return new ContinuationConfirmationScope(false, null, null, null);
    }
    return new ContinuationConfirmationScope(
        true,
        request.userConfirmedContinuationAction(),
        request.userConfirmedContinuationProvider(),
        request.userConfirmedContinuationModel());
  }

  ContinuationConfirmationScope withSimplicity(
      PlannedTask task, WorkerResult workerResult, GoalDonenessDecision goalDecision) {
    return new ContinuationConfirmationScope(
        confirmed,
        action,
        provider,
        model,
        TaskSimplicityClassifier.assess(task, workerResult, goalDecision));
  }

  boolean simplerThanExpected() {
    return simplicity != null && simplicity.simplerThanExpected();
  }

  void appendSimplicityEvidence(Map<String, Object> policy) {
    if (simplicity != null) simplicity.appendPolicyEvidence(policy);
  }
}

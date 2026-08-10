package com.navneet.executespec.workspacegeneration;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.navneet.executespec.observability.Metrics;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** One typed SSE and metrics vocabulary for the transactional BUILD generation boundary. */
@Component
@RequiredArgsConstructor
public class WorkspaceGenerationTelemetry {

  private final Metrics metrics;
  private final SseBroadcaster broadcaster;

  public void state(
      String runId,
      String taskId,
      WorkspaceGenerationLifecycle.Prepared prepared,
      String state,
      TaskAcceptanceContract contract,
      String provider,
      String model,
      String candidate,
      String outcome) {
    if (!sharedLoopContract(contract)) return;
    metrics.recordWorkspaceGenerationState(state, provider, model);
    Map<String, Object> data = base(prepared.attemptId(), state, provider, model);
    data.put("taskContractDigest", contract.digest());
    if (candidate != null) data.put("candidateGeneration", candidate);
    if (outcome != null) {
      data.put(
          state.startsWith("INITIAL_FAILURE_") ? "initialFailureCode" : "verificationOutcome",
          outcome);
    }
    emit(runId, taskId, "BUILD generation " + state.toLowerCase(Locale.ROOT), data);
  }

  public void verification(String outcome, String scope, String commandClass, Duration elapsed) {
    metrics.recordWorkspaceGenerationVerification(outcome, scope, commandClass, elapsed);
  }

  public void rollback(String outcome, Duration elapsed) {
    metrics.recordWorkspaceGenerationRollback(outcome, elapsed);
  }

  public void execution(
      String provider, String model, String outcome, int iterations, double costUsd) {
    metrics.recordWorkspaceGenerationExecution(provider, model, outcome, iterations, costUsd);
  }

  public void unresolved(long count) {
    metrics.setUnresolvedWorkspaceGenerationAttempts(count);
  }

  public void authority(String event, String authority) {
    metrics.recordWorkspaceGenerationAuthority(event, authority);
  }

  public void recovery(
      String runId, String taskId, UUID attemptId, String action, String outcome, String state) {
    metrics.recordWorkspaceGenerationRecovery(action, outcome);
    Map<String, Object> data = base(attemptId, state, null, null);
    data.put("recoveryAction", action);
    data.put("recoveryOutcome", outcome);
    emit(runId, taskId, "BUILD generation recovery " + outcome.toLowerCase(Locale.ROOT), data);
  }

  public void control(
      String runId, String taskId, UUID attemptId, String event, String outcome, String boundary) {
    metrics.recordWorkspaceGenerationControl(event, outcome, boundary);
    Map<String, Object> data = base(attemptId, event, null, null);
    data.put("controlEvent", event);
    data.put("controlOutcome", outcome);
    if (boundary != null) data.put("boundary", boundary);
    emit(runId, taskId, "BUILD generation control " + event.toLowerCase(Locale.ROOT), data);
  }

  private void emit(String runId, String taskId, String summary, Map<String, Object> data) {
    broadcaster.emit(runId, RunEvent.workspaceGeneration(runId, taskId, summary, data));
  }

  private static Map<String, Object> base(
      UUID attemptId, String state, String provider, String model) {
    Map<String, Object> data = new LinkedHashMap<>();
    if (attemptId != null) data.put("attemptId", attemptId.toString());
    data.put("state", state);
    data.put("generationKind", "BUILD_TASK");
    if (provider != null) data.put("provider", provider);
    if (model != null) data.put("model", model);
    return data;
  }

  private static boolean sharedLoopContract(TaskAcceptanceContract contract) {
    return contract != null && contract.schemaVersion() >= 2;
  }
}

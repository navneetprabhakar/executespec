package com.navneet.executespec.workspacegeneration;

import java.util.Objects;
import java.util.UUID;

/**
 * Process-local, non-serializable capability for one physical BUILD generation phase.
 *
 * <p>Durable identifiers are evidence, not authority. This object additionally holds the exact live
 * workspace lease and a state-machine fence that performs a fresh database check at every
 * provider/tool/verification dispatch.
 */
public class WorkspaceGenerationDispatchAuthority {

  public enum Phase {
    MUTATION,
    VERIFICATION
  }

  private final String runId;
  private final String taskId;
  private final WorkspaceGenerationLifecycle.Prepared prepared;
  private final Phase phase;
  private final WorkspaceGenerationLifecycle lifecycle;

  public WorkspaceGenerationDispatchAuthority(
      String runId,
      String taskId,
      WorkspaceGenerationLifecycle.Prepared prepared,
      Phase phase,
      WorkspaceGenerationLifecycle lifecycle) {
    this.runId = requireText(runId, "runId");
    this.taskId = requireText(taskId, "taskId");
    this.prepared = Objects.requireNonNull(prepared, "prepared");
    this.phase = Objects.requireNonNull(phase, "phase");
    this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
  }

  public void assertDispatch(String actualRunId, String actualTaskId, Phase requiredPhase) {
    if (!runId.equals(actualRunId)) {
      throw new IllegalStateException("WORKSPACE_GENERATION_AUTHORITY_RUN_MISMATCH");
    }
    if (!taskId.equals(actualTaskId)) {
      throw new IllegalStateException("WORKSPACE_GENERATION_AUTHORITY_TASK_MISMATCH");
    }
    if (phase != requiredPhase) {
      throw new IllegalStateException("WORKSPACE_GENERATION_AUTHORITY_PHASE_MISMATCH");
    }
    if (!runId.equals(prepared.lease().runId())) {
      throw new IllegalStateException("WORKSPACE_GENERATION_AUTHORITY_LEASE_MISMATCH");
    }
    prepared.lease().assertOwned();
    if (phase == Phase.MUTATION) lifecycle.assertMutationDispatch(prepared);
    else lifecycle.assertVerificationDispatch(prepared);
  }

  public UUID attemptId() {
    return prepared.attemptId();
  }

  public Phase phase() {
    return phase;
  }

  @Override
  public String toString() {
    return "WorkspaceGenerationDispatchAuthority[REDACTED]";
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " required");
    return value;
  }
}

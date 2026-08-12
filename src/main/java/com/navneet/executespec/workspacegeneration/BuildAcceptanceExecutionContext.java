package com.navneet.executespec.workspacegeneration;

import com.navneet.executespec.engine.DeterministicFailureDelta;

/**
 * Immutable, explicit BUILD acceptance inputs carried with one task dispatch.
 *
 * <p>This is evidence and execution policy, not authority. The process-local workspace-generation
 * capability remains the only object that can authorize mutation or verification. Keeping these
 * values on the execution context prevents prompt, control, and status semantics from depending on
 * coordinator {@code ThreadLocal} state when dispatch crosses a virtual thread or executor.
 */
public record BuildAcceptanceExecutionContext(
    String systemDirective,
    DeterministicFailureDelta.Snapshot initialFailureSnapshot,
    boolean sharedWorkerControls,
    boolean acceptancePending) {

  public BuildAcceptanceExecutionContext {
    systemDirective = systemDirective == null ? "" : systemDirective;
  }
}

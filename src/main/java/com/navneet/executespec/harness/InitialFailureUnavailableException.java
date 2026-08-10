package com.navneet.executespec.harness;

import com.navneet.executespec.workspacegeneration.TaskAcceptanceVerifier;

/** Stops BUILD before provider dispatch when no deterministic initial failure was observed. */
final class InitialFailureUnavailableException extends IllegalStateException {

  private final TaskAcceptanceVerifier.InitialFailure evidence;

  InitialFailureUnavailableException(TaskAcceptanceVerifier.InitialFailure evidence) {
    super(evidence.code() + ": " + evidence.detail());
    this.evidence = evidence;
  }

  TaskAcceptanceVerifier.InitialFailure evidence() {
    return evidence;
  }
}

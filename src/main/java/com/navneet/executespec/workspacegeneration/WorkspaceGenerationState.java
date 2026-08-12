package com.navneet.executespec.workspacegeneration;

/** Durable phases of one reversible workspace mutation attempt. */
public enum WorkspaceGenerationState {
  ADMITTED,
  OPERATION_CLAIMED,
  AUTHORITY_ACQUIRED,
  PREPARED,
  MUTATING,
  VERIFYING,
  VERIFIED,
  CHECKPOINTING,
  ACCEPTED,
  ROLLING_BACK,
  ROLLED_BACK,
  RECOVERY_FAILED
}

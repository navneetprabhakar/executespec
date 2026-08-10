package com.navneet.executespec.workspacegeneration;

/** Durable phases of one reversible workspace mutation attempt. */
public enum WorkspaceGenerationState {
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

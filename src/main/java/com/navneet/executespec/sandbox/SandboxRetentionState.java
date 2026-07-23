package com.navneet.executespec.sandbox;

/**
 * Artifact retention for a sandbox, tracked separately from the lifecycle state so that tearing
 * down a runtime never implies destroying its audit evidence.
 */
public enum SandboxRetentionState {
  /** Artifacts and audit evidence are kept. */
  RETAINED,

  /** Marked for purge; the sweeper has not yet removed the artifacts. */
  PURGE_PENDING,

  /** Artifacts removed. The lifecycle row itself is retained as audit evidence. */
  PURGED
}

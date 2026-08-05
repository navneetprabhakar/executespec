package com.navneet.executespec.run.sync;

/** A durable workspace exists, but a transient/platform fault prevented restoring it. */
public class RunWorkspaceRestoreFailedException extends IllegalStateException {

  public static final String ERROR_CODE = "WORKSPACE_RESTORE_FAILED";

  public RunWorkspaceRestoreFailedException(String runId, Throwable cause) {
    super("Workspace restore failed for run " + runId, cause);
  }
}

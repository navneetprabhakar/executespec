package com.navneet.executespec.run.sync;

/** A run workspace is neither local nor recoverable from its durable archive. */
public class RunWorkspaceUnavailableException extends IllegalStateException {

  public static final String ERROR_CODE = "WORKSPACE_UNAVAILABLE";

  public RunWorkspaceUnavailableException(String runId, Throwable cause) {
    super("Workspace is unavailable for run " + runId, cause);
  }
}

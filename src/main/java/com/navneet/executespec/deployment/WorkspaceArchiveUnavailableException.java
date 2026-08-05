package com.navneet.executespec.deployment;

/** The run has no durable workspace archive from which a local workspace can be restored. */
public class WorkspaceArchiveUnavailableException extends IllegalStateException {

  public WorkspaceArchiveUnavailableException(String runId) {
    super("No durable workspace archive is available for run " + runId);
  }
}

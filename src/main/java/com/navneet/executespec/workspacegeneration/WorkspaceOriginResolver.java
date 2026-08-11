package com.navneet.executespec.workspacegeneration;

import java.util.Collection;

/** Resolves workspace origin once from durable submission/admission facts. */
public final class WorkspaceOriginResolver {

  private WorkspaceOriginResolver() {}

  public static WorkspaceOrigin submitted(
      String projectId,
      String sourceRunId,
      boolean localExecution,
      String repoMode,
      Collection<?> selectedRepositoryIds) {
    if (sourceRunId != null && !sourceRunId.isBlank()) return WorkspaceOrigin.ITERATION;
    if (localExecution || "EXISTING".equalsIgnoreCase(repoMode)) {
      return WorkspaceOrigin.BROWNFIELD;
    }
    if (projectId != null && !projectId.isBlank()) {
      return selectedRepositoryIds != null && selectedRepositoryIds.size() > 1
          ? WorkspaceOrigin.MULTI_REPOSITORY
          : WorkspaceOrigin.BROWNFIELD;
    }
    return WorkspaceOrigin.GREENFIELD;
  }

  /** Compatibility only for rows created before workspace_origin existed. */
  public static WorkspaceOrigin persistedOrHistorical(
      String persisted,
      String projectId,
      String sourceRunId,
      boolean localExecution,
      String repoMode,
      Collection<?> selectedRepositoryIds) {
    return persisted == null || persisted.isBlank()
        ? submitted(projectId, sourceRunId, localExecution, repoMode, selectedRepositoryIds)
        : WorkspaceOrigin.parse(persisted);
  }
}

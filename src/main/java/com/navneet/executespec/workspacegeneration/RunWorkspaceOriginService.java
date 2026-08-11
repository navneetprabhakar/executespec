package com.navneet.executespec.workspacegeneration;

import java.util.Collection;

import jakarta.persistence.EntityManager;

import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** Owns durable workspace-origin refinement and historical-row resolution for BUILD runs. */
@Component
@RequiredArgsConstructor
public class RunWorkspaceOriginService {

  private final PipelineRunRepository runs;
  private final EntityManager entityManager;

  public String submittedName(
      String projectId,
      String sourceRunId,
      boolean localExecution,
      String repoMode,
      Collection<?> selectedRepositoryIds) {
    return WorkspaceOriginResolver.submitted(
            projectId, sourceRunId, localExecution, repoMode, selectedRepositoryIds)
        .name();
  }

  @Transactional
  public void refineProjectAdmission(String runId, String sourceRunId, int admittedRepositories) {
    if (sourceRunId != null && !sourceRunId.isBlank()) return;
    WorkspaceOrigin origin =
        admittedRepositories == 0
            ? WorkspaceOrigin.GREENFIELD
            : admittedRepositories > 1
                ? WorkspaceOrigin.MULTI_REPOSITORY
                : WorkspaceOrigin.BROWNFIELD;
    int updated =
        entityManager
            .createQuery(
                "UPDATE PipelineRun r SET r.workspaceOrigin = :origin WHERE r.runId = :runId")
            .setParameter("origin", origin.name())
            .setParameter("runId", runId)
            .executeUpdate();
    if (updated != 1) throw new IllegalStateException("WORKSPACE_ORIGIN_PERSIST_FAILED:" + runId);
  }

  public WorkspaceOrigin load(String runId, Collection<?> selectedRepositoryIds) {
    PipelineRun run =
        runs.findById(runId)
            .orElseThrow(() -> new IllegalStateException("Execution run disappeared: " + runId));
    return resolve(run, selectedRepositoryIds);
  }

  public WorkspaceOrigin resolve(PipelineRun run, Collection<?> selectedRepositoryIds) {
    return WorkspaceOriginResolver.persistedOrHistorical(
        run.getWorkspaceOrigin(),
        run.getProjectId(),
        run.getSourceRunId(),
        run.isLocalExecutionRequested(),
        run.getRepoMode(),
        selectedRepositoryIds);
  }
}

package com.navneet.executespec.state.repository;

import java.time.Instant;
import java.util.UUID;

import com.navneet.executespec.state.entity.PipelineRun;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Narrow compare-and-set mutations that must not save a stale {@link PipelineRun} aggregate. */
public interface PipelineRunMutationRepository extends Repository<PipelineRun, String> {

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Transactional
  @Query(
      "UPDATE PipelineRun r SET r.acceptedWorkspaceGeneration = :generation, "
          + "r.acceptedTaskIndex = -1 WHERE r.runId = :runId "
          + "AND r.acceptedWorkspaceGeneration IS NULL AND r.acceptedTaskIndex IS NULL "
          + "AND r.acceptedGenerationAttemptId IS NULL")
  int seedAcceptedWorkspaceFrontier(
      @Param("runId") String runId, @Param("generation") String generation);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Transactional
  @Query(
      "UPDATE PipelineRun r SET r.acceptedWorkspaceGeneration = :candidate, "
          + "r.acceptedTaskIndex = :taskIndex, r.acceptedGenerationAttemptId = :attemptId, "
          + "r.acceptedCheckpointKind = :checkpointKind, "
          + "r.acceptedCheckpointKey = :checkpointKey, "
          + "r.acceptedCheckpointDigest = :checkpointDigest, "
          + "r.acceptedCheckpointCompletedAt = :checkpointCompletedAt "
          + "WHERE r.runId = :runId AND r.status = 'WORKING' "
          + "AND r.acceptedWorkspaceGeneration = :before "
          + "AND r.acceptedTaskIndex = :previousTaskIndex")
  int advanceAcceptedWorkspaceFrontier(
      @Param("runId") String runId,
      @Param("before") String beforeGeneration,
      @Param("previousTaskIndex") int previousTaskIndex,
      @Param("candidate") String candidateGeneration,
      @Param("taskIndex") int taskIndex,
      @Param("attemptId") UUID attemptId,
      @Param("checkpointKind") String checkpointKind,
      @Param("checkpointKey") String checkpointKey,
      @Param("checkpointDigest") String checkpointDigest,
      @Param("checkpointCompletedAt") Instant checkpointCompletedAt);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Transactional
  @Query("UPDATE PipelineRun r SET r.gcsWorkspaceKey = :key WHERE r.runId = :runId")
  int updateGcsWorkspaceKey(@Param("runId") String runId, @Param("key") String key);
}

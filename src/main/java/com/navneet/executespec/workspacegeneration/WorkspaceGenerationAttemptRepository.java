package com.navneet.executespec.workspacegeneration;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkspaceGenerationAttemptRepository
    extends JpaRepository<WorkspaceGenerationAttempt, UUID> {

  List<WorkspaceGenerationAttempt> findByStateInOrderByCreatedAtAsc(
      List<WorkspaceGenerationState> states);

  Optional<WorkspaceGenerationAttempt> findFirstByRunIdAndStateInOrderByCreatedAtDesc(
      String runId, List<WorkspaceGenerationState> states);

  List<WorkspaceGenerationAttempt> findTop20ByRunIdOrderByCreatedAtDesc(String runId);

  List<WorkspaceGenerationAttempt> findByStateOrderByCreatedAtAsc(WorkspaceGenerationState state);

  @Query(
      "SELECT COALESCE(MAX(a.attemptNumber), 0) FROM WorkspaceGenerationAttempt a "
          + "WHERE a.runId = :runId AND a.taskId = :taskId")
  int maximumAttemptNumber(@Param("runId") String runId, @Param("taskId") String taskId);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE WorkspaceGenerationAttempt a SET a.state = :next, a.updatedAt = :now "
          + "WHERE a.id = :id AND a.workspaceLeaseToken = :token AND a.state = :expected")
  int transition(
      @Param("id") UUID id,
      @Param("token") UUID token,
      @Param("expected") WorkspaceGenerationState expected,
      @Param("next") WorkspaceGenerationState next,
      @Param("now") Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE WorkspaceGenerationAttempt a SET a.llmOperationId = :operationId, "
          + "a.llmAttemptNumber = :attemptNumber, a.llmLeaseToken = :llmLeaseToken, "
          + "a.updatedAt = :now WHERE a.id = :id AND a.workspaceLeaseToken = :workspaceToken "
          + "AND a.state = :mutating AND a.llmOperationId IS NULL")
  int bindLlmAttempt(
      @Param("id") UUID id,
      @Param("workspaceToken") UUID workspaceToken,
      @Param("operationId") String operationId,
      @Param("attemptNumber") int attemptNumber,
      @Param("llmLeaseToken") UUID llmLeaseToken,
      @Param("mutating") WorkspaceGenerationState mutating,
      @Param("now") Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE WorkspaceGenerationAttempt a SET a.observedInitialFailureEvidenceJson = :evidence, "
          + "a.initialFailureObservedAt = :observedAt, a.updatedAt = :observedAt "
          + "WHERE a.id = :id AND a.workspaceLeaseToken = :token AND a.state = :mutating "
          + "AND a.observedInitialFailureEvidenceJson IS NULL")
  int recordObservedInitialFailure(
      @Param("id") UUID id,
      @Param("token") UUID token,
      @Param("mutating") WorkspaceGenerationState mutating,
      @Param("evidence") String evidenceJson,
      @Param("observedAt") Instant observedAt);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE WorkspaceGenerationAttempt a SET a.effectiveProvider = :provider, "
          + "a.effectiveModel = :model, a.workerIterations = :iterations, "
          + "a.workerToolCalls = :toolCalls, a.modelCalls = :modelCalls, "
          + "a.inputTokens = :inputTokens, a.outputTokens = :outputTokens, "
          + "a.cacheReadTokens = :cacheReadTokens, a.cacheWriteTokens = :cacheWriteTokens, "
          + "a.reasoningTokens = :reasoningTokens, a.costUsd = :costUsd, a.updatedAt = :now "
          + "WHERE a.id = :id AND a.workspaceLeaseToken = :token AND a.state = :mutating "
          + "AND a.effectiveProvider IS NULL")
  int recordExecutionEvidence(
      @Param("id") UUID id,
      @Param("token") UUID token,
      @Param("mutating") WorkspaceGenerationState mutating,
      @Param("provider") String provider,
      @Param("model") String model,
      @Param("iterations") int iterations,
      @Param("toolCalls") int toolCalls,
      @Param("modelCalls") long modelCalls,
      @Param("inputTokens") long inputTokens,
      @Param("outputTokens") long outputTokens,
      @Param("cacheReadTokens") long cacheReadTokens,
      @Param("cacheWriteTokens") long cacheWriteTokens,
      @Param("reasoningTokens") long reasoningTokens,
      @Param("costUsd") java.math.BigDecimal costUsd,
      @Param("now") Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE WorkspaceGenerationAttempt a SET a.state = :verifying, "
          + "a.candidateGeneration = :candidate, a.updatedAt = :now "
          + "WHERE a.id = :id AND a.workspaceLeaseToken = :token AND a.state = :mutating")
  int beginVerification(
      @Param("id") UUID id,
      @Param("token") UUID token,
      @Param("candidate") String candidateGeneration,
      @Param("mutating") WorkspaceGenerationState mutating,
      @Param("verifying") WorkspaceGenerationState verifying,
      @Param("now") Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE WorkspaceGenerationAttempt a SET a.checkpointKind = :kind, "
          + "a.checkpointKey = :key, a.checkpointGeneration = :generation, "
          + "a.checkpointDigest = :digest, a.checkpointCompletedAt = :completedAt, "
          + "a.updatedAt = :completedAt WHERE a.id = :id AND a.workspaceLeaseToken = :token "
          + "AND a.state = :checkpointing AND a.candidateGeneration = :generation "
          + "AND a.checkpointKind IS NULL AND a.checkpointKey IS NULL "
          + "AND a.checkpointGeneration IS NULL AND a.checkpointDigest IS NULL "
          + "AND a.checkpointCompletedAt IS NULL")
  int completeCheckpoint(
      @Param("id") UUID id,
      @Param("token") UUID token,
      @Param("kind") String kind,
      @Param("key") String key,
      @Param("generation") String generation,
      @Param("digest") String digest,
      @Param("checkpointing") WorkspaceGenerationState checkpointing,
      @Param("completedAt") Instant completedAt);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE WorkspaceGenerationAttempt a SET a.state = :accepted, "
          + "a.resolvedAt = :now, a.updatedAt = :now "
          + "WHERE a.id = :id AND a.workspaceLeaseToken = :token "
          + "AND a.state = :checkpointing AND a.candidateGeneration = :candidate")
  int accept(
      @Param("id") UUID id,
      @Param("token") UUID token,
      @Param("candidate") String candidateGeneration,
      @Param("checkpointing") WorkspaceGenerationState checkpointing,
      @Param("accepted") WorkspaceGenerationState accepted,
      @Param("now") Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE WorkspaceGenerationAttempt a SET a.state = :rollingBack, "
          + "a.rejectedDiffDigest = :diffDigest, a.rejectedDiffJson = :diffJson, "
          + "a.failureCode = :code, a.failureReason = :reason, a.updatedAt = :now "
          + "WHERE a.id = :id AND a.workspaceLeaseToken = :token AND a.state = :expected")
  int beginRollback(
      @Param("id") UUID id,
      @Param("token") UUID token,
      @Param("expected") WorkspaceGenerationState expected,
      @Param("rollingBack") WorkspaceGenerationState rollingBack,
      @Param("diffDigest") String diffDigest,
      @Param("diffJson") String diffJson,
      @Param("code") String code,
      @Param("reason") String reason,
      @Param("now") Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE WorkspaceGenerationAttempt a SET a.state = :rollingBack, "
          + "a.recoveryLeaseToken = :recoveryToken, a.recoveryStartedAt = :now, "
          + "a.updatedAt = :now WHERE a.id = :id AND a.state = :expected")
  int claimRecovery(
      @Param("id") UUID id,
      @Param("expected") WorkspaceGenerationState expected,
      @Param("rollingBack") WorkspaceGenerationState rollingBack,
      @Param("recoveryToken") UUID recoveryToken,
      @Param("now") Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE WorkspaceGenerationAttempt a SET "
          + "a.rejectedDiffDigest = COALESCE(a.rejectedDiffDigest, :diffDigest), "
          + "a.rejectedDiffJson = COALESCE(a.rejectedDiffJson, :diffJson), "
          + "a.failureCode = COALESCE(a.failureCode, :code), "
          + "a.failureReason = COALESCE(a.failureReason, :reason), a.updatedAt = :now "
          + "WHERE a.id = :id AND a.recoveryLeaseToken = :token "
          + "AND a.state = :rollingBack")
  int recordRecoveryEvidence(
      @Param("id") UUID id,
      @Param("token") UUID recoveryToken,
      @Param("rollingBack") WorkspaceGenerationState rollingBack,
      @Param("diffDigest") String diffDigest,
      @Param("diffJson") String diffJson,
      @Param("code") String code,
      @Param("reason") String reason,
      @Param("now") Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE WorkspaceGenerationAttempt a SET a.state = :rolledBack, "
          + "a.resolvedAt = :now, a.updatedAt = :now "
          + "WHERE a.id = :id AND a.workspaceLeaseToken = :token "
          + "AND a.state = :rollingBack")
  int markRolledBack(
      @Param("id") UUID id,
      @Param("token") UUID token,
      @Param("rollingBack") WorkspaceGenerationState rollingBack,
      @Param("rolledBack") WorkspaceGenerationState rolledBack,
      @Param("now") Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE WorkspaceGenerationAttempt a SET a.state = :recoveryFailed, "
          + "a.recoveryFailureCode = COALESCE(a.recoveryFailureCode, :code), "
          + "a.recoveryFailureReason = COALESCE(a.recoveryFailureReason, :reason), "
          + "a.recoveryFailureAt = COALESCE(a.recoveryFailureAt, :now), a.updatedAt = :now "
          + "WHERE a.id = :id AND a.workspaceLeaseToken = :token "
          + "AND a.state = :rollingBack")
  int markRollbackFailed(
      @Param("id") UUID id,
      @Param("token") UUID workspaceToken,
      @Param("code") String code,
      @Param("reason") String reason,
      @Param("rollingBack") WorkspaceGenerationState rollingBack,
      @Param("recoveryFailed") WorkspaceGenerationState recoveryFailed,
      @Param("now") Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE WorkspaceGenerationAttempt a SET a.state = :rolledBack, "
          + "a.resolvedAt = :now, a.updatedAt = :now "
          + "WHERE a.id = :id AND a.recoveryLeaseToken = :token "
          + "AND a.state = :rollingBack")
  int markRecoveredRolledBack(
      @Param("id") UUID id,
      @Param("token") UUID token,
      @Param("rollingBack") WorkspaceGenerationState rollingBack,
      @Param("rolledBack") WorkspaceGenerationState rolledBack,
      @Param("now") Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE WorkspaceGenerationAttempt a SET a.state = :recoveryFailed, "
          + "a.recoveryFailureCode = COALESCE(a.recoveryFailureCode, :code), "
          + "a.recoveryFailureReason = COALESCE(a.recoveryFailureReason, :reason), "
          + "a.recoveryFailureAt = COALESCE(a.recoveryFailureAt, :now), a.updatedAt = :now "
          + "WHERE a.id = :id AND a.recoveryLeaseToken = :token "
          + "AND a.state = :rollingBack")
  int markRecoveredFailure(
      @Param("id") UUID id,
      @Param("token") UUID token,
      @Param("code") String code,
      @Param("reason") String reason,
      @Param("rollingBack") WorkspaceGenerationState rollingBack,
      @Param("recoveryFailed") WorkspaceGenerationState recoveryFailed,
      @Param("now") Instant now);

  @Query(
      value =
          """
          SELECT COUNT(*)
            FROM workspace_generation_attempt a
            JOIN pipeline_run p ON p.run_id = a.run_id
            JOIN run_workspace_authority w ON w.run_id = a.run_id
            JOIN llm_execution_operation o ON o.id = a.llm_operation_id
           WHERE a.id = :id
             AND a.workspace_lease_token = :workspaceToken
             AND a.state = 'MUTATING'
             AND p.status = 'WORKING'
             AND w.owner_token = a.workspace_lease_token
             AND w.authority_type = 'BUILD_GENERATION'
             AND w.lease_expires_at >= NOW()
             AND o.attempt = a.llm_attempt_number
             AND o.lease_token = a.llm_lease_token
             AND o.operation_kind = 'RUN_BUILD_TASK'
             AND o.operation_key = CONCAT('pipeline-run:', a.run_id, ':build-task:', a.task_id)
             AND o.status = 'RUNNING'
             AND o.lease_expires_at >= NOW()
          """,
      nativeQuery = true)
  int countCurrentDispatchAuthority(
      @Param("id") UUID id, @Param("workspaceToken") UUID workspaceToken);
}

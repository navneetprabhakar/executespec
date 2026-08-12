package com.navneet.executespec.workspacegeneration;

import java.time.Duration;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/** Creates BUILD attempts only through the legal V383 pre-dispatch state sequence. */
final class WorkspaceGenerationPostgresFixture {

  private WorkspaceGenerationPostgresFixture() {}

  static OperationBinding insertPrepared(
      JdbcTemplate jdbc,
      UUID attemptId,
      String runId,
      String taskId,
      int taskIndex,
      int attemptNumber,
      String workspaceRoot,
      UUID workspaceToken,
      String beforeGeneration,
      String repositoriesJson,
      String authorityDigest,
      String taskContractDigest,
      String ignoredBaselineJson,
      String ignoredBaselineDigest,
      String beforeCheckpointKind,
      String beforeCheckpointKey,
      String beforeCheckpointDigest) {
    return insertPrepared(
        jdbc,
        attemptId,
        runId,
        taskId,
        taskIndex,
        attemptNumber,
        workspaceRoot,
        workspaceToken,
        beforeGeneration,
        repositoriesJson,
        authorityDigest,
        taskContractDigest,
        ignoredBaselineJson,
        ignoredBaselineDigest,
        beforeCheckpointKind,
        beforeCheckpointKey,
        beforeCheckpointDigest,
        "ctx-" + UUID.randomUUID(),
        "op-" + UUID.randomUUID(),
        UUID.randomUUID(),
        Duration.ofMinutes(10));
  }

  static OperationBinding insertPrepared(
      JdbcTemplate jdbc,
      UUID attemptId,
      String runId,
      String taskId,
      int taskIndex,
      int attemptNumber,
      String workspaceRoot,
      UUID workspaceToken,
      String beforeGeneration,
      String repositoriesJson,
      String authorityDigest,
      String taskContractDigest,
      String ignoredBaselineJson,
      String ignoredBaselineDigest,
      String beforeCheckpointKind,
      String beforeCheckpointKey,
      String beforeCheckpointDigest,
      String contextId,
      String operationId,
      UUID operationToken,
      Duration operationLease) {
    return insertPreparedExact(
        jdbc,
        attemptId,
        runId,
        taskId,
        taskIndex,
        attemptNumber,
        workspaceRoot,
        workspaceToken,
        beforeGeneration,
        repositoriesJson,
        authorityDigest,
        taskContractDigest,
        ignoredBaselineJson,
        ignoredBaselineDigest,
        beforeCheckpointKind,
        beforeCheckpointKey,
        beforeCheckpointDigest,
        "fixture-generation",
        1024,
        "fixture-crc",
        "fixture-etag",
        contextId,
        operationId,
        operationToken,
        operationLease);
  }

  static OperationBinding insertPreparedExact(
      JdbcTemplate jdbc,
      UUID attemptId,
      String runId,
      String taskId,
      int taskIndex,
      int attemptNumber,
      String workspaceRoot,
      UUID workspaceToken,
      String beforeGeneration,
      String repositoriesJson,
      String authorityDigest,
      String taskContractDigest,
      String ignoredBaselineJson,
      String ignoredBaselineDigest,
      String beforeCheckpointKind,
      String beforeCheckpointKey,
      String beforeCheckpointDigest,
      String storageGeneration,
      long sizeBytes,
      String crc32c,
      String etag) {
    return insertPreparedExact(
        jdbc,
        attemptId,
        runId,
        taskId,
        taskIndex,
        attemptNumber,
        workspaceRoot,
        workspaceToken,
        beforeGeneration,
        repositoriesJson,
        authorityDigest,
        taskContractDigest,
        ignoredBaselineJson,
        ignoredBaselineDigest,
        beforeCheckpointKind,
        beforeCheckpointKey,
        beforeCheckpointDigest,
        storageGeneration,
        sizeBytes,
        crc32c,
        etag,
        "ctx-" + UUID.randomUUID(),
        "op-" + UUID.randomUUID(),
        UUID.randomUUID(),
        Duration.ofMinutes(10));
  }

  private static OperationBinding insertPreparedExact(
      JdbcTemplate jdbc,
      UUID attemptId,
      String runId,
      String taskId,
      int taskIndex,
      int attemptNumber,
      String workspaceRoot,
      UUID workspaceToken,
      String beforeGeneration,
      String repositoriesJson,
      String authorityDigest,
      String taskContractDigest,
      String ignoredBaselineJson,
      String ignoredBaselineDigest,
      String beforeCheckpointKind,
      String beforeCheckpointKey,
      String beforeCheckpointDigest,
      String storageGeneration,
      long sizeBytes,
      String crc32c,
      String etag,
      String contextId,
      String operationId,
      UUID operationToken,
      Duration operationLease) {
    jdbc.update(
        "INSERT INTO llm_execution_context("
            + "id,context_type,feature,source_type,source_id,correlation_id,scope_mode,isolation_key,"
            + "key_source_mode,allowed_providers_json,allowed_provider_models_json,"
            + "provider_key_sources_json,model_overrides_json,routing_mode,route_snapshot_version,status,max_attempts,idempotency_key,request_fingerprint)"
            + " VALUES (?,'PIPELINE_RUN','worker','PIPELINE',?,'workspace-generation-fixture',"
            + "'PLATFORM_INTERNAL','platform:internal','SYSTEM','[]'::jsonb,'{}'::jsonb,"
            + "'{}'::jsonb,'{}'::jsonb,'PROVIDER_PREFERRED',1,'QUEUED',4,?,repeat('7',64))",
        contextId,
        runId,
        "idem-" + UUID.randomUUID());
    jdbc.update(
        "INSERT INTO llm_execution_operation(id,llm_execution_context_id,operation_key,"
            + "operation_kind,invocation,operation_fingerprint,status,attempt,max_attempts,"
            + "lease_owner,lease_token,lease_expires_at,heartbeat_at,started_at,created_at,updated_at)"
            + " VALUES"
            + " (?,?,?,'RUN_BUILD_TASK',1,repeat('8',64),'RUNNING',1,4,'fixture-owner',?::uuid,NOW()+(?"
            + " * INTERVAL '1 millisecond'),NOW(),NOW(),NOW(),NOW())",
        operationId,
        contextId,
        "pipeline-run:" + runId + ":build-task:" + taskId,
        operationToken.toString(),
        operationLease.toMillis());
    jdbc.update(
        "INSERT INTO workspace_generation_attempt("
            + "id,run_id,task_id,task_index,attempt_number,attempt_kind,state,workspace_root,"
            + "authority_digest,task_contract_digest,build_protocol_version,"
            + "verification_command_protocol_version,created_at,updated_at)"
            + " VALUES (?,?,?,?,?,'BUILD_TASK','ADMITTED',?,?,?,"
            + "(SELECT build_protocol_version FROM pipeline_run WHERE run_id=?),"
            + "(SELECT CASE WHEN build_protocol_version='SHARED_KERNEL_V2' THEN 2 ELSE 1 END"
            + " FROM pipeline_run WHERE run_id=?),NOW(),NOW())",
        attemptId,
        runId,
        taskId,
        taskIndex,
        attemptNumber,
        workspaceRoot,
        authorityDigest,
        taskContractDigest,
        runId,
        runId);
    jdbc.update(
        "UPDATE workspace_generation_attempt SET state='OPERATION_CLAIMED',"
            + "llm_operation_id=?,llm_attempt_number=1,llm_lease_token=?::uuid,updated_at=NOW()"
            + " WHERE id=?",
        operationId,
        operationToken.toString(),
        attemptId);
    jdbc.update(
        "UPDATE workspace_generation_attempt SET state='AUTHORITY_ACQUIRED',"
            + "workspace_lease_token=?::uuid,updated_at=NOW() WHERE id=?",
        workspaceToken.toString(),
        attemptId);
    jdbc.update(
        "UPDATE workspace_generation_attempt SET state='PREPARED',before_generation=?,"
            + "repositories_json=?::jsonb,ignored_artifact_baseline_json=?::jsonb,"
            + "ignored_artifact_baseline_digest=?,before_checkpoint_kind=?,"
            + "before_checkpoint_key=?,before_checkpoint_digest=?,"
            + "before_checkpoint_storage_generation=?,"
            + "before_checkpoint_size_bytes=?,before_checkpoint_crc32c=?,"
            + "before_checkpoint_etag=?,"
            + "before_checkpoint_completed_at=NOW(),updated_at=NOW() WHERE id=?",
        beforeGeneration,
        repositoriesJson,
        ignoredBaselineJson,
        ignoredBaselineDigest,
        beforeCheckpointKind,
        beforeCheckpointKey,
        beforeCheckpointDigest,
        storageGeneration,
        sizeBytes,
        crc32c,
        etag,
        attemptId);
    return new OperationBinding(contextId, operationId, operationToken);
  }

  record OperationBinding(String contextId, String operationId, UUID operationToken) {}
}

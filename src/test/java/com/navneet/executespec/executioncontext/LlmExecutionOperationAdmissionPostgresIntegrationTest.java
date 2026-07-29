package com.navneet.executespec.executioncontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.navneet.executespec.cost.LlmUsageAttemptIntent;
import com.navneet.executespec.integration.PostgresIntegrationBase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real PostgreSQL proof for atomic reservation, operation fencing and intent rollback. */
class LlmExecutionOperationAdmissionPostgresIntegrationTest extends PostgresIntegrationBase {

  private static final UUID TOKEN = UUID.fromString("00000000-0000-0000-0000-000000000354");
  private static final LlmExecutionScope SCOPE = LlmExecutionScope.platformInternal();

  @Autowired LlmExecutionOperationAdmissionService service;
  @Autowired JdbcTemplate jdbc;

  private String contextId;
  private String operationId;
  private LlmExecutionReference reference;
  private LlmExecutionOperationClaim claim;

  @BeforeEach
  void setUp() {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    contextId = "llmctx_admit_" + suffix.substring(0, 24);
    operationId = "llmop_admit_" + suffix.substring(0, 24);
    reference = new LlmExecutionReference(contextId, SCOPE);
    claim = new LlmExecutionOperationClaim(reference, operationId, 1, 1, TOKEN);
    insertContext(1);
    insertRunningOperation();
  }

  @Test
  void durationAdmissionCommitsZeroOutputReservationHorizonsAndIntentFromOneDbClock() {
    jdbc.update("UPDATE llm_execution_context SET max_output_tokens=1 WHERE id=?", contextId);

    LlmExecutionOperationAdmissionService.AdmittedAttempt admission =
        service.admit(
            reference,
            LlmExecutionOperationAuthority.issue(claim, "owner-a"),
            draft("physical-success-" + contextId),
            0,
            Duration.ofSeconds(120),
            Duration.ofSeconds(10));
    Instant deadline = admission.transportDeadline();
    Instant startedAt =
        jdbc.queryForObject(
            "SELECT started_at FROM llm_usage_attempt_intent WHERE physical_attempt_id=?",
            Instant.class,
            "physical-success-" + contextId);

    assertThat(
            jdbc.queryForObject(
                "SELECT reserved_provider_calls FROM llm_execution_context WHERE id=?",
                Long.class,
                contextId))
        .isEqualTo(1L);
    assertThat(startedAt).isNotNull();
    assertThat(Duration.between(startedAt, deadline)).isEqualTo(Duration.ofSeconds(120));
    assertThat(admission.intent().getTransportDeadlineAt()).isEqualTo(deadline);
    assertThat(
            jdbc.queryForObject(
                "SELECT lease_expires_at FROM llm_execution_operation WHERE id=?",
                Instant.class,
                operationId))
        .isAfterOrEqualTo(deadline.plusSeconds(10));
    assertThat(
            jdbc.queryForObject(
                "SELECT not_reclaim_before FROM llm_execution_operation WHERE id=?",
                Instant.class,
                operationId))
        .isAfterOrEqualTo(deadline.plusSeconds(10));
    assertThat(
            jdbc.queryForMap(
                "SELECT operation_fence_version,llm_execution_context_id,"
                    + "llm_execution_operation_id,llm_execution_operation_attempt,"
                    + "llm_execution_lease_token,operation_not_reclaim_before,"
                    + "transport_deadline_at FROM llm_usage_attempt_intent"
                    + " WHERE physical_attempt_id=?",
                "physical-success-" + contextId))
        .containsEntry("operation_fence_version", 1)
        .containsEntry("llm_execution_context_id", contextId)
        .containsEntry("llm_execution_operation_id", operationId)
        .containsEntry("llm_execution_operation_attempt", 1);
  }

  @Test
  void intentFlushFailureRollsBackBudgetAndBothOperationHorizons() {
    String duplicateId = "physical-duplicate-" + contextId;
    jdbc.update(contextFreeIntentInsert(), duplicateId);
    Instant deadline = databaseNow().plusSeconds(120);

    assertThatThrownBy(
            () ->
                service.admit(
                    reference,
                    LlmExecutionOperationAuthority.issue(claim, "owner-a"),
                    draft(duplicateId),
                    1200,
                    deadline,
                    Duration.ofSeconds(10)))
        .isInstanceOf(RuntimeException.class)
        .rootCause()
        .hasMessageContaining("append-only");

    assertThat(
            jdbc.queryForObject(
                "SELECT reserved_provider_calls FROM llm_execution_context WHERE id=?",
                Long.class,
                contextId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT lease_expires_at FROM llm_execution_operation WHERE id=?",
                Instant.class,
                operationId))
        .isBefore(deadline);
    assertThat(
            jdbc.queryForObject(
                "SELECT not_reclaim_before IS NULL FROM llm_execution_operation WHERE id=?",
                Boolean.class,
                operationId))
        .isTrue();
  }

  @Test
  void concurrentAdmissionsAtFinalProviderCallUnitHaveOneCoherentWinner() throws Exception {
    Instant deadline = databaseNow().plusSeconds(120);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Boolean>> results;
    try (var pool = Executors.newFixedThreadPool(2)) {
      results =
          List.of(
              pool.submit(
                  () -> admitAfterBarrier("physical-race-a-" + contextId, deadline, ready, start)),
              pool.submit(
                  () -> admitAfterBarrier("physical-race-b-" + contextId, deadline, ready, start)));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(
              List.of(
                  results.get(0).get(20, TimeUnit.SECONDS),
                  results.get(1).get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }

    assertThat(
            jdbc.queryForObject(
                "SELECT reserved_provider_calls FROM llm_execution_context WHERE id=?",
                Long.class,
                contextId))
        .isEqualTo(1L);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM llm_usage_attempt_intent"
                    + " WHERE llm_execution_context_id=? AND operation_fence_version=1",
                Integer.class,
                contextId))
        .isEqualTo(1);
  }

  @Test
  void staleTokenAndCancellationBothFailBeforeReservation() {
    var staleClaim =
        new LlmExecutionOperationClaim(reference, operationId, 1, 1, UUID.randomUUID());
    assertThatThrownBy(
            () ->
                service.admit(
                    reference,
                    LlmExecutionOperationAuthority.issue(staleClaim, "owner-a"),
                    draft("physical-stale-" + contextId),
                    1200,
                    databaseNow().plusSeconds(120),
                    Duration.ofSeconds(10)))
        .isInstanceOf(LlmExecutionOperationException.class)
        .extracting(error -> ((LlmExecutionOperationException) error).reason())
        .isEqualTo(LlmExecutionOperationException.Reason.LEASE_NOT_OWNED);

    jdbc.update(
        "UPDATE llm_execution_operation SET status='CANCEL_REQUESTED',"
            + "cancellation_requested_at=now(),updated_at=now() WHERE id=?",
        operationId);
    assertThatThrownBy(
            () ->
                service.admit(
                    reference,
                    LlmExecutionOperationAuthority.issue(claim, "owner-a"),
                    draft("physical-cancel-" + contextId),
                    1200,
                    databaseNow().plusSeconds(120),
                    Duration.ofSeconds(10)))
        .isInstanceOf(LlmExecutionOperationException.class)
        .extracting(error -> ((LlmExecutionOperationException) error).reason())
        .isEqualTo(LlmExecutionOperationException.Reason.LEASE_NOT_OWNED);
    assertThat(
            jdbc.queryForObject(
                "SELECT reserved_provider_calls FROM llm_execution_context WHERE id=?",
                Long.class,
                contextId))
        .isZero();
  }

  private boolean admitAfterBarrier(
      String physicalId, Instant deadline, CountDownLatch ready, CountDownLatch start)
      throws InterruptedException {
    ready.countDown();
    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
    try {
      service.admit(
          reference,
          LlmExecutionOperationAuthority.issue(claim, "owner-a"),
          draft(physicalId),
          1200,
          deadline,
          Duration.ofSeconds(10));
      return true;
    } catch (LlmDispatchPolicyException exhausted) {
      assertThat(exhausted.reason()).isEqualTo(LlmDispatchPolicyException.Reason.BUDGET_EXHAUSTED);
      return false;
    }
  }

  private void insertContext(int maxProviderCalls) {
    jdbc.update(
        "INSERT INTO llm_execution_context("
            + "id,context_type,feature,source_type,source_id,correlation_id,scope_mode,isolation_key,"
            + "key_source_mode,allowed_providers_json,allowed_provider_models_json,"
            + "provider_key_sources_json,model_overrides_json,routing_mode,route_snapshot_version,"
            + "max_output_tokens,max_total_tokens,max_provider_calls,status,max_attempts,idempotency_key,request_fingerprint)"
            + " VALUES (?,'INTERNAL_TEST','test','INTERNAL','source','correlation',"
            + "'PLATFORM_INTERNAL','platform:internal','SYSTEM','[\"openai\"]'::jsonb,"
            + "'{\"openai\":[\"gpt-5.4-mini\"]}'::jsonb,"
            + "'{\"openai\":\"PLATFORM\"}'::jsonb,'{}'::jsonb,'PROVIDER_PREFERRED',1,"
            + "10000,20000,?,'QUEUED',1,?,repeat('a',64))",
        contextId,
        maxProviderCalls,
        "idem-" + contextId);
  }

  private void insertRunningOperation() {
    jdbc.update(
        "INSERT INTO llm_execution_operation("
            + "id,llm_execution_context_id,operation_key,operation_kind,invocation,"
            + "operation_fingerprint,status,attempt,max_attempts,next_attempt_at,created_at,updated_at)"
            + " VALUES (?,?,'operation/test','TEST',1,repeat('b',64),'QUEUED',0,1,"
            + "now(),now(),now())",
        operationId,
        contextId);
    jdbc.update(
        "UPDATE llm_execution_operation SET"
            + " status='RUNNING',attempt=1,lease_owner='owner-a',lease_token=?::uuid,lease_expires_at=now()"
            + " + interval '60"
            + " seconds',heartbeat_at=now(),next_attempt_at=NULL,started_at=now(),updated_at=now()"
            + " WHERE id=?",
        TOKEN.toString(),
        operationId);
  }

  private Instant databaseNow() {
    return jdbc.queryForObject("SELECT clock_timestamp()", Instant.class);
  }

  private static LlmUsageAttemptIntent draft(String physicalAttemptId) {
    return LlmUsageAttemptIntent.builder()
        .physicalAttemptId(physicalAttemptId)
        .logicalCallId("logical-" + physicalAttemptId)
        .attemptKind("PRIMARY")
        .attemptOrdinal(1)
        .correlationId("correlation")
        .feature("test")
        .sourceType("INTERNAL")
        .sourceId("source")
        .runId("run")
        .agentRole("test")
        .humanRoleSnapshot("SUPER_ADMIN")
        .roleProvenance("CAPTURED_AT_DISPATCH")
        .provider("openai")
        .model("gpt-5.4-mini")
        .modelTier("LIGHTWEIGHT")
        .keySource("PLATFORM")
        .startedAt(Instant.EPOCH)
        .build();
  }

  private static String contextFreeIntentInsert() {
    return "INSERT INTO llm_usage_attempt_intent("
        + "physical_attempt_id,logical_call_id,attempt_kind,attempt_ordinal,correlation_id,"
        + "feature,source_type,source_id,run_id,agent_role,human_role_snapshot,role_provenance,"
        + "provider,model,model_tier,key_source,started_at,operation_fence_version)"
        + " VALUES (?,'logical-legacy','PRIMARY',1,'correlation','test','INTERNAL','source',"
        + "'run','test','SUPER_ADMIN','CAPTURED_AT_DISPATCH','openai','gpt-5.4-mini',"
        + "'LIGHTWEIGHT','PLATFORM',now(),0)";
  }
}

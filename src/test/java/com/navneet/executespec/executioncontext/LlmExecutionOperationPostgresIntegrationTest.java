package com.navneet.executespec.executioncontext;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.navneet.executespec.integration.PostgresIntegrationBase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real PostgreSQL/Hibernate proof for cross-JVM-style claim serialization and stale fencing. */
class LlmExecutionOperationPostgresIntegrationTest extends PostgresIntegrationBase {

  private static final String CONTEXT_ID = "llmctx_operation_concurrency";
  private static final LlmExecutionScope SCOPE = LlmExecutionScope.platformInternal();

  @Autowired LlmExecutionOperationService service;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM llm_execution_operation WHERE llm_execution_context_id=?", CONTEXT_ID);
    jdbc.update("DELETE FROM llm_execution_context WHERE id=?", CONTEXT_ID);
    jdbc.update(platformContextInsert(), CONTEXT_ID);
  }

  @Test
  void concurrentClaimHasOneWinnerAndExpiredReplacementFencesTheOldToken() throws Exception {
    var reference = new LlmExecutionReference(CONTEXT_ID, SCOPE);
    var descriptor =
        LlmExecutionOperationDescriptor.of("worker/task-1", "WORKER", "run-1|task-1|plan-sha", 3);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);

    List<Optional<LlmExecutionOperationClaim>> claims;
    try (var pool = Executors.newFixedThreadPool(2)) {
      Future<Optional<LlmExecutionOperationClaim>> first =
          pool.submit(() -> claimAfterBarrier(reference, descriptor, "owner-a", ready, start));
      Future<Optional<LlmExecutionOperationClaim>> second =
          pool.submit(() -> claimAfterBarrier(reference, descriptor, "owner-b", ready, start));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      claims = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
    }

    assertThat(claims.stream().filter(Optional::isPresent)).hasSize(1);
    LlmExecutionOperationClaim original =
        claims.stream().flatMap(Optional::stream).findFirst().orElseThrow();
    String originalOwner = ownerFor(original, claims);
    String replacementOwner = originalOwner.equals("owner-a") ? "owner-b" : "owner-a";

    // Poll through the normal lock path rather than relying on one scheduler-sensitive sleep.
    Optional<LlmExecutionOperationClaim> replacementResult = Optional.empty();
    for (int attempt = 0; attempt < 100 && replacementResult.isEmpty(); attempt++) {
      Thread.sleep(10);
      replacementResult =
          service.claimOrResume(reference, descriptor, replacementOwner, Duration.ofSeconds(30));
    }
    LlmExecutionOperationClaim replacement = replacementResult.orElseThrow();

    assertThat(replacement.attempt()).isEqualTo(2);
    assertThat(replacement.leaseToken()).isNotEqualTo(original.leaseToken());
    assertThat(service.heartbeat(original, originalOwner, Duration.ofSeconds(30))).isFalse();
    assertThat(service.complete(original, originalOwner)).isFalse();
    assertThat(service.complete(replacement, replacementOwner)).isTrue();
  }

  private Optional<LlmExecutionOperationClaim> claimAfterBarrier(
      LlmExecutionReference reference,
      LlmExecutionOperationDescriptor descriptor,
      String owner,
      CountDownLatch ready,
      CountDownLatch start)
      throws InterruptedException {
    ready.countDown();
    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
    return service.claimOrResume(reference, descriptor, owner, Duration.ofMillis(5));
  }

  private static String ownerFor(
      LlmExecutionOperationClaim original, List<Optional<LlmExecutionOperationClaim>> claims) {
    return claims.get(0).filter(original::equals).isPresent() ? "owner-a" : "owner-b";
  }

  private static String platformContextInsert() {
    return "INSERT INTO llm_execution_context("
        + "id,context_type,feature,source_type,source_id,correlation_id,scope_mode,isolation_key,"
        + "key_source_mode,allowed_providers_json,allowed_provider_models_json,"
        + "provider_key_sources_json,model_overrides_json,routing_mode,route_snapshot_version,"
        + "status,max_attempts,idempotency_key,request_fingerprint) VALUES (?,"
        + "'INTERNAL_TEST','test','INTERNAL','source','correlation','PLATFORM_INTERNAL',"
        + "'platform:internal','SYSTEM','[]'::jsonb,'{}'::jsonb,'{}'::jsonb,'{}'::jsonb,"
        + "'PROVIDER_PREFERRED',1,'QUEUED',1,'operation-concurrency-idem',repeat('e',64))";
  }
}

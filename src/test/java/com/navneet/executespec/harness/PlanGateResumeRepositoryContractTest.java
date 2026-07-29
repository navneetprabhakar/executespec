package com.navneet.executespec.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.navneet.executespec.harness.repository.PlanGateResumeOutboxRepository;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class PlanGateResumeRepositoryContractTest {

  @Test
  void claimUsesSkipLockedAndExcludesExhaustedRows() throws Exception {
    Query query =
        PlanGateResumeOutboxRepository.class
            .getMethod("findNextClaimableForUpdate", java.time.Instant.class)
            .getAnnotation(Query.class);

    assertThat(query.nativeQuery()).isTrue();
    assertThat(query.value()).containsIgnoringCase("FOR UPDATE SKIP LOCKED");
    assertThat(query.value()).contains("attempt_count < max_attempts");
  }

  @Test
  void heartbeatRequiresOwnerTokenAndUnexpiredLease() throws Exception {
    Query query =
        PlanGateResumeOutboxRepository.class
            .getMethod(
                "heartbeat",
                String.class,
                String.class,
                java.util.UUID.class,
                java.time.Instant.class,
                java.time.Instant.class)
            .getAnnotation(Query.class);

    assertThat(query.value()).contains("o.leaseOwner = :owner");
    assertThat(query.value()).contains("o.leaseToken = :token");
    assertThat(query.value()).contains("o.leaseExpiresAt > :now");
  }
}

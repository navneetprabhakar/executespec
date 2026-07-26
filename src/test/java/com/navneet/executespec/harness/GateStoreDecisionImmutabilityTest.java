package com.navneet.executespec.harness;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.navneet.executespec.harness.entity.GateRecord;
import com.navneet.executespec.harness.repository.GateRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class GateStoreDecisionImmutabilityTest {

  private static final String RUN_ID = "run-1";
  private static final String GATE_KEY = "gate:plan:" + RUN_ID;

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  private final GateRepository repository = mock(GateRepository.class);

  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> values = mock(ValueOperations.class);

  private GateStore store;

  @BeforeEach
  void setUp() {
    when(redis.opsForValue()).thenReturn(values);
    store = new GateStore(redis, repository, new SimpleMeterRegistry());
  }

  @Test
  void cancellationCannotRewriteAnApprovedDurableDecision() {
    GateRecord approved = resolved("APPROVED");
    when(repository.tryResolveIfPending(eq(GATE_KEY), eq("REJECTED"), any())).thenReturn(0);
    when(repository.findById(GATE_KEY)).thenReturn(Optional.of(approved));

    store.setPlanGate(RUN_ID, false);

    verify(repository, never()).save(any());
    verify(values).set(GATE_KEY, "1", Duration.ofHours(4));
  }

  @Test
  void broadApprovalCannotRewriteARejectedDurableDecision() {
    GateRecord rejected = resolved("REJECTED");
    when(repository.tryResolveIfPending(eq(GATE_KEY), eq("APPROVED"), any())).thenReturn(0);
    when(repository.findById(GATE_KEY)).thenReturn(Optional.of(rejected));

    store.setPlanGate(RUN_ID, true);

    verify(repository, never()).save(any());
    verify(values).set(GATE_KEY, "0", Duration.ofHours(4));
  }

  @Test
  void approvalCannotFabricateAMissingGateOrContinuation() {
    when(repository.tryResolveIfPending(eq(GATE_KEY), eq("APPROVED"), any())).thenReturn(0);
    when(repository.findById(GATE_KEY)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> store.claimPlanGate(RUN_ID, true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("never durably opened");

    verify(repository, never()).save(any());
  }

  private static GateRecord resolved(String status) {
    GateRecord record = GateRecord.pending(GATE_KEY, RUN_ID, "PLAN");
    record.setStatus(status);
    record.setResolvedAt(Instant.now());
    return record;
  }
}

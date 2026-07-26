package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * RR-1151 / RR-1152 — breaker keys carry a deployment namespace so a shared Redis cannot cross
 * environments, and success clears both keys in one atomic DEL.
 */
@ExtendWith(MockitoExtension.class)
class PlanStrategyCircuitBreakerTest {

  @Mock private StringRedisTemplate redis;
  @InjectMocks private PlanStrategyCircuitBreaker breaker;

  @Test
  void keysAreDeploymentNamespaced() {
    // RR-1151 — a per-environment namespace is prefixed onto both the fail-counter and open keys.
    ReflectionTestUtils.setField(breaker, "namespace", "prod");
    assertThat(breaker.failKey(PlanStrategy.LARGE))
        .isEqualTo("planner:strategy:fail:v2:prod:LARGE");
    assertThat(breaker.openKey(PlanStrategy.LARGE))
        .isEqualTo("planner:strategy:open:v2:prod:LARGE");

    ReflectionTestUtils.setField(breaker, "namespace", "dev");
    assertThat(breaker.failKey(PlanStrategy.LARGE)).isEqualTo("planner:strategy:fail:v2:dev:LARGE");
  }

  @Test
  @SuppressWarnings("unchecked")
  void recordSuccessDeletesBothNamespacedKeysAtomically() {
    // RR-1152 — a single DEL of both keys, so a success cannot leave a half-cleared state.
    ReflectionTestUtils.setField(breaker, "namespace", "dev");

    breaker.recordSuccess(PlanStrategy.MEDIUM);

    ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
    verify(redis).delete(keys.capture());
    assertThat(keys.getValue())
        .containsExactly(
            "planner:strategy:fail:v2:dev:MEDIUM", "planner:strategy:open:v2:dev:MEDIUM");
  }

  @Test
  void isOpenChecksNamespacedKey() {
    ReflectionTestUtils.setField(breaker, "namespace", "bench");
    when(redis.hasKey(anyString())).thenReturn(false);

    assertThat(breaker.degradeIfOpen(PlanStrategy.SMALL)).isEqualTo(PlanStrategy.SMALL);
    verify(redis).hasKey("planner:strategy:open:v2:bench:SMALL");
  }

  @Test
  void degradeWalksTheLadderWhenOpen() {
    // LARGE open → degrade to MEDIUM; MEDIUM healthy → stop there.
    ReflectionTestUtils.setField(breaker, "namespace", "default");
    when(redis.hasKey("planner:strategy:open:v2:default:LARGE")).thenReturn(true);
    when(redis.hasKey("planner:strategy:open:v2:default:MEDIUM")).thenReturn(false);

    assertThat(breaker.degradeIfOpen(PlanStrategy.LARGE)).isEqualTo(PlanStrategy.MEDIUM);
  }

  @Test
  void deterministicQualityRejectionsDoNotCountAsAvailabilityFailures() {
    Throwable expansionQualityFailure =
        new TaskExpanderAgent.ExpansionContractException(
            "T2", List.of("reservation lines are missing"));
    Throwable coherenceQualityFailure =
        new IllegalStateException(
            "hierarchical plan coherence validation failed",
            new IllegalStateException("assembled plan still violates executable contracts"));

    assertThat(PlanStrategyCircuitBreaker.countsTowardAvailabilityBreaker(expansionQualityFailure))
        .isFalse();
    assertThat(PlanStrategyCircuitBreaker.countsTowardAvailabilityBreaker(coherenceQualityFailure))
        .isFalse();
    assertThat(
            PlanStrategyCircuitBreaker.countsTowardAvailabilityBreaker(
                new IllegalStateException("provider connection timed out")))
        .isTrue();
  }
}

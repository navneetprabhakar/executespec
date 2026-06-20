package com.navneet.executespec.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Pins the fail-open + user/key-scoping contract of {@link RunIdempotencyService} (RL-2026-06-005).
 */
@ExtendWith(MockitoExtension.class)
class RunIdempotencyServiceTest {

  @Mock StringRedisTemplate redis;
  @Mock ValueOperations<String, String> ops;

  @Test
  void failsOpenWhenRedisAbsent() {
    RunIdempotencyService svc = new RunIdempotencyService((StringRedisTemplate) null);
    assertThat(svc.lookup("u1", "k1")).isEmpty();
    assertThatCode(() -> svc.record("u1", "k1", "run-1")).doesNotThrowAnyException();
  }

  @Test
  void lookupReturnsStoredRunIdScopedByUserAndKey() {
    RunIdempotencyService svc = withRedis();
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.get("run:idem:u1:k1")).thenReturn("run-1");
    assertThat(svc.lookup("u1", "k1")).contains("run-1");
  }

  @Test
  void lookupEmptyOnMiss() {
    RunIdempotencyService svc = withRedis();
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.get("run:idem:u1:k1")).thenReturn(null);
    assertThat(svc.lookup("u1", "k1")).isEmpty();
  }

  @Test
  void recordSetsScopedKeyWithTtl() {
    RunIdempotencyService svc = withRedis();
    when(redis.opsForValue()).thenReturn(ops);
    svc.record("u1", "k1", "run-1");
    verify(ops).set("run:idem:u1:k1", "run-1", Duration.ofHours(24));
  }

  @Test
  void blankInputsNeverTouchRedis() {
    RunIdempotencyService svc = withRedis();
    assertThat(svc.lookup("u1", "  ")).isEmpty();
    assertThat(svc.lookup(null, "k1")).isEmpty();
    svc.record("u1", "", "run-1");
    svc.record(null, "k1", "run-1");
    verify(redis, never()).opsForValue();
  }

  @Test
  void lookupFailsOpenOnRedisError() {
    RunIdempotencyService svc = withRedis();
    when(redis.opsForValue()).thenThrow(new RuntimeException("redis down"));
    assertThat(svc.lookup("u1", "k1")).isEmpty(); // swallowed → empty, never propagates
  }

  private RunIdempotencyService withRedis() {
    return new RunIdempotencyService(redis);
  }
}

package com.navneet.executespec.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Unit test for {@link RunLocalExecutionFlag} — the P3 local-hands per-run marker (RL-2026-06-014).
 * Pure Mockito over a mocked {@link StringRedisTemplate}; mirrors the {@link RunSkipPushFlag}
 * contract it was cloned from (mark sets a TTL'd key, consume is read-and-delete, null/blank-safe).
 */
class RunLocalExecutionFlagTest {

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> ops = mock(ValueOperations.class);

  private final RunLocalExecutionFlag flag = new RunLocalExecutionFlag(redis);

  @Test
  void mark_setsKeyWithTtl() {
    when(redis.opsForValue()).thenReturn(ops);
    flag.mark("run-1");
    verify(ops).set(eq("run:local-exec:run-1"), eq("1"), any(Duration.class));
  }

  @Test
  void mark_noopsOnNullOrBlank() {
    flag.mark(null);
    flag.mark("  ");
    verify(redis, never()).opsForValue();
  }

  @Test
  void consume_returnsTrueAndDeletesWhenSet() {
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.getAndDelete("run:local-exec:run-2")).thenReturn("1");
    assertThat(flag.consume("run-2")).isTrue();
    verify(ops).getAndDelete("run:local-exec:run-2");
  }

  @Test
  void consume_returnsFalseWhenAbsent() {
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.getAndDelete(anyString())).thenReturn(null);
    assertThat(flag.consume("run-3")).isFalse();
  }

  @Test
  void consume_noopsFalseOnNullOrBlank() {
    assertThat(flag.consume(null)).isFalse();
    assertThat(flag.consume("")).isFalse();
    verify(redis, never()).opsForValue();
  }
}

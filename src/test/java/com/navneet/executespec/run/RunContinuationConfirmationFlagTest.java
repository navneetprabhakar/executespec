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

class RunContinuationConfirmationFlagTest {

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> ops = mock(ValueOperations.class);

  private final RunContinuationConfirmationFlag flag = new RunContinuationConfirmationFlag(redis);

  @Test
  void mark_setsKeyWithTtl() {
    when(redis.opsForValue()).thenReturn(ops);
    flag.mark("run-1");
    verify(ops).set(eq("run:continuation-confirm:run-1"), eq("1"), any(Duration.class));
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
    when(ops.getAndDelete("run:continuation-confirm:run-2")).thenReturn("1");
    assertThat(flag.consume("run-2")).isTrue();
    verify(ops).getAndDelete("run:continuation-confirm:run-2");
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

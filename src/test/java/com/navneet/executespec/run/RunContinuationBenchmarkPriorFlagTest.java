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

class RunContinuationBenchmarkPriorFlagTest {

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> ops = mock(ValueOperations.class);

  private final RunContinuationBenchmarkPriorFlag flag =
      new RunContinuationBenchmarkPriorFlag(redis);

  @Test
  void markTrue_setsOneWithTtl() {
    when(redis.opsForValue()).thenReturn(ops);
    flag.mark("run-1", true);
    verify(ops).set(eq("run:continuation-benchmark-prior:run-1"), eq("1"), any(Duration.class));
  }

  @Test
  void markFalse_setsZeroWithTtl() {
    when(redis.opsForValue()).thenReturn(ops);
    flag.mark("run-2", false);
    verify(ops).set(eq("run:continuation-benchmark-prior:run-2"), eq("0"), any(Duration.class));
  }

  @Test
  void mark_noopsOnNullOrBlank() {
    flag.mark(null, false);
    flag.mark("  ", true);
    verify(redis, never()).opsForValue();
  }

  @Test
  void consume_returnsBooleanAndDeletesWhenSet() {
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.getAndDelete("run:continuation-benchmark-prior:run-3")).thenReturn("0");
    assertThat(flag.consume("run-3")).contains(false);
    verify(ops).getAndDelete("run:continuation-benchmark-prior:run-3");
  }

  @Test
  void consume_returnsEmptyWhenAbsentOrMalformed() {
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.getAndDelete(anyString())).thenReturn("bogus");
    assertThat(flag.consume("run-4")).isEmpty();
  }

  @Test
  void consume_noopsEmptyOnNullOrBlank() {
    assertThat(flag.consume(null)).isEmpty();
    assertThat(flag.consume("")).isEmpty();
    verify(redis, never()).opsForValue();
  }
}

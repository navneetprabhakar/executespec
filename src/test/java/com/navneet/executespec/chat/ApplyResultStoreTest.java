package com.navneet.executespec.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Pins the apply lifecycle Redis store. Round-trip semantics + TTL handoff to {@link
 * StringRedisTemplate#opsForValue()} are the only invariants worth pinning here — the record itself
 * is plain Jackson serialisation.
 *
 * <p>Why this exists: run a076c876 (2026-05-03) showed the apply-suggestion outcome could die in
 * the void when the response stream broke at the 3-second mark. The store + companion polling
 * endpoint give the UI a way to re-attach. These tests guard the contract:
 *
 * <ul>
 *   <li>{@link ApplyResultStore#put} writes JSON keyed on {@code v2:apply:result:&lt;runId&gt;}
 *       with the canonical 1h TTL
 *   <li>{@link ApplyResultStore#get} round-trips the record verbatim
 *   <li>state transitions ({@code running → toCompleted}, {@code running → toFailed}) preserve run
 *       id + task id + start timestamp
 * </ul>
 */
class ApplyResultStoreTest {

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> ops = mock(ValueOperations.class);

  private final ApplyResultStore store = new ApplyResultStore(redis);

  // Tiny in-memory backing for ops.set / ops.get so round-trips work without a real Redis.
  private final Map<String, String> backing = new HashMap<>();

  ApplyResultStoreTest() {
    when(redis.opsForValue()).thenReturn(ops);
    org.mockito.Mockito.lenient()
        .when(ops.get(anyString()))
        .thenAnswer(inv -> backing.get(inv.<String>getArgument(0)));
    org.mockito.Mockito.lenient()
        .doAnswer(
            inv -> {
              backing.put(inv.getArgument(0), inv.getArgument(1));
              return null;
            })
        .when(ops)
        .set(anyString(), anyString(), any(Duration.class));
  }

  @Test
  void put_then_get_roundtrips_running_state() {
    ApplyResultStore.ApplyState s =
        ApplyResultStore.ApplyState.running("run-1", "APPLY-42", "copilot", "claude-sonnet-4-6");
    store.put(s);

    var loaded = store.get("run-1");

    assertThat(loaded).isPresent();
    assertThat(loaded.get().runId()).isEqualTo("run-1");
    assertThat(loaded.get().taskId()).isEqualTo("APPLY-42");
    assertThat(loaded.get().status()).isEqualTo("RUNNING");
    assertThat(loaded.get().model()).isEqualTo("claude-sonnet-4-6");
    assertThat(loaded.get().startedAt()).isNotBlank();
    assertThat(loaded.get().completedAt()).isNull();
  }

  @Test
  void put_uses_namespaced_key_and_one_hour_ttl() {
    ApplyResultStore.ApplyState s =
        ApplyResultStore.ApplyState.running("abc-123", "APPLY-1", "review", "haiku");
    store.put(s);

    verify(ops).set(eq("v2:apply:result:abc-123"), anyString(), eq(Duration.ofHours(1)));
  }

  @Test
  void completed_transition_preserves_run_and_task_ids_and_start_time() {
    ApplyResultStore.ApplyState pending =
        ApplyResultStore.ApplyState.running("r1", "APPLY-7", "copilot", "sonnet");
    ApplyResultStore.ApplyState done =
        pending.toCompleted("ITERATION_LIMIT_EXCEEDED", "summary text", 25, 34);

    assertThat(done.runId()).isEqualTo(pending.runId());
    assertThat(done.taskId()).isEqualTo(pending.taskId());
    assertThat(done.startedAt()).isEqualTo(pending.startedAt());
    assertThat(done.status()).isEqualTo("COMPLETED");
    assertThat(done.outcome()).isEqualTo("ITERATION_LIMIT_EXCEEDED");
    assertThat(done.iterations()).isEqualTo(25);
    assertThat(done.toolCalls()).isEqualTo(34);
    assertThat(done.completedAt()).isNotBlank();
    assertThat(done.error()).isNull();
  }

  @Test
  void failed_transition_records_error_message() {
    ApplyResultStore.ApplyState pending =
        ApplyResultStore.ApplyState.running("r1", "APPLY-7", "copilot", "sonnet");
    ApplyResultStore.ApplyState failed = pending.toFailed("RuntimeException: connection refused");

    assertThat(failed.status()).isEqualTo("FAILED");
    assertThat(failed.error()).contains("connection refused");
    assertThat(failed.outcome()).isNull();
    assertThat(failed.completedAt()).isNotBlank();
    assertThat(failed.startedAt()).isEqualTo(pending.startedAt());
  }

  @Test
  void get_returns_empty_when_nothing_in_redis() {
    var loaded = store.get("never-written");
    assertThat(loaded).isEmpty();
  }

  @Test
  void put_swallows_exceptions_so_apply_path_never_fails_on_a_redis_blip() {
    when(redis.opsForValue()).thenThrow(new RuntimeException("redis down"));
    // Should not propagate.
    store.put(ApplyResultStore.ApplyState.running("r1", "T", "src", "model"));
  }

  @Test
  void get_returns_empty_when_redis_throws() {
    when(redis.opsForValue()).thenThrow(new RuntimeException("redis down"));
    assertThat(store.get("any")).isEmpty();
  }
}

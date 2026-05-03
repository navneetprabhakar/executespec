package com.navneet.executespec.v2.chat;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis-backed store for apply-suggestion lifecycle state. Lets the UI poll the result of an apply
 * even after the original POST response stream was severed (broken pipe, browser navigation, etc.)
 *
 * <p>Diagnosed from run {@code a076c876} (2026-05-03): the apply POST handler's response stream
 * died at the 3-second mark when Monaco's DiffEditor disposal race detonated the React subtree. The
 * apply itself continued running on its async executor for ~57 seconds and ultimately hit
 * ITERATION_LIMIT_EXCEEDED — but with the response stream gone, the user had no way to learn the
 * outcome. This store lets the UI re-attach via {@code GET /api/runs/{id}/apply/status} and read
 * the latest state regardless of when the original POST disconnected.
 *
 * <p>Storage: Redis key {@code v2:apply:result:<runId>}, value is the JSON-serialised {@link
 * ApplyState} record, TTL is 1 hour. Apply runs themselves are bounded ~2-3 minutes; the 1h TTL
 * gives the user comfortable headroom to navigate back, refresh, or re-open the run page and see
 * the result.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ApplyResultStore {

  /** TTL on the result key. Long enough for the user to navigate back and see the outcome. */
  public static final Duration RESULT_TTL = Duration.ofHours(1);

  private static final String KEY_PREFIX = "v2:apply:result:";

  private final StringRedisTemplate redis;

  // Local mapper — the existing Jackson conventions in this package use kebab-cased records for
  // some payloads; for the polling JSON we want plain camelCase to match the rest of the API.
  private static final ObjectMapper MAPPER =
      new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE);

  /**
   * Lifecycle state of a single apply invocation. {@code outcome} mirrors {@code
   * WorkerResult.Outcome}; {@code error} is non-null only when the worker / dispatch threw.
   */
  public record ApplyState(
      String runId,
      String taskId,
      String source,
      String status,
      String outcome,
      String summary,
      String error,
      String model,
      Integer iterations,
      Integer toolCalls,
      String startedAt,
      String completedAt) {

    public static ApplyState running(String runId, String taskId, String source, String model) {
      return new ApplyState(
          runId,
          taskId,
          source,
          "RUNNING",
          null,
          null,
          null,
          model,
          null,
          null,
          Instant.now().toString(),
          null);
    }

    public ApplyState toCompleted(String outcome, String summary, int iterations, int toolCalls) {
      return new ApplyState(
          this.runId,
          this.taskId,
          this.source,
          "COMPLETED",
          outcome,
          summary,
          null,
          this.model,
          iterations,
          toolCalls,
          this.startedAt,
          Instant.now().toString());
    }

    public ApplyState toFailed(String error) {
      return new ApplyState(
          this.runId,
          this.taskId,
          this.source,
          "FAILED",
          null,
          null,
          error,
          this.model,
          null,
          null,
          this.startedAt,
          Instant.now().toString());
    }
  }

  /** Write the current apply state to Redis with the standard TTL. Best-effort; never throws. */
  public void put(ApplyState state) {
    if (state == null || state.runId() == null) return;
    String key = KEY_PREFIX + state.runId();
    try {
      String json = MAPPER.writeValueAsString(state);
      redis.opsForValue().set(key, json, RESULT_TTL);
    } catch (Exception e) {
      log.warn("ApplyResultStore put failed for run={}: {}", state.runId(), e.getMessage());
    }
  }

  /** Read the current apply state for a run, or empty when missing/expired. */
  public Optional<ApplyState> get(String runId) {
    if (runId == null || runId.isBlank()) return Optional.empty();
    String key = KEY_PREFIX + runId;
    try {
      String json = redis.opsForValue().get(key);
      if (json == null) return Optional.empty();
      return Optional.of(MAPPER.readValue(json, ApplyState.class));
    } catch (Exception e) {
      log.warn("ApplyResultStore get failed for run={}: {}", runId, e.getMessage());
      return Optional.empty();
    }
  }

  /** Test-only: clear the entry. Used by the controller's "kick off a new apply" path. */
  public void delete(String runId) {
    if (runId == null) return;
    try {
      redis.delete(KEY_PREFIX + runId);
    } catch (Exception e) {
      log.warn("ApplyResultStore delete failed for run={}: {}", runId, e.getMessage());
    }
  }
}

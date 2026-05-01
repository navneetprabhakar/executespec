package com.navneet.executespec.router;

import java.time.Duration;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis-backed store that pins a run to the provider that served its first LLM call.
 *
 * <p>All tasks within the same run use the same provider for style consistency. The pin is advisory
 * — if the pinned provider becomes unhealthy, {@link LlmRouter} falls back normally and the pin is
 * cleared so the new provider is used going forward.
 *
 * <h3>Redis key</h3>
 *
 * <pre>run:provider:pin:{runId} → "ANTHROPIC" | "OPENAI" | "GOOGLE"  (TTL = 2h)</pre>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RunProviderPinStore {

  private static final String PIN_KEY_PREFIX = "run:provider:pin:";
  private static final Duration PIN_TTL = Duration.ofHours(2);

  private final StringRedisTemplate redis;

  /**
   * Pins this run to the given provider if not already pinned. No-op if a pin already exists
   * (first-write-wins).
   */
  public void pinIfAbsent(String runId, String provider) {
    if (runId == null || provider == null) return;
    try {
      // SET NX — only sets if key doesn't already exist
      Boolean set = redis.opsForValue().setIfAbsent(pinKey(runId), provider, PIN_TTL);
      if (Boolean.TRUE.equals(set)) {
        log.debug("[PinStore] Run {} pinned to provider {}", runId, provider);
      }
    } catch (Exception e) {
      log.debug("[PinStore] Redis error pinning run {} to {}: {}", runId, provider, e.getMessage());
    }
  }

  /** Returns the pinned provider for this run, if one exists. */
  public Optional<String> getPin(String runId) {
    if (runId == null) return Optional.empty();
    try {
      return Optional.ofNullable(redis.opsForValue().get(pinKey(runId)));
    } catch (Exception e) {
      log.debug("[PinStore] Redis error reading pin for run {}: {}", runId, e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Clears the pin — called when the pinned provider is unhealthy and a switch occurs. The next
   * successful call will re-pin to the new provider.
   */
  public void clearPin(String runId) {
    if (runId == null) return;
    try {
      redis.delete(pinKey(runId));
      log.debug("[PinStore] Pin cleared for run {}", runId);
    } catch (Exception e) {
      log.debug("[PinStore] Redis error clearing pin for run {}: {}", runId, e.getMessage());
    }
  }

  private static String pinKey(String runId) {
    return PIN_KEY_PREFIX + runId;
  }
}

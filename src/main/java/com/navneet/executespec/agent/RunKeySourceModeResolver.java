package com.navneet.executespec.agent;

import java.time.Duration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Durable, cache-independent answer to "is this run a pure-BYOK (OWN_KEYS) run?" — used to fail the
 * BYOK boundary CLOSED at dispatch time (BYOK deep-dive §7 #2, 2026-07-17).
 *
 * <p>The per-run resolved-key caches ({@code RunKeyContextStore} / {@code RunKeyStore}) are
 * best-effort in-memory/Redis stores. On eviction, Redis outage, cross-JVM resume, or a background
 * job they miss, and the router previously treated a miss as {@code PLATFORM} — silently spending
 * on platform credentials for an Enterprise customer. The run's key-source <b>mode</b>, by
 * contrast, is derived from the org row ({@code Org.keySourceMode}) via the run's persisted {@code
 * createdByUserId}, so it survives every cache failure. The router consults this ONLY at the moment
 * it would otherwise fall back to platform keys, so the extra lookup is off the hot path; results
 * are bounded-cached to keep even that rare path cheap.
 */
@Service
@RequiredArgsConstructor
public class RunKeySourceModeResolver {

  private final PipelineRunRepository runRepo;
  private final ApiKeyResolver apiKeyResolver;

  private final Cache<String, Boolean> ownKeysByRun =
      Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(Duration.ofMinutes(30)).build();

  /**
   * True iff the run belongs to an org whose {@code keySourceMode == OWN_KEYS}. Fail-safe: an
   * unknown/missing run or user returns {@code false} (treated as platform-capable), because the
   * hard OWN_KEYS guarantee is anchored on a real org policy — we never invent BYOK for a run we
   * cannot attribute.
   */
  public boolean isOwnKeysRun(String runId) {
    if (runId == null || runId.isBlank()) return false;
    return Boolean.TRUE.equals(
        ownKeysByRun.get(
            runId,
            id -> {
              PipelineRun run = runRepo.findById(id).orElse(null);
              if (run == null || run.getCreatedByUserId() == null) return false;
              return apiKeyResolver.isOwnKeysMode(run.getCreatedByUserId());
            }));
  }

  /** Drop a run's cached mode (e.g. on terminal cleanup). */
  public void evict(String runId) {
    if (runId != null) ownKeysByRun.invalidate(runId);
  }
}

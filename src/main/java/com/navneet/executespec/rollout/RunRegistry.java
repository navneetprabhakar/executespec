package com.navneet.executespec.rollout;

import java.time.Duration;
import java.util.Optional;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import org.springframework.stereotype.Component;

/**
 * In-memory registry of V2 runs.
 *
 * <p>Uses a Caffeine cache with 24h TTL so completed runs don't accumulate forever but remain
 * pollable for long enough for any UI to display them. 10k-entry hard cap (a production service
 * won't hit this — V2 runs take minutes each — but it bounds memory in case something goes wrong).
 *
 * <p>Phase 9 baseline: this is deliberately not DB-backed. Persisting V2 runs to the {@code run}
 * table unlocks crash recovery (re-associating an in-progress run with a saved context snapshot)
 * but requires more plumbing than Phase 9 warrants. Defer until Phase 9.x when we integrate V2 with
 * V1's Run entity + StartupRunResumer.
 */
@Component
public class RunRegistry {

  private final Cache<String, Run> runs =
      Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(Duration.ofHours(24)).build();

  public void put(Run run) {
    runs.put(run.runId(), run);
  }

  public Optional<Run> get(String runId) {
    return Optional.ofNullable(runs.getIfPresent(runId));
  }

  public long size() {
    return runs.estimatedSize();
  }
}

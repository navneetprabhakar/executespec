package com.navneet.executespec.router;

import java.time.Duration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/** Durable provider/model contract for every run-scoped dispatch. */
@Service
@RequiredArgsConstructor
public class RunRoutePolicyService {

  private final PipelineRunRepository runRepository;
  private final Cache<String, RoutePolicy> cache =
      Caffeine.newBuilder().maximumSize(100_000).expireAfterAccess(Duration.ofHours(1)).build();

  public RoutePolicy forRun(String runId) {
    if (runId == null || runId.isBlank()) return RoutePolicy.unlocked();
    RoutePolicy cached = cache.getIfPresent(runId);
    if (cached != null) return cached;
    var run = runRepository.findById(runId).orElse(null);
    if (run == null) return RoutePolicy.unlocked();
    RoutePolicy loaded =
        new RoutePolicy(
            run.getRequestedProvider(),
            run.getRequestedModel(),
            run.getRoutingMode(),
            run.isRoutePinned());
    cache.put(runId, loaded);
    return loaded;
  }

  public record RoutePolicy(
      String requestedProvider, String requestedModel, String routingMode, boolean exactLock) {
    static RoutePolicy unlocked() {
      return new RoutePolicy(null, null, "PROVIDER_PREFERRED", false);
    }
  }
}

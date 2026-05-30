package com.navneet.executespec.router;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Tracks the operational status of each LLM provider by combining:
 *
 * <ol>
 *   <li>Internal circuit-breaker state ({@link ProviderHealthTracker})
 *   <li>Optional external status-page polling (advisory, non-blocking)
 * </ol>
 *
 * <p>Polled every 60 seconds. Results cached in Redis so all instances share the same view (key:
 * {@code provider:status:{name}}, TTL 90s).
 *
 * <p>A provider is considered "available" only if its circuit breaker is healthy. The external
 * status page gives early warning but does not independently open the circuit (that's {@link
 * ProviderHealthTracker}'s responsibility).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProviderStatusService {

  private static final Duration POLL_TIMEOUT = Duration.ofSeconds(8);

  /** Failover order per primary provider — mirrors application.yml failover-order. */
  private static final Map<LlmProvider, List<LlmProvider>> FAILOVER_ORDER =
      Map.of(
          LlmProvider.ANTHROPIC, List.of(LlmProvider.OPENAI, LlmProvider.GEMINI),
          LlmProvider.OPENAI, List.of(LlmProvider.GEMINI, LlmProvider.ANTHROPIC),
          LlmProvider.GEMINI, List.of(LlmProvider.OPENAI, LlmProvider.ANTHROPIC));

  private static final Map<LlmProvider, String> STATUS_PAGES =
      Map.of(
          LlmProvider.ANTHROPIC, "https://status.anthropic.com/api/v2/status.json",
          LlmProvider.OPENAI, "https://status.openai.com/api/v2/status.json",
          LlmProvider.GEMINI, "https://status.cloud.google.com/incidents.json");

  private final ProviderHealthTracker healthTracker;
  private final StringRedisTemplate redis;
  private final MeterRegistry meterRegistry;

  private final AtomicInteger queuedDueToOutage = new AtomicInteger(0);

  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(POLL_TIMEOUT).build();

  @jakarta.annotation.PostConstruct
  void registerGauges() {
    Gauge.builder("executespec.provider.outage.queued_runs", queuedDueToOutage, AtomicInteger::get)
        .description("Runs currently queued because all LLM providers are unavailable")
        .register(meterRegistry);
  }

  /**
   * Returns true if at least one provider in the failover chain (primary + fallbacks) has a healthy
   * circuit breaker, meaning a run can proceed.
   *
   * @param requestedProvider the user-selected provider for the run
   * @param availableProviders the set of providers for which API keys exist
   */
  public boolean isAnyProviderHealthy(
      LlmProvider requestedProvider, Set<LlmProvider> availableProviders) {
    // Check primary provider first
    if (availableProviders.contains(requestedProvider)
        && healthTracker.isHealthy(requestedProvider)) {
      return true;
    }
    // Check failover chain
    List<LlmProvider> fallbacks = FAILOVER_ORDER.getOrDefault(requestedProvider, List.of());
    for (LlmProvider fallback : fallbacks) {
      if (availableProviders.contains(fallback) && healthTracker.isHealthy(fallback)) {
        return true;
      }
    }
    return false;
  }

  /** Increment the outage-queued counter (call when a run is queued due to provider outage). */
  public void recordQueuedDueToOutage() {
    queuedDueToOutage.incrementAndGet();
  }

  /** Decrement the outage-queued counter (call when a queued run is retried or cancelled). */
  public void recordOutageQueueReleased() {
    queuedDueToOutage.decrementAndGet();
  }

  /**
   * Polls external status pages every 60 seconds. Failures are logged but never block pipeline
   * execution — the circuit breaker is the authoritative health source.
   */
  @Scheduled(fixedDelay = 60_000)
  public void pollStatusPages() {
    for (Map.Entry<LlmProvider, String> entry : STATUS_PAGES.entrySet()) {
      LlmProvider provider = entry.getKey();
      String url = entry.getValue();
      try {
        HttpRequest req =
            HttpRequest.newBuilder().uri(URI.create(url)).timeout(POLL_TIMEOUT).GET().build();
        HttpResponse<Void> resp = HTTP.send(req, HttpResponse.BodyHandlers.discarding());
        boolean reachable = resp.statusCode() < 500;
        String statusKey = "provider:status:" + provider.name();
        redis.opsForValue().set(statusKey, reachable ? "UP" : "DEGRADED", Duration.ofSeconds(90));
        if (!reachable) {
          log.warn(
              "[ProviderStatus] {} status page returned {} — possible outage",
              provider,
              resp.statusCode());
        }
      } catch (Exception e) {
        // Don't propagate — status page may be behind a firewall in some envs
        log.debug("[ProviderStatus] Could not reach {} status page: {}", provider, e.getMessage());
      }
    }
  }
}

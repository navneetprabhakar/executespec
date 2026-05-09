package com.navneet.executespec.config.featureflag;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class FeatureFlagService implements MessageListener {

  private static final String REDIS_CHANNEL = "executespec:feature-flag-update";

  private final FeatureFlagRepository repository;
  private final StringRedisTemplate redisTemplate;
  private final RedisMessageListenerContainer redisMessageListenerContainer;
  // B-13 fix (2026-05-09) — terminate active SSE streams on force-logout.
  // ObjectProvider so Spring doesn't tightly couple boot order between
  // featureflag and sse packages.
  private final org.springframework.beans.factory.ObjectProvider<
          com.navneet.executespec.sse.SseBroadcaster>
      sseBroadcasterProvider;

  private final Map<String, String> cache = new ConcurrentHashMap<>();

  @PostConstruct
  void init() {
    reloadAll();
    // Subscribe to Redis pub/sub for cross-instance cache invalidation
    redisMessageListenerContainer.addMessageListener(this, new ChannelTopic(REDIS_CHANNEL));
    log.info("FeatureFlagService initialized — {} flags loaded", cache.size());
  }

  // ── Public read API ──────────────────────────────────────────────────────

  public boolean isEnabled(String key) {
    String val = cache.get(key);
    return "true".equalsIgnoreCase(val);
  }

  public String getString(String key, String defaultValue) {
    return cache.getOrDefault(key, defaultValue);
  }

  public int getInt(String key, int defaultValue) {
    String val = cache.get(key);
    if (val == null) return defaultValue;
    try {
      return Integer.parseInt(val);
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  // ── Write API (admin) ────────────────────────────────────────────────────

  @Transactional
  public FeatureFlag update(String key, String value, String userId) {
    FeatureFlag flag =
        repository
            .findByFlagKey(key)
            .orElseThrow(() -> new IllegalArgumentException("Unknown feature flag: " + key));
    flag.setFlagValue(value);
    flag.setUpdatedBy(userId);
    flag.setUpdatedAt(Instant.now());
    FeatureFlag saved = repository.save(flag);

    // Update local cache immediately
    cache.put(key, value);
    // Publish to Redis so other instances pick it up
    redisTemplate.convertAndSend(REDIS_CHANNEL, key);

    // When force_logout is enabled, stamp the epoch so all existing JWTs are invalidated
    if ("force_logout".equals(key) && "true".equalsIgnoreCase(value)) {
      String epoch = Instant.now().toString();
      upsertFlag("force_logout_epoch", epoch, "Force logout timestamp", userId);
      log.info("Force logout activated — all JWTs issued before {} are now invalid", epoch);
      // B-13 fix (2026-05-09) — JWT-epoch check fires only on the next read of the
      // token, but EventSource is server→client (no client-originated reads), so
      // active SSE streams kept delivering events for up to 5 min after force-logout.
      // Terminate every emitter on this instance now. Cross-instance: each instance
      // independently runs this branch when it picks up the flag toggle via Redis
      // pub/sub (handled by the existing FeatureFlagService listener).
      try {
        var broadcaster = sseBroadcasterProvider.getIfAvailable();
        if (broadcaster != null) {
          broadcaster.completeAll();
        }
      } catch (Exception e) {
        log.warn("[force-logout] SseBroadcaster.completeAll failed: {}", e.getMessage());
      }
    }

    log.info("Feature flag updated: {} = {} (by userId={})", key, value, userId);
    return saved;
  }

  private void upsertFlag(String key, String value, String description, String userId) {
    FeatureFlag flag =
        repository
            .findByFlagKey(key)
            .orElse(
                FeatureFlag.builder()
                    .flagKey(key)
                    .valueType(FeatureFlag.ValueType.STRING)
                    .scope(FeatureFlag.Scope.PLATFORM)
                    .description(description)
                    .build());
    flag.setFlagValue(value);
    flag.setUpdatedBy(userId);
    flag.setUpdatedAt(Instant.now());
    repository.save(flag);
    cache.put(key, value);
    redisTemplate.convertAndSend(REDIS_CHANNEL, key);
  }

  public List<FeatureFlag> listAll() {
    return repository.findAll();
  }

  // ── Redis subscriber ─────────────────────────────────────────────────────

  @Override
  public void onMessage(Message message, byte[] pattern) {
    String flagKey = new String(message.getBody(), StandardCharsets.UTF_8);
    log.debug("Redis feature-flag-update received for key: {}", flagKey);
    // Reload just this key from DB
    repository.findByFlagKey(flagKey).ifPresent(f -> cache.put(f.getFlagKey(), f.getFlagValue()));
    // B-13 fix (2026-05-09) — cross-instance force-logout SSE termination. The
    // upsertFlag(...) path on the originating instance closes its own emitters
    // immediately; sibling instances learn about the flip only through this Redis
    // pub/sub handler, so they need to mirror the closeAll() here.
    if ("force_logout".equals(flagKey) && "true".equalsIgnoreCase(cache.get(flagKey))) {
      try {
        var broadcaster = sseBroadcasterProvider.getIfAvailable();
        if (broadcaster != null) {
          broadcaster.completeAll();
        }
      } catch (Exception e) {
        log.warn("[force-logout/cross-instance] SseBroadcaster.completeAll failed: {}", e.getMessage());
      }
    }
  }

  // ── Scheduled full reload fallback ───────────────────────────────────────

  @Scheduled(fixedRate = 60_000)
  void scheduledReload() {
    reloadAll();
  }

  private void reloadAll() {
    try {
      List<FeatureFlag> all = repository.findAll();
      cache.clear();
      for (FeatureFlag f : all) {
        cache.put(f.getFlagKey(), f.getFlagValue());
      }
    } catch (Exception e) {
      log.warn("Failed to reload feature flags from DB", e);
    }
  }
}

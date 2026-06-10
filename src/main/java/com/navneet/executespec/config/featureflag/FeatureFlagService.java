package com.navneet.executespec.config.featureflag;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

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

  private final AtomicReference<Map<String, String>> cache = new AtomicReference<>(Map.of());

  @PostConstruct
  void init() {
    reloadAll();
    // Subscribe to Redis pub/sub for cross-instance cache invalidation
    redisMessageListenerContainer.addMessageListener(this, new ChannelTopic(REDIS_CHANNEL));
    log.info("FeatureFlagService initialized — {} flags loaded", cache.get().size());
  }

  public boolean isEnabled(String key) {
    String val = cache.get().get(key);
    return "true".equalsIgnoreCase(val);
  }

  public String getString(String key, String defaultValue) {
    return cache.get().getOrDefault(key, defaultValue);
  }

  public int getInt(String key, int defaultValue) {
    String val = cache.get().get(key);
    if (val == null) return defaultValue;
    try {
      return Integer.parseInt(val);
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  // Write API (admin)

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
    putCacheValue(key, value);
    // Publish to Redis so other instances pick it up
    redisTemplate.convertAndSend(REDIS_CHANNEL, key);

    // When force_logout is enabled, stamp the epoch so all existing JWTs are invalidated
    if ("force_logout".equals(key) && "true".equalsIgnoreCase(value)) {
      String epoch = Instant.now().toString();
      upsertFlag("force_logout_epoch", epoch, "Force logout timestamp", userId);
      log.info("Force logout activated — all JWTs issued before {} are now invalid", epoch);
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
    putCacheValue(key, value);
    redisTemplate.convertAndSend(REDIS_CHANNEL, key);
  }

  public List<FeatureFlag> listAll() {
    return repository.findAll();
  }

  @Override
  public void onMessage(Message message, byte[] pattern) {
    String flagKey = new String(message.getBody(), StandardCharsets.UTF_8);
    log.debug("Redis feature-flag-update received for key: {}", flagKey);
    // Reload just this key from DB
    repository
        .findByFlagKey(flagKey)
        .ifPresent(f -> putCacheValue(f.getFlagKey(), f.getFlagValue()));
  }

  @Scheduled(fixedRate = 60_000)
  void scheduledReload() {
    reloadAll();
  }

  private void reloadAll() {
    try {
      List<FeatureFlag> all = repository.findAll();
      cache.set(
          all.stream()
              .collect(
                  Collectors.toUnmodifiableMap(
                      FeatureFlag::getFlagKey, FeatureFlag::getFlagValue, (a, b) -> b)));
    } catch (Exception e) {
      log.warn("Failed to reload feature flags from DB", e);
    }
  }

  private void putCacheValue(String key, String value) {
    cache.updateAndGet(
        current -> {
          java.util.Map<String, String> next = new java.util.HashMap<>(current);
          next.put(key, value);
          return Map.copyOf(next);
        });
  }
}

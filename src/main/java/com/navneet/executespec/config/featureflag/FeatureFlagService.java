package com.navneet.executespec.config.featureflag;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class FeatureFlagService implements MessageListener {

    private static final String REDIS_CHANNEL = "executespec:feature-flag-update";

    private final FeatureFlagRepository repository;
    private final StringRedisTemplate redisTemplate;
    private final RedisMessageListenerContainer redisMessageListenerContainer;

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
        FeatureFlag flag = repository.findByFlagKey(key)
                .orElseThrow(() -> new IllegalArgumentException("Unknown feature flag: " + key));
        flag.setFlagValue(value);
        flag.setUpdatedBy(userId);
        flag.setUpdatedAt(Instant.now());
        FeatureFlag saved = repository.save(flag);

        // Update local cache immediately
        cache.put(key, value);
        // Publish to Redis so other instances pick it up
        redisTemplate.convertAndSend(REDIS_CHANNEL, key);

        log.info("Feature flag updated: {} = {} (by userId={})", key, value, userId);
        return saved;
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

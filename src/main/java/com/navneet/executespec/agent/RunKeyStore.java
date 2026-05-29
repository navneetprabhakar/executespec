package com.navneet.executespec.agent;

import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.navneet.executespec.security.KeyEncryptor;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Holds the per-run resolved API keys for the lifetime of each pipeline run.
 *
 * <p>Two-layer store:
 *
 * <ol>
 *   <li><b>L1 — Caffeine</b>: same-instance hot path; avoids Redis roundtrip per LLM call.
 *   <li><b>L2 — Redis</b>: AES-256-GCM encrypted, 2h TTL; survives rolling deployments so a run
 *       resumed on a different instance can still find its keys.
 * </ol>
 *
 * <p>On L1 miss: try Redis decrypt → promote to L1. On Redis miss: return {@link
 * ResolvedKeys#SYSTEM_ONLY} (graceful fallback — agents use system beans).
 *
 * <p>Lifecycle:
 *
 * <pre>
 *   RunOrchestrator.executeRunAsync()           → runKeyStore.put(runId, keys)
 *   [agents run normally — L1 hit for every call]
 *   RunOrchestrator.runBackgroundValidation finally → runKeyStore.remove(runId)
 * </pre>
 */
@Service
@Slf4j
public class RunKeyStore {

  private static final Duration L1_TTL = Duration.ofMinutes(30);
  private static final Duration REDIS_TTL = Duration.ofHours(2);
  private static final String KEY_PFX = "runkey:";

  private final Cache<String, ResolvedKeys> l1 =
      Caffeine.newBuilder().expireAfterAccess(L1_TTL).build();

  private final StringRedisTemplate redis;
  private final KeyEncryptor encryptor;
  private final ObjectMapper mapper;

  public RunKeyStore(StringRedisTemplate redis, KeyEncryptor encryptor, ObjectMapper mapper) {
    this.redis = redis;
    this.encryptor = encryptor;
    this.mapper = mapper;
  }

  /** Register resolved keys at the start of a run (writes to L1 + Redis). */
  public void put(String runId, ResolvedKeys keys) {
    l1.put(runId, keys);
    writeToRedis(runId, keys);
    log.debug(
        "[RunKeyStore] Stored keys for run {} — anthropic={}, openai={}, gemini={}",
        runId,
        keys.anthropicKey() != null,
        keys.openAiKey() != null,
        keys.geminiKey() != null);
  }

  /**
   * Returns the resolved keys for the given run. L1 hit → return immediately. L2 hit → promote to
   * L1. Miss → SYSTEM_ONLY.
   */
  public ResolvedKeys get(String runId) {
    // L1 hit (hot path — same instance, no serialization overhead)
    ResolvedKeys cached = l1.getIfPresent(runId);
    if (cached != null) return cached;

    // L2 (Redis) — cross-instance recovery after rolling deploy
    ResolvedKeys fromRedis = readFromRedis(runId);
    if (fromRedis != null) {
      l1.put(runId, fromRedis); // promote to L1
      log.debug("[RunKeyStore] L2 hit for run {} — promoted to L1", runId);
      return fromRedis;
    }

    log.debug("[RunKeyStore] Cache miss for run {} — using SYSTEM_ONLY", runId);
    return ResolvedKeys.SYSTEM_ONLY;
  }

  /** Remove entry at the end of a run (clears both L1 and Redis). */
  public void remove(String runId) {
    l1.invalidate(runId);
    try {
      redis.delete(KEY_PFX + runId);
    } catch (Exception e) {
      log.debug("[RunKeyStore] Redis delete failed for run {} — L1 already cleared", runId, e);
    }
    log.debug("[RunKeyStore] Cleaned up keys for run {}", runId);
  }

  private void writeToRedis(String runId, ResolvedKeys keys) {
    try {
      String json = mapper.writeValueAsString(keys);
      String encrypted = encryptor.encrypt(json);
      redis.opsForValue().set(KEY_PFX + runId, encrypted, REDIS_TTL);
    } catch (Exception e) {
      log.warn(
          "[RunKeyStore] Failed to write keys to Redis for run {} — L1 only: {}",
          runId,
          e.getMessage());
    }
  }

  private ResolvedKeys readFromRedis(String runId) {
    try {
      String encrypted = redis.opsForValue().get(KEY_PFX + runId);
      if (encrypted == null) return null;
      String json = encryptor.decrypt(encrypted);
      return mapper.readValue(json, ResolvedKeys.class);
    } catch (Exception e) {
      log.warn(
          "[RunKeyStore] Failed to read/decrypt keys from Redis for run {}: {}",
          runId,
          e.getMessage());
      return null;
    }
  }
}

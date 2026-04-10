package com.navneet.executespec.agent;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Holds the per-run resolved API keys for the lifetime of each pipeline run.
 *
 * <p>Keys are stored here (keyed by runId) so that {@link LlmRouter} can look them
 * up without any changes to agent method signatures. Every LLM call already passes
 * a runId, making this a zero-friction injection point.
 *
 * <p>Lifecycle:
 * <pre>
 *   PipelineHarness.executeRunAsync()       → runKeyStore.put(runId, keys)
 *   [agents run normally]
 *   PipelineHarness.runBackgroundValidation finally → runKeyStore.remove(runId)
 * </pre>
 *
 * <p>Safety net: Caffeine cache with 30-minute access-expiry ensures leaked entries are
 * eventually evicted even if the explicit {@code remove()} is never reached (e.g. OOM kill).
 * Background validation typically completes in &lt;5 minutes — well within the 30-min window.
 *
 * <p>Thread-safety: Caffeine — safe for concurrent worker tasks within
 * the same run as well as multiple runs executing in parallel.
 */
@Service
@Slf4j
public class RunKeyStore {

    /** Safety-net TTL: evict idle entries after 30 min (background validation completes in <5 min). */
    private static final Duration IDLE_EVICTION_TTL = Duration.ofMinutes(30);

    private final Cache<String, ResolvedKeys> store = Caffeine.newBuilder()
            .expireAfterAccess(IDLE_EVICTION_TTL)
            .build();

    /** Register resolved keys at the start of a run. */
    public void put(String runId, ResolvedKeys keys) {
        store.put(runId, keys);
        log.debug("[RunKeyStore] Stored keys for run {} — anthropic={}, openai={}, gemini={}",
                runId,
                keys.anthropicKey() != null,
                keys.openAiKey() != null,
                keys.geminiKey() != null);
    }

    /**
     * Returns the resolved keys for the given run.
     * Falls back to {@link ResolvedKeys#SYSTEM_ONLY} if no entry exists
     * (e.g. recovery runs, test contexts, or background agents).
     */
    public ResolvedKeys get(String runId) {
        ResolvedKeys keys = store.getIfPresent(runId);
        return keys != null ? keys : ResolvedKeys.SYSTEM_ONLY;
    }

    /** Remove the entry at the end of a run (cleanup). */
    public void remove(String runId) {
        store.invalidate(runId);
        log.debug("[RunKeyStore] Cleaned up keys for run {}", runId);
    }
}

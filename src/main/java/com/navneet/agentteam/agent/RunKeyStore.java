package com.navneet.agentteam.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the per-run resolved API keys for the lifetime of each pipeline run.
 *
 * <p>Keys are stored here (keyed by runId) so that {@link LlmRouter} can look them
 * up without any changes to agent method signatures. Every LLM call already passes
 * a runId, making this a zero-friction injection point.
 *
 * <p>Lifecycle:
 * <pre>
 *   PipelineHarness.executeRunAsync()  → runKeyStore.put(runId, keys)
 *   [agents run normally]
 *   PipelineHarness finally block      → runKeyStore.remove(runId)
 * </pre>
 *
 * <p>Thread-safety: {@link ConcurrentHashMap} — safe for concurrent worker tasks within
 * the same run as well as multiple runs executing in parallel.
 */
@Service
@Slf4j
public class RunKeyStore {

    private final Map<String, ResolvedKeys> store = new ConcurrentHashMap<>();

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
        return store.getOrDefault(runId, ResolvedKeys.SYSTEM_ONLY);
    }

    /** Remove the entry at the end of a run (cleanup). */
    public void remove(String runId) {
        store.remove(runId);
        log.debug("[RunKeyStore] Cleaned up keys for run {}", runId);
    }
}

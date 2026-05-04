package com.navneet.executespec.v2.agent;

import java.time.Duration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.navneet.executespec.agent.ApiKeyResolver;
import com.navneet.executespec.agent.ResolvedKeys;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-run BYOK key cache — single-source-of-truth for which keys {@link LlmRouterV2} should thread
 * into provider-side dispatch.
 *
 * <p>Lifecycle: {@code RunOrchestrator.runPipeline} resolves keys once at run-start via {@link
 * #resolveAndPut} and the entry stays live for the duration of the run. Caffeine LRU eviction (1024
 * entries × 6h TTL) keeps memory bounded — long enough to outlive any V2 run (the longest we've
 * observed in production is ~25 min for LARGE classification + full expansion). On eviction the
 * next {@link #get} returns null and the router safely falls through to platform-key dispatch
 * (HYBRID semantics) rather than failing the call.
 *
 * <p>Why a Caffeine cache rather than a {@code Map<runId, ctx>} or per-thread field: V2 fans out
 * across thread pools (the parallel-expansion pool, the reviewer pool, async refresh callbacks). A
 * thread-local would lose context across boundaries. A plain {@code Map} would leak indefinitely on
 * aborted runs — Caffeine's TTL guarantees cleanup even when the orchestrator crashes mid-pipeline
 * before its {@code finally} cleanup fires.
 *
 * <p>Audit context: this store is the load-bearing piece of the V2 BYOK fix — see {@code
 * audit-output/BYOK_DEEPDIVE.md} for the chain-of-evidence showing why the pre-fix V2 router
 * silently used platform keys.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RunKeyContextStore {

  private final ApiKeyResolver apiKeyResolver;

  /** runId → V2RunKeyContext. */
  private final Cache<String, V2RunKeyContext> cache =
      Caffeine.newBuilder().maximumSize(1024).expireAfterWrite(Duration.ofHours(6)).build();

  /**
   * Resolve user/org keys for the given run and stash them in the cache. Idempotent — re-calling
   * with the same {@code runId} replaces the existing entry (handy for runs that change ownership
   * mid-pipeline, though rare in practice).
   *
   * @return the resolved context (also stored in the cache under runId).
   */
  public V2RunKeyContext resolveAndPut(String runId, String userId) {
    if (runId == null || runId.isBlank()) {
      throw new IllegalArgumentException("runId required");
    }
    ResolvedKeys keys = apiKeyResolver.resolve(userId);

    V2RunKeyContext.Source anthropicSrc =
        (keys.anthropicKey() != null)
            ? V2RunKeyContext.Source.USER
            : V2RunKeyContext.Source.PLATFORM;
    V2RunKeyContext.Source openAiSrc =
        (keys.openAiKey() != null) ? V2RunKeyContext.Source.USER : V2RunKeyContext.Source.PLATFORM;
    V2RunKeyContext.Source geminiSrc =
        (keys.geminiKey() != null) ? V2RunKeyContext.Source.USER : V2RunKeyContext.Source.PLATFORM;
    // ApiKeyResolver merges user-level and org-level keys without
    // distinguishing which level won. We can't tell from the
    // ResolvedKeys output whether a non-null Anthropic key came from
    // user.anthropic_api_key or org.anthropic_api_key — both flow
    // through the same field. Mark them USER as the most-likely
    // attribution for the metric/audit; if the source split matters
    // for billing reconciliation, ApiKeyResolver itself needs to
    // return a richer ResolvedKeys (separate user-side + org-side
    // fields) — tracked as a follow-up to the BYOK fix.

    V2RunKeyContext ctx =
        new V2RunKeyContext(
            keys.anthropicKey(),
            keys.openAiKey(),
            keys.geminiKey(),
            anthropicSrc,
            openAiSrc,
            geminiSrc,
            userId,
            /* orgId= */ null);
    cache.put(runId, ctx);
    log.debug(
        "[RunKeyContextStore] resolved keys for run={} user={} dominant={}",
        runId,
        userId,
        ctx.dominantSource());
    return ctx;
  }

  /** Fetch the resolved keys for a run, or {@code null} if not stored. */
  public V2RunKeyContext get(String runId) {
    if (runId == null) return null;
    return cache.getIfPresent(runId);
  }

  /** Drop the entry — usually called from RunOrchestrator's terminal cleanup. */
  public void evict(String runId) {
    if (runId == null) return;
    cache.invalidate(runId);
  }
}

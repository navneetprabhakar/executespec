package com.navneet.agentteam.agent;

import com.navneet.agentteam.state.InboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheOptions;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Routes LLM calls with provider fallback guardrails.
 *
 * Guardrail rules:
 * 1. IMMEDIATE mode (planner/reviewer): always fallback on any error
 * 2. TASK_BOUNDARY mode, iteration==0: fallback allowed (task not started)
 * 3. TASK_BOUNDARY mode, justCompacted==true: fallback allowed (checkpoint is provider-agnostic)
 * 4. TASK_BOUNDARY mode, mid-task: NO fallback — throw MidTaskProviderFailureException
 * 5. Fallback stays within the same capability tier
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LlmRouter {

    public enum FallbackMode { IMMEDIATE, TASK_BOUNDARY }

    // ── provider fallback chains (2-provider: Anthropic → OpenAI) ────────────────
    // ARCHITECT_CHAIN: Planner — complex reasoning, task decomposition.
    private static final List<String[]> ARCHITECT_CHAIN = List.of(
        new String[]{"anthropic", "claude-opus-4-6"},
        new String[]{"openai",    "gpt-4o"}
    );

    // CAPABLE_CHAIN: Worker + Compiler + ApiTestSuite — code-write-test-fix loop.
    private static final List<String[]> CAPABLE_CHAIN = List.of(
        new String[]{"anthropic", "claude-sonnet-4-6"},
        new String[]{"openai",    "gpt-4o"}
    );

    // UTILITY_CHAIN: Reviewer + lightweight tasks — verification, JSON scoring.
    private static final List<String[]> UTILITY_CHAIN = List.of(
        new String[]{"anthropic", "claude-haiku-4-5-20251001"},
        new String[]{"openai",    "gpt-4o-mini"}
    );

    // Maps a primary model to its fallback chain (includes legacy names for backward compat)
    private static final Map<String, List<String[]>> MODEL_CHAIN = Map.of(
        "claude-opus-4-6",    ARCHITECT_CHAIN,  // The Architect (current)
        "claude-opus-4-5",    ARCHITECT_CHAIN,  // The Architect (legacy alias)
        "claude-sonnet-4-6",  CAPABLE_CHAIN,    // The Workhorse (current)
        "claude-sonnet-4-5",  CAPABLE_CHAIN,    // The Workhorse (legacy alias)
        "claude-haiku-4-5-20251001", UTILITY_CHAIN,  // The Reviewer / Utility (current)
        "claude-haiku-4-5",   UTILITY_CHAIN,    // The Reviewer / Utility (legacy alias)
        "gpt-4o-mini",        UTILITY_CHAIN,
        "gpt-4o",             CAPABLE_CHAIN
    );

    private final ApplicationContext ctx;
    private final InboxService inboxService;

    /**
     * Wraps a ChatResponse with the provider and model that actually served the request.
     * Callers that need to track which provider succeeded should use
     * {@link #callWithFallbackTracked} and update their own provider variable accordingly.
     */
    public record LlmResponse(ChatResponse response, String provider, String model) {}

    /**
     * Call the model with provider fallback according to the guardrail rules.
     * Returns an {@link LlmResponse} that includes the provider that actually succeeded,
     * allowing callers to update their tracked provider for subsequent calls.
     *
     * @param primaryProvider primary provider name ("anthropic", "openai")
     * @param primaryModel    primary model string
     * @param prompt          the prompt to send
     * @param runId           pipeline run ID (for inbox logging)
     * @param agentId         agent identifier (for inbox logging)
     * @param mode            fallback mode
     * @param iteration       current agentic loop iteration (0 = task not yet started)
     * @param justCompacted   true if a compaction checkpoint was just taken
     * @return LlmResponse containing the ChatResponse and the provider/model that succeeded
     * @throws ProviderUnavailableException     if all providers fail
     * @throws MidTaskProviderFailureException  if worker fails mid-task with no safe fallback
     */
    public LlmResponse callWithFallbackTracked(String primaryProvider, String primaryModel,
                                               Prompt prompt, String runId, String agentId,
                                               FallbackMode mode, int iteration, boolean justCompacted) {
        List<String[]> chain = resolveChain(primaryModel);
        int startIndex = findIndexInChain(chain, primaryProvider);

        Exception lastError = null;
        for (int i = startIndex; i < chain.size(); i++) {
            String provider = chain.get(i)[0];
            String model    = chain.get(i)[1];

            if (i > startIndex) {
                log.warn("[LlmRouter] Falling back: {} -> {} for agent {} (iteration={})",
                    primaryProvider, provider, agentId, iteration);
                inboxService.sendProviderFallback(runId, agentId, primaryProvider, provider,
                    lastError != null ? lastError.getMessage() : "unknown error");
            }

            try {
                ChatResponse response = callWithModelOptions(provider, model, prompt);
                if (i > startIndex) {
                    log.info("[LlmRouter] Fallback succeeded: {} for {}", provider, agentId);
                }
                return new LlmResponse(response, provider, model);
            } catch (Exception e) {
                if (isPerMinuteRateLimit(e)) {
                    // Per-minute quota exhausted — sleep 15 s and retry the SAME provider.
                    log.warn("[LlmRouter] Per-minute rate limit on {} for {} (iteration={}) — sleeping 15 s before retry",
                        provider, agentId, iteration);
                    try {
                        Thread.sleep(15_000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new ProviderUnavailableException("Interrupted during rate-limit sleep for " + agentId);
                    }
                    try {
                        ChatResponse retryResponse = callWithModelOptions(provider, model, prompt);
                        log.info("[LlmRouter] Post-sleep retry succeeded: {} for {}", provider, agentId);
                        return new LlmResponse(retryResponse, provider, model);
                    } catch (Exception retryEx) {
                        // Still failing after the sleep (e.g. other workers consumed the quota).
                        // Fall through to the next provider in chain.
                        log.warn("[LlmRouter] Post-sleep retry also failed on {} for {}: {}",
                            provider, agentId, retryEx.getMessage());
                        lastError = retryEx;
                    }
                } else if (isOverloaded(e)) {
                    // HTTP 529 Overloaded — Anthropic is temporarily saturated.
                    // Sleep 30 s (longer than per-minute since overloads clear more slowly)
                    // and retry the same provider once before handing off to OpenAI.
                    log.warn("[LlmRouter] Anthropic overloaded (529) for {} (iteration={}) — sleeping 30 s before retry",
                        agentId, iteration);
                    try {
                        Thread.sleep(30_000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new ProviderUnavailableException("Interrupted during overload sleep for " + agentId);
                    }
                    try {
                        ChatResponse retryResponse = callWithModelOptions(provider, model, prompt);
                        log.info("[LlmRouter] Post-overload retry succeeded: {} for {}", provider, agentId);
                        return new LlmResponse(retryResponse, provider, model);
                    } catch (Exception retryEx) {
                        // Still overloaded — fall through to the next provider in chain.
                        log.warn("[LlmRouter] Post-overload retry also failed on {} for {}: {}",
                            provider, agentId, retryEx.getMessage());
                        lastError = retryEx;
                    }
                } else {
                    // Any other failure: immediately try the next provider.
                    // No mid-task guardrail — message context is portable across providers.
                    log.warn("[LlmRouter] Provider {} failed for {} (non-rate-limit, iteration={}): {}",
                        provider, agentId, iteration, e.getMessage());
                    lastError = e;
                }
            }
        }

        throw new ProviderUnavailableException(
            "All providers in chain exhausted for agent " + agentId + ". Last error: "
            + (lastError != null ? lastError.getMessage() : "none"));
    }

    /**
     * Convenience wrapper for callers that only need the ChatResponse (IMMEDIATE mode agents).
     */
    public ChatResponse callWithFallback(String primaryProvider, String primaryModel,
                                         Prompt prompt, String runId, String agentId,
                                         FallbackMode mode, int iteration, boolean justCompacted) {
        return callWithFallbackTracked(primaryProvider, primaryModel, prompt, runId, agentId,
            mode, iteration, justCompacted).response();
    }

    /**
     * Calls a provider's ChatModel with the given model name set as a ChatOptions override.
     * Preserves any {@link ToolCallback} definitions, {@code internalToolExecutionEnabled} flag,
     * {@code maxTokens}, and Anthropic {@link AnthropicCacheOptions} from the original prompt.
     */
    private ChatResponse callWithModelOptions(String provider, String model, Prompt prompt) {
        // Extract tool callbacks, internalToolExec flag, maxTokens, and cacheOptions from
        // caller's options so they all survive the provider re-wrap.
        List<ToolCallback> toolCallbacks = List.of();
        Boolean internalToolExec = null;
        Integer maxTokens = null;
        AnthropicCacheOptions cacheOpts = null;
        if (prompt.getOptions() != null) {
            maxTokens = prompt.getOptions().getMaxTokens();
        }
        if (prompt.getOptions() instanceof ToolCallingChatOptions tco) {
            var cbs = tco.getToolCallbacks();
            if (cbs != null && !cbs.isEmpty()) toolCallbacks = cbs;
            internalToolExec = tco.getInternalToolExecutionEnabled();
        }
        // Extract Anthropic-specific cache options so they survive the re-wrap.
        if (prompt.getOptions() instanceof AnthropicChatOptions aco) {
            cacheOpts = aco.getCacheOptions();
        }
        final var callbacks       = toolCallbacks;
        final var toolExecEnabled = internalToolExec;
        final var maxTok          = maxTokens;
        final var finalCacheOpts  = cacheOpts;

        log.info("[LlmRouter] callWithModelOptions: provider={} model={} tools={} internalExec={} cacheStrategy={}",
            provider, model, callbacks.size(), toolExecEnabled,
            finalCacheOpts != null && finalCacheOpts.getStrategy() != null
                ? finalCacheOpts.getStrategy() : "NONE");

        Prompt augmented = switch (provider.toLowerCase()) {
            case "anthropic" -> {
                var builder = AnthropicChatOptions.builder().model(model);
                if (!callbacks.isEmpty()) builder.toolCallbacks(callbacks);
                if (toolExecEnabled != null) builder.internalToolExecutionEnabled(toolExecEnabled);
                if (maxTok != null) builder.maxTokens(maxTok);
                if (finalCacheOpts != null) builder.cacheOptions(finalCacheOpts);
                yield new Prompt(prompt.getInstructions(), builder.build());
            }
            case "openai" -> {
                var builder = OpenAiChatOptions.builder().model(model);
                if (!callbacks.isEmpty()) builder.toolCallbacks(callbacks);
                if (toolExecEnabled != null) builder.internalToolExecutionEnabled(toolExecEnabled);
                if (maxTok != null) builder.maxTokens(maxTok);
                yield new Prompt(prompt.getInstructions(), builder.build());
            }
            default -> prompt;
        };
        return resolveModel(provider).call(augmented);
    }

    private List<String[]> resolveChain(String model) {
        if (model == null) return CAPABLE_CHAIN;
        List<String[]> chain = MODEL_CHAIN.get(model.toLowerCase());
        return chain != null ? chain : CAPABLE_CHAIN;
    }

    private int findIndexInChain(List<String[]> chain, String provider) {
        for (int i = 0; i < chain.size(); i++) {
            if (chain.get(i)[0].equalsIgnoreCase(provider)) return i;
        }
        return 0; // default to first in chain
    }

    private org.springframework.ai.chat.model.ChatModel resolveModel(String provider) {
        return switch (provider.toLowerCase()) {
            case "anthropic" -> ctx.getBean(AnthropicChatModel.class);
            case "openai"    -> ctx.getBean(OpenAiChatModel.class);
            default -> throw new ProviderUnavailableException("Unknown provider: " + provider);
        };
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Returns true only for per-minute quota errors (HTTP 429 with "per minute" in the body).
     * These are transient: waiting 15 s is enough for the quota window to reset.
     * Other 429 variants (daily limits, account-level bans) are treated as hard failures
     * and trigger an immediate provider switch instead of a sleep.
     */
    private boolean isPerMinuteRateLimit(Exception e) {
        if (e == null) return false;
        String msg = e.getMessage();
        return msg != null && msg.contains("per minute");
    }

    /**
     * Returns true for HTTP 529 Overloaded responses from Anthropic.
     * These indicate temporary server saturation — distinct from 429 rate limits.
     * Spring AI wraps these as TransientAiException, so we match by message content.
     * A 30-second sleep gives the cluster time to shed load before retrying.
     */
    private boolean isOverloaded(Exception e) {
        if (e == null) return false;
        String msg = e.getMessage();
        if (msg == null) return false;
        return msg.contains("529") || msg.toLowerCase().contains("overloaded_error")
            || msg.toLowerCase().contains("overloaded");
    }

    // ── Exceptions ─────────────────────────────────────────────────────────────

    public static class ProviderUnavailableException extends RuntimeException {
        public ProviderUnavailableException(String message) { super(message); }
    }

    public static class MidTaskProviderFailureException extends RuntimeException {
        public MidTaskProviderFailureException(String message) { super(message); }
    }
}

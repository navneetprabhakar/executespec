package com.navneet.executespec.agent;

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelRegistry;
import com.navneet.executespec.router.ProviderHealthTracker;
import com.navneet.executespec.state.InboxService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import static com.navneet.executespec.config.CustomMetricsConfig.*;
import com.navneet.executespec.config.CustomMetricsConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.anthropic.api.AnthropicCacheOptions;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import com.google.genai.Client;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.ApplicationContext;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Routes LLM calls with provider fallback guardrails.
 *
 * Fallback chain (3-provider):  anthropic → openai → google (Gemini)
 *
 * Per-run API key injection:
 *   Keys are resolved via {@link ApiKeyResolver} (user → org → system hierarchy)
 *   and stored in {@link RunKeyStore} at run-start. This router looks them up
 *   by runId for every call — zero changes to any agent method signatures.
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

    public enum FallbackMode { IMMEDIATE, TASK_BOUNDARY, NO_FALLBACK }

    /** Per-run LLM call counters — for observability only (no enforcement). TTL 6h, max 1000. */
    private final Cache<String, AtomicInteger> runCallCounters = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(6))
            .maximumSize(1000)
            .build();

    /** Per-run user tier cache — populated by PipelineHarness at run start. FREE tier forces gpt-5.4-mini. TTL 6h, max 1000. */
    private final Cache<String, String> runUserTierCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(6))
            .maximumSize(1000)
            .build();

    private final ApplicationContext ctx;
    private final InboxService inboxService;
    private final RunKeyStore runKeyStore;
    private final AgentProperties agentProperties;
    private final MeterRegistry meterRegistry;
    private final ProviderHealthTracker healthTracker;
    private final ModelRegistry modelRegistry;

    /**
     * Wraps a ChatResponse with the provider and model that actually served the request.
     */
    public record LlmResponse(ChatResponse response, String provider, String model) {}

    /**
     * Call the model with provider fallback according to the guardrail rules.
     */
    public LlmResponse callWithFallbackTracked(String primaryProvider, String primaryModel,
                                               Prompt prompt, String runId, String agentId,
                                               FallbackMode mode, int iteration, boolean justCompacted) {
        // FREE tier model lock: force all calls to configured free-tier model
        String userTier = getRunUserTier(runId);
        if ("FREE".equals(userTier)) {
            primaryProvider = modelRegistry.freeTierProvider();
            primaryModel = modelRegistry.freeTierModel();
        }

        // Track call count for observability (no enforcement — wall-clock limit is the safety net)
        if (runId != null) {
            runCallCounters.get(runId, k -> new AtomicInteger(0)).incrementAndGet();
        }

        Observation llmObs = Observation.createNotStarted("executespec.llm.call", getObservationRegistry())
            .lowCardinalityKeyValue("agent.id", agentId != null ? agentId : "unknown")
            .lowCardinalityKeyValue("run.id", runId != null ? runId : "unknown")
            .lowCardinalityKeyValue("fallback.mode", mode != null ? mode.name() : "unknown")
            .start();

        try {
            List<String[]> chain = resolveChain(primaryModel);
            int startIndex = findIndexInChain(chain, primaryProvider);

            // Resolve per-run keys to check provider restrictions
            ResolvedKeys runKeys = runKeyStore.get(runId);

            // NO_FALLBACK mode: only try the primary provider (used when message history
            // is provider-specific, e.g. OpenAI native tool calling with ToolResponseMessages)
            int maxAttempts = (mode == FallbackMode.NO_FALLBACK) ? 1 : chain.size();

            Exception lastError = null;
            for (int j = 0; j < maxAttempts; j++) {
                int i = (startIndex + j) % chain.size();
                String provider = chain.get(i)[0];
                String model    = chain.get(i)[1];
                Instant callStart = Instant.now();

                llmObs.lowCardinalityKeyValue("provider", provider);
                llmObs.lowCardinalityKeyValue("model", model);

                // Skip providers disabled by org policy
                if (runKeys != null && !runKeys.isProviderAllowed(provider)) {
                    log.info("[LlmRouter] Provider {} disabled by org policy — skipping", provider);
                    continue;
                }

                // Skip providers marked unhealthy by global circuit breaker
                LlmProvider llmProvider = toLlmProvider(provider);
                if (!healthTracker.isHealthy(llmProvider)) {
                    log.info("[LlmRouter] Provider {} circuit OPEN — skipping for agent {} (iteration={})",
                        provider, agentId, iteration);
                    continue;
                }

                if (j > 0) {
                    String fromProvider = chain.get((startIndex + j - 1) % chain.size())[0];
                    llmObs.event(Observation.Event.of("fallback",
                        "Falling back from " + fromProvider + " to " + provider));
                    Counter.builder(LLM_FALLBACK).tag("from", fromProvider).tag("to", provider)
                            .register(meterRegistry).increment();
                    log.warn("[LlmRouter] Falling back: {} -> {} for agent {} (iteration={})",
                        primaryProvider, provider, agentId, iteration);
                    inboxService.sendProviderFallback(runId, agentId, primaryProvider, provider,
                        lastError != null ? lastError.getMessage() : "unknown error");
                }

                try {
                    ChatResponse response = callWithModelOptions(provider, model, prompt, runId);
                    Duration callDuration = Duration.between(callStart, Instant.now());
                    Counter.builder(LLM_CALLS).tag("provider", provider).tag("model", model)
                            .register(meterRegistry).increment();
                    Timer.builder(LLM_DURATION).tag("provider", provider).tag("model", model)
                            .register(meterRegistry).record(callDuration);
                    healthTracker.recordSuccess(llmProvider);
                    if (j > 0) {
                        log.info("[LlmRouter] Fallback succeeded: {} for {}", provider, agentId);
                    }
                    // Record token usage as high-cardinality keys
                    try {
                        var usage = response.getMetadata().getUsage();
                        Counter.builder(LLM_TOKENS_INPUT).tag("provider", provider).tag("model", model)
                                .register(meterRegistry).increment(usage.getPromptTokens());
                        Counter.builder(LLM_TOKENS_OUTPUT).tag("provider", provider).tag("model", model)
                                .register(meterRegistry).increment(usage.getCompletionTokens());
                        llmObs.highCardinalityKeyValue("tokens.prompt",
                            String.valueOf(usage.getPromptTokens()));
                        llmObs.highCardinalityKeyValue("tokens.completion",
                            String.valueOf(usage.getCompletionTokens()));
                        llmObs.highCardinalityKeyValue("tokens.total",
                            String.valueOf(usage.getTotalTokens()));
                    } catch (Exception tokenEx) {
                        // token extraction is best-effort
                    }
                    llmObs.lowCardinalityKeyValue("outcome", "success");
                    return new LlmResponse(response, provider, model);
                } catch (Exception e) {
                    if (isPerMinuteRateLimit(e)) {
                        llmObs.event(Observation.Event.of("rate_limit",
                            "Per-minute rate limit on " + provider));
                        log.warn("[LlmRouter] Per-minute rate limit on {} for {} (iteration={}) — sleeping 30 s before retry",
                            provider, agentId, iteration);
                        try {
                            Thread.sleep(30_000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new ProviderUnavailableException("Interrupted during rate-limit sleep for " + agentId);
                        }
                        try {
                            ChatResponse retryResponse = callWithModelOptions(provider, model, prompt, runId);
                            Duration retryCallDuration = Duration.between(callStart, Instant.now());
                            Counter.builder(LLM_CALLS).tag("provider", provider).tag("model", model)
                                    .register(meterRegistry).increment();
                            Counter.builder(LLM_RETRY_COUNT).tag("provider", provider).tag("model", model)
                                    .register(meterRegistry).increment();
                            Timer.builder(LLM_DURATION).tag("provider", provider).tag("model", model)
                                    .register(meterRegistry).record(retryCallDuration);
                            healthTracker.recordSuccess(llmProvider);
                            log.info("[LlmRouter] Post-sleep retry succeeded: {} for {}", provider, agentId);
                            try {
                                var usage = retryResponse.getMetadata().getUsage();
                                Counter.builder(LLM_TOKENS_INPUT).tag("provider", provider).tag("model", model)
                                        .register(meterRegistry).increment(usage.getPromptTokens());
                                Counter.builder(LLM_TOKENS_OUTPUT).tag("provider", provider).tag("model", model)
                                        .register(meterRegistry).increment(usage.getCompletionTokens());
                                llmObs.highCardinalityKeyValue("tokens.prompt",
                                    String.valueOf(usage.getPromptTokens()));
                                llmObs.highCardinalityKeyValue("tokens.completion",
                                    String.valueOf(usage.getCompletionTokens()));
                                llmObs.highCardinalityKeyValue("tokens.total",
                                    String.valueOf(usage.getTotalTokens()));
                            } catch (Exception tokenEx) {
                                // best-effort
                            }
                            llmObs.lowCardinalityKeyValue("outcome", "success_after_retry");
                            return new LlmResponse(retryResponse, provider, model);
                        } catch (Exception retryEx) {
                            healthTracker.recordFailure(llmProvider);
                            log.warn("[LlmRouter] Post-sleep retry also failed on {} for {}: {}",
                                provider, agentId, retryEx.getMessage());
                            Counter.builder(LLM_ERRORS).tag("provider", provider).tag("error_type", "rate_limit_retry_failed")
                                    .register(meterRegistry).increment();
                            lastError = retryEx;
                        }
                    } else if (isOverloaded(e)) {
                        llmObs.event(Observation.Event.of("overloaded",
                            "Provider overloaded (529) on " + provider));
                        log.warn("[LlmRouter] Anthropic overloaded (529) for {} (iteration={}) — sleeping 30 s before retry",
                            agentId, iteration);
                        try {
                            Thread.sleep(30_000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new ProviderUnavailableException("Interrupted during overload sleep for " + agentId);
                        }
                        try {
                            ChatResponse retryResponse = callWithModelOptions(provider, model, prompt, runId);
                            Duration retryCallDuration2 = Duration.between(callStart, Instant.now());
                            Counter.builder(LLM_CALLS).tag("provider", provider).tag("model", model)
                                    .register(meterRegistry).increment();
                            Counter.builder(LLM_RETRY_COUNT).tag("provider", provider).tag("model", model)
                                    .register(meterRegistry).increment();
                            Timer.builder(LLM_DURATION).tag("provider", provider).tag("model", model)
                                    .register(meterRegistry).record(retryCallDuration2);
                            healthTracker.recordSuccess(llmProvider);
                            log.info("[LlmRouter] Post-overload retry succeeded: {} for {}", provider, agentId);
                            try {
                                var usage = retryResponse.getMetadata().getUsage();
                                Counter.builder(LLM_TOKENS_INPUT).tag("provider", provider).tag("model", model)
                                        .register(meterRegistry).increment(usage.getPromptTokens());
                                Counter.builder(LLM_TOKENS_OUTPUT).tag("provider", provider).tag("model", model)
                                        .register(meterRegistry).increment(usage.getCompletionTokens());
                                llmObs.highCardinalityKeyValue("tokens.prompt",
                                    String.valueOf(usage.getPromptTokens()));
                                llmObs.highCardinalityKeyValue("tokens.completion",
                                    String.valueOf(usage.getCompletionTokens()));
                                llmObs.highCardinalityKeyValue("tokens.total",
                                    String.valueOf(usage.getTotalTokens()));
                            } catch (Exception tokenEx) {
                                // best-effort
                            }
                            llmObs.lowCardinalityKeyValue("outcome", "success_after_retry");
                            return new LlmResponse(retryResponse, provider, model);
                        } catch (Exception retryEx) {
                            healthTracker.recordFailure(llmProvider);
                            log.warn("[LlmRouter] Post-overload retry also failed on {} for {}: {}",
                                provider, agentId, retryEx.getMessage());
                            Counter.builder(LLM_ERRORS).tag("provider", provider).tag("error_type", "overload_retry_failed")
                                    .register(meterRegistry).increment();
                            lastError = retryEx;
                        }
                    } else {
                        healthTracker.recordFailure(llmProvider);
                        log.warn("[LlmRouter] Provider {} failed for {} (non-rate-limit, iteration={}): {}",
                            provider, agentId, iteration, e.getMessage());
                        Counter.builder(LLM_ERRORS).tag("provider", provider).tag("error_type", "provider_error")
                                .register(meterRegistry).increment();
                        lastError = e;
                    }
                }
            }

            // If all providers were skipped due to circuit breaker (lastError is null),
            // retry the primary provider anyway — same "force primary" pattern as ModelResolver.
            // But respect org policy: if the primary is policy-blocked, don't bypass.
            if (lastError == null) {
                if (runKeys != null && !runKeys.isProviderAllowed(primaryProvider)) {
                    log.warn("[LlmRouter] All providers circuit-open AND primary {} disabled by org policy — cannot force", primaryProvider);
                    throw new ProviderUnavailableException(
                        "All providers circuit-open and primary provider " + primaryProvider
                        + " is disabled by org policy for agent " + agentId);
                }
                log.warn("[LlmRouter] All providers circuit-open — forcing primary {} for {}", primaryProvider, agentId);
                try {
                    ChatResponse forced = callWithModelOptions(primaryProvider, primaryModel, prompt, runId);
                    healthTracker.recordSuccess(toLlmProvider(primaryProvider));
                    llmObs.lowCardinalityKeyValue("outcome", "success_forced_primary");
                    return new LlmResponse(forced, primaryProvider, primaryModel);
                } catch (Exception forcedEx) {
                    lastError = forcedEx;
                }
            }

            llmObs.lowCardinalityKeyValue("outcome", "all_providers_exhausted");
            throw new ProviderUnavailableException(
                "All providers in chain exhausted for agent " + agentId + ". Last error: "
                + (lastError != null ? lastError.getMessage() : "none"));
        } catch (Exception e) {
            llmObs.error(e);
            throw e;
        } finally {
            llmObs.stop();
        }
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
     * Direct LLM call that bypasses health checks, fallback chains, and per-run key resolution.
     * Uses system API keys only. Intended for eval/admin tooling where isolation per model is required.
     *
     * @param provider  provider name (anthropic, google, openai)
     * @param model     exact model string
     * @param prompt    the prompt to send
     * @return response with provider/model metadata
     */
    public LlmResponse callDirect(String provider, String model, Prompt prompt) {
        Instant start = Instant.now();
        ChatResponse response = callWithModelOptions(provider, model, prompt, "eval-system");
        Duration duration = Duration.between(start, Instant.now());
        Counter.builder(LLM_CALLS).tag("provider", provider).tag("model", model)
                .register(meterRegistry).increment();
        Timer.builder(LLM_DURATION).tag("provider", provider).tag("model", model)
                .register(meterRegistry).record(duration);
        return new LlmResponse(response, provider, model);
    }

    // ── Internal ───────────────────────────────────────────────────────────────

    /**
     * Builds a provider-specific Prompt augmented with model options and calls it.
     * Looks up the per-run resolved keys from {@link RunKeyStore} and uses a
     * dynamically-built ChatModel if the user/org has provided their own key;
     * otherwise falls through to the system singleton bean.
     */
    private ChatResponse callWithModelOptions(String provider, String model, Prompt prompt, String runId) {
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
        if (prompt.getOptions() instanceof AnthropicChatOptions aco) {
            cacheOpts = aco.getCacheOptions();
        }

        // Anthropic API requires max_tokens — default from agent.token-budget.max-tokens
        if (maxTokens == null) {
            maxTokens = agentProperties.getTokenBudget().getMaxTokens();
        }

        final var callbacks       = toolCallbacks;
        final var toolExecEnabled = internalToolExec;
        final var maxTok          = maxTokens;
        final var finalCacheOpts  = cacheOpts;

        log.info("[LlmRouter] callWithModelOptions: provider={} model={} tools={} internalExec={} cacheStrategy={} runId={}",
            provider, model, callbacks.size(), toolExecEnabled,
            finalCacheOpts != null && finalCacheOpts.getStrategy() != null
                ? finalCacheOpts.getStrategy() : "NONE",
            runId);

        // Resolve per-run keys (user → org → system)
        ResolvedKeys keys = runKeyStore.get(runId);

        Prompt augmented = switch (provider.toLowerCase()) {
            case "anthropic" -> {
                var builder = AnthropicChatOptions.builder().model(model).maxTokens(maxTok).temperature(0.0);
                if (!callbacks.isEmpty()) builder.toolCallbacks(callbacks);
                if (toolExecEnabled != null) builder.internalToolExecutionEnabled(toolExecEnabled);
                if (finalCacheOpts != null) builder.cacheOptions(finalCacheOpts);
                yield new Prompt(prompt.getInstructions(), builder.build());
            }
            case "openai" -> {
                var builder = OpenAiChatOptions.builder().model(model).temperature(0.0);
                if (!callbacks.isEmpty()) builder.toolCallbacks(callbacks);
                if (toolExecEnabled != null) builder.internalToolExecutionEnabled(toolExecEnabled);
                if (maxTok != null) builder.maxCompletionTokens(maxTok);
                yield new Prompt(prompt.getInstructions(), builder.build());
            }
            case "google" -> {
                var builder = GoogleGenAiChatOptions.builder().model(model).temperature(0.0);
                if (maxTok != null) builder.maxOutputTokens(maxTok);
                yield new Prompt(prompt.getInstructions(), builder.build());
            }
            default -> prompt;
        };

        ChatResponse response = resolveModel(provider, keys).call(augmented);

        // Guard against null result — Gemini can return a ChatResponse with getResult() == null
        // (e.g., safety filter, empty response). Treat as a provider error to trigger fallback.
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new ProviderUnavailableException(
                provider + " returned an empty/null response (model=" + model + ") — possible safety filter or empty generation");
        }
        return response;
    }

    /**
     * Returns the ChatModel for the given provider.
     * If the resolved keys contain a user/org-level key, a fresh model instance is created
     * with that key; otherwise the system singleton bean is used.
     */
    private org.springframework.ai.chat.model.ChatModel resolveModel(String provider, ResolvedKeys keys) {
        return switch (provider.toLowerCase()) {
            case "anthropic" -> {
                String key = keys.anthropicKey();
                if (key != null) {
                    log.debug("[LlmRouter] Using user/org Anthropic key for this call");
                    yield buildAnthropicModel(key);
                }
                if ("OWN_KEYS".equals(keys.keySourceMode())) {
                    throw new ProviderUnavailableException(
                        "Anthropic key not provided, mode=OWN_KEYS — no system fallback");
                }
                yield ctx.getBean(AnthropicChatModel.class);
            }
            case "openai" -> {
                String key = keys.openAiKey();
                if (key != null) {
                    log.debug("[LlmRouter] Using user/org OpenAI key for this call");
                    yield buildOpenAiModel(key);
                }
                if ("OWN_KEYS".equals(keys.keySourceMode())) {
                    throw new ProviderUnavailableException(
                        "OpenAI key not provided, mode=OWN_KEYS — no system fallback");
                }
                OpenAiChatModel bean = ctx.getBean(OpenAiChatModel.class);
                if (bean == null) {
                    throw new ProviderUnavailableException(
                        "OpenAI system key not configured — provider unavailable");
                }
                yield bean;
            }
            case "google" -> {
                String key = keys.geminiKey();
                if (key != null) {
                    log.debug("[LlmRouter] Using user/org Gemini key for this call");
                    yield buildGeminiModel(key);
                }
                if ("OWN_KEYS".equals(keys.keySourceMode())) {
                    throw new ProviderUnavailableException(
                        "Gemini key not provided, mode=OWN_KEYS — no system fallback");
                }
                GoogleGenAiChatModel systemBean = ctx.getBeanProvider(GoogleGenAiChatModel.class).getIfAvailable();
                if (systemBean == null) {
                    throw new ProviderUnavailableException(
                        "Gemini not configured: no user/org key and no system GOOGLE_API_KEY");
                }
                yield systemBean;
            }
            default -> throw new ProviderUnavailableException("Unknown provider: " + provider);
        };
    }

    // ── Per-request model builders ─────────────────────────────────────────────

    private AnthropicChatModel buildAnthropicModel(String apiKey) {
        AnthropicApi api = AnthropicApi.builder().apiKey(apiKey).build();
        return AnthropicChatModel.builder()
                .anthropicApi(api)
                .defaultOptions(AnthropicChatOptions.builder().temperature(0.0).build())
                .retryTemplate(getRetryTemplate())
                .observationRegistry(getObservationRegistry())
                .build();
    }

    private OpenAiChatModel buildOpenAiModel(String apiKey) {
        OpenAiApi api = OpenAiApi.builder().apiKey(apiKey).build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().temperature(0.0).build())
                .retryTemplate(getRetryTemplate())
                .observationRegistry(getObservationRegistry())
                .build();
    }

    private GoogleGenAiChatModel buildGeminiModel(String apiKey) {
        Client client = Client.builder().apiKey(apiKey).build();
        return GoogleGenAiChatModel.builder()
                .genAiClient(client)
                .defaultOptions(GoogleGenAiChatOptions.builder().temperature(0.0).build())
                .retryTemplate(getRetryTemplate())
                .observationRegistry(getObservationRegistry())
                .build();
    }

    private RetryTemplate getRetryTemplate() {
        try {
            return ctx.getBean(RetryTemplate.class);
        } catch (Exception e) {
            return RetryUtils.DEFAULT_RETRY_TEMPLATE;
        }
    }

    private ObservationRegistry getObservationRegistry() {
        try {
            return ctx.getBean(ObservationRegistry.class);
        } catch (Exception e) {
            return ObservationRegistry.NOOP;
        }
    }

    // ── Chain helpers ───────────────────────────────────────────────────────────

    private List<String[]> resolveChain(String model) {
        List<String[]> chain = modelRegistry.fallbackChainFor(model);
        return chain != null && !chain.isEmpty() ? chain : modelRegistry.fallbackChainForTier("CAPABLE");
    }

    private int findIndexInChain(List<String[]> chain, String provider) {
        for (int i = 0; i < chain.size(); i++) {
            if (chain.get(i)[0].equalsIgnoreCase(provider)) return i;
        }
        return 0;
    }

    // ── Provider string → enum mapping ─────────────────────────────────────────

    /** Maps the chain provider string ("anthropic", "google", "openai") to LlmProvider enum. */
    private static LlmProvider toLlmProvider(String provider) {
        return switch (provider.toLowerCase()) {
            case "google" -> LlmProvider.GOOGLE;
            case "anthropic" -> LlmProvider.ANTHROPIC;
            case "openai" -> LlmProvider.OPENAI;
            default -> LlmProvider.valueOf(provider.toUpperCase());
        };
    }

    // ── Rate-limit / overload detection ────────────────────────────────────────

    private boolean isPerMinuteRateLimit(Exception e) {
        if (e == null) return false;
        String msg = e.getMessage();
        return msg != null && msg.contains("per minute");
    }

    private boolean isOverloaded(Exception e) {
        if (e == null) return false;
        String msg = e.getMessage();
        if (msg == null) return false;
        return msg.contains("529") || msg.toLowerCase().contains("overloaded_error")
            || msg.toLowerCase().contains("overloaded");
    }

    // ── Call counter lifecycle ─────────────────────────────────────────────────

    /** Call when a run completes (success or failure) to free memory. */
    public void clearRunCallCounter(String runId) {
        if (runId != null) {
            runCallCounters.invalidate(runId);
            runUserTierCache.invalidate(runId);
        }
    }

    /** Get current LLM call count for a run (for observability). */
    public int getRunCallCount(String runId) {
        AtomicInteger counter = runCallCounters.getIfPresent(runId);
        return counter != null ? counter.get() : 0;
    }

    // ── FREE tier model lock ──────────────────────────────────────────────────

    /** Register the user's subscription tier for a run. Called by PipelineHarness at run start. */
    public void setRunUserTier(String runId, String userTier) {
        if (runId != null && userTier != null) {
            runUserTierCache.put(runId, userTier.toUpperCase());
        }
    }

    /** Returns the cached user tier for a run, or null if not set. */
    public String getRunUserTier(String runId) {
        return runId != null ? runUserTierCache.getIfPresent(runId) : null;
    }

    // ── Exceptions ─────────────────────────────────────────────────────────────

    public static class ProviderUnavailableException extends RuntimeException {
        public ProviderUnavailableException(String message) { super(message); }
    }

    public static class MidTaskProviderFailureException extends RuntimeException {
        public MidTaskProviderFailureException(String message) { super(message); }
    }

}

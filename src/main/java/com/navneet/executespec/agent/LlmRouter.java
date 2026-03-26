package com.navneet.executespec.agent;

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.config.ModelConstants;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    public enum FallbackMode { IMMEDIATE, TASK_BOUNDARY }

    // ── Provider fallback chains (3-provider: Anthropic → Gemini → OpenAI) ──────

    // ── Provider fallback chains: Anthropic → Gemini → OpenAI (fixed priority) ──
    // Gemini uses GA model strings; fallback chain handles unavailability gracefully.

    private static final List<String[]> ARCHITECT_CHAIN = List.of(
        new String[]{"anthropic", ModelConstants.OPUS},
        new String[]{"google",    ModelConstants.GEMINI_PRO},
        new String[]{"openai",    ModelConstants.GPT5_4}
    );

    private static final List<String[]> CAPABLE_CHAIN = List.of(
        new String[]{"anthropic", ModelConstants.SONNET},
        new String[]{"google",    ModelConstants.GEMINI_PRO},
        new String[]{"openai",    ModelConstants.GPT5_4}
    );

    private static final List<String[]> UTILITY_CHAIN = List.of(
        new String[]{"anthropic", ModelConstants.HAIKU},
        new String[]{"google",    ModelConstants.GEMINI_FLASH},
        new String[]{"openai",    ModelConstants.GPT5_4_MINI}
    );

    private static final Map<String, List<String[]>> MODEL_CHAIN = Map.ofEntries(
        Map.entry(ModelConstants.OPUS,              ARCHITECT_CHAIN),
        Map.entry(ModelConstants.OPUS_LEGACY,       ARCHITECT_CHAIN),
        Map.entry(ModelConstants.SONNET,            CAPABLE_CHAIN),
        Map.entry(ModelConstants.SONNET_LEGACY,     CAPABLE_CHAIN),
        Map.entry(ModelConstants.HAIKU,             UTILITY_CHAIN),
        Map.entry(ModelConstants.HAIKU_LEGACY,      UTILITY_CHAIN),
        Map.entry(ModelConstants.GPT5_4_MINI,       UTILITY_CHAIN),
        Map.entry(ModelConstants.GPT4O_MINI,        UTILITY_CHAIN),  // legacy compat
        Map.entry(ModelConstants.GPT5_4,             CAPABLE_CHAIN),
        Map.entry(ModelConstants.GPT4O,             CAPABLE_CHAIN),  // legacy compat
        Map.entry(ModelConstants.GEMINI_PRO,        CAPABLE_CHAIN),
        Map.entry(ModelConstants.GEMINI_FLASH,      UTILITY_CHAIN),
        // Legacy preview model strings (backward compat for in-flight runs)
        Map.entry("gemini-3.1-pro-preview-customtools", ARCHITECT_CHAIN),
        Map.entry("gemini-3.1-flash-lite-preview",      CAPABLE_CHAIN)
    );

    /**
     * Global circuit breaker: max LLM calls per run before killing the run.
     * If a single run exceeds this, something is fundamentally wrong (spec/prompt issue).
     *
     * TODO: Explore per-tier circuit breakers (e.g., max 60 FAST calls, max 30 CAPABLE, max 10 POWER)
     * Current: single global cap of 100 across all tiers
     */
    private static final int MAX_LLM_CALLS_PER_RUN = 100;
    private final ConcurrentHashMap<String, AtomicInteger> runCallCounters = new ConcurrentHashMap<>();

    private final ApplicationContext ctx;
    private final InboxService inboxService;
    private final RunKeyStore runKeyStore;
    private final AgentProperties agentProperties;
    private final MeterRegistry meterRegistry;

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
        // ── Circuit breaker: kill run if total LLM calls exceed cap ──
        if (runId != null) {
            int callCount = runCallCounters
                    .computeIfAbsent(runId, k -> new AtomicInteger(0))
                    .incrementAndGet();
            if (callCount > MAX_LLM_CALLS_PER_RUN) {
                log.error("[LlmRouter] Circuit breaker tripped for run {} — {} LLM calls exceeded limit of {}",
                        runId, callCount, MAX_LLM_CALLS_PER_RUN);
                throw new CircuitBreakerException(
                        "Run terminated — exceeded maximum iteration limit (" + MAX_LLM_CALLS_PER_RUN +
                        " calls). This usually indicates a spec problem, not a model problem. " +
                        "Review your input and try again.");
            }
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

            Exception lastError = null;
            for (int j = 0; j < chain.size(); j++) {
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
                            log.warn("[LlmRouter] Post-overload retry also failed on {} for {}: {}",
                                provider, agentId, retryEx.getMessage());
                            Counter.builder(LLM_ERRORS).tag("provider", provider).tag("error_type", "overload_retry_failed")
                                    .register(meterRegistry).increment();
                            lastError = retryEx;
                        }
                    } else {
                        log.warn("[LlmRouter] Provider {} failed for {} (non-rate-limit, iteration={}): {}",
                            provider, agentId, iteration, e.getMessage());
                        Counter.builder(LLM_ERRORS).tag("provider", provider).tag("error_type", "provider_error")
                                .register(meterRegistry).increment();
                        lastError = e;
                    }
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
                var builder = AnthropicChatOptions.builder().model(model).maxTokens(maxTok);
                if (!callbacks.isEmpty()) builder.toolCallbacks(callbacks);
                if (toolExecEnabled != null) builder.internalToolExecutionEnabled(toolExecEnabled);
                if (finalCacheOpts != null) builder.cacheOptions(finalCacheOpts);
                yield new Prompt(prompt.getInstructions(), builder.build());
            }
            case "openai" -> {
                var builder = OpenAiChatOptions.builder().model(model);
                if (!callbacks.isEmpty()) builder.toolCallbacks(callbacks);
                if (toolExecEnabled != null) builder.internalToolExecutionEnabled(toolExecEnabled);
                if (maxTok != null) builder.maxCompletionTokens(maxTok);
                yield new Prompt(prompt.getInstructions(), builder.build());
            }
            case "google" -> {
                var builder = GoogleGenAiChatOptions.builder().model(model);
                if (maxTok != null) builder.maxOutputTokens(maxTok);
                yield new Prompt(prompt.getInstructions(), builder.build());
            }
            default -> prompt;
        };

        return resolveModel(provider, keys).call(augmented);
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
                .defaultOptions(AnthropicChatOptions.builder().build())
                .retryTemplate(getRetryTemplate())
                .observationRegistry(getObservationRegistry())
                .build();
    }

    private OpenAiChatModel buildOpenAiModel(String apiKey) {
        OpenAiApi api = OpenAiApi.builder().apiKey(apiKey).build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().build())
                .retryTemplate(getRetryTemplate())
                .observationRegistry(getObservationRegistry())
                .build();
    }

    private GoogleGenAiChatModel buildGeminiModel(String apiKey) {
        Client client = Client.builder().apiKey(apiKey).build();
        return GoogleGenAiChatModel.builder()
                .genAiClient(client)
                .defaultOptions(GoogleGenAiChatOptions.builder().build())
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
        if (model == null) return CAPABLE_CHAIN;
        List<String[]> chain = MODEL_CHAIN.get(model.toLowerCase());
        return chain != null ? chain : CAPABLE_CHAIN;
    }

    private int findIndexInChain(List<String[]> chain, String provider) {
        for (int i = 0; i < chain.size(); i++) {
            if (chain.get(i)[0].equalsIgnoreCase(provider)) return i;
        }
        return 0;
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

    // ── Circuit breaker lifecycle ───────────────────────────────────────────────

    /** Call when a run completes (success or failure) to free memory. */
    public void clearRunCallCounter(String runId) {
        if (runId != null) runCallCounters.remove(runId);
    }

    /** Get current LLM call count for a run (for observability). */
    public int getRunCallCount(String runId) {
        AtomicInteger counter = runCallCounters.get(runId);
        return counter != null ? counter.get() : 0;
    }

    // ── Exceptions ─────────────────────────────────────────────────────────────

    public static class ProviderUnavailableException extends RuntimeException {
        public ProviderUnavailableException(String message) { super(message); }
    }

    public static class MidTaskProviderFailureException extends RuntimeException {
        public MidTaskProviderFailureException(String message) { super(message); }
    }

    public static class CircuitBreakerException extends RuntimeException {
        public CircuitBreakerException(String message) { super(message); }
    }
}

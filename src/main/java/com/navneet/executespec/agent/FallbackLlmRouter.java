package com.navneet.executespec.agent;

import static com.navneet.executespec.config.CustomMetricsConfig.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.genai.Client;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelRegistry;
import com.navneet.executespec.router.ProviderHealthTracker;
import com.navneet.executespec.router.RunProviderPinStore;
import com.navneet.executespec.state.InboxService;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.anthropic.api.AnthropicCacheOptions;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Routes LLM calls with provider fallback guardrails.
 *
 * <p>Fallback chain (3-provider): anthropic → openai → google (Gemini)
 *
 * <p>Per-run API key injection: Keys are resolved via {@link ApiKeyResolver} (user → org → system
 * hierarchy) and stored in {@link RunKeyStore} at run-start. This router looks them up by runId for
 * every call — zero changes to any agent method signatures.
 *
 * <p>Guardrail rules: 1. IMMEDIATE mode (planner/reviewer): always fallback on any error 2.
 * TASK_BOUNDARY mode, iteration==0: fallback allowed (task not started) 3. TASK_BOUNDARY mode,
 * justCompacted==true: fallback allowed (checkpoint is provider-agnostic) 4. TASK_BOUNDARY mode,
 * mid-task: NO fallback — throw MidTaskProviderFailureException 5. Fallback stays within the same
 * capability tier
 */
/**
 * Shared multi-provider LLM router used by non-tool-calling agents (AccuracyAssessmentService,
 * TestPlannerAgent, TestWorkerAgent, TestReportGenerator, ApiTestSuiteAgent, ConversationAgent,
 * ContextAgent, ValidationReportGenerator, PromptOptimiserAgent, eval stack).
 *
 * <p>V2 tool-calling agents use {@code LlmRouterV2} in {@code com.navneet.executespec.v2.agent}.
 * This router handles provider failover, circuit breaking, and API-key resolution for
 * text-completion callers that do not need native tool calling.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FallbackLlmRouter {

  public enum FallbackMode {
    IMMEDIATE,
    TASK_BOUNDARY,
    NO_FALLBACK
  }

  /** Per-run LLM call counters — for observability only (no enforcement). TTL 6h, max 1000. */
  private final Cache<String, AtomicInteger> runCallCounters =
      Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(6)).maximumSize(1000).build();

  /**
   * Per-run user tier cache — populated by RunOrchestrator at run start. FREE tier forces
   * gpt-5.4-mini. TTL 6h, max 1000.
   */
  private final Cache<String, String> runUserTierCache =
      Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(6)).maximumSize(1000).build();

  private final ApplicationContext ctx;
  private final InboxService inboxService;
  private final RunKeyStore runKeyStore;
  private final AgentProperties agentProperties;
  private final MeterRegistry meterRegistry;
  private final ProviderHealthTracker healthTracker;
  private final ModelRegistry modelRegistry;
  private final RunProviderPinStore pinStore;
  private final com.navneet.executespec.router.ModelPerformanceTracker modelPerfTracker;

  /**
   * v2.5.9 Bug #4 — emit RUN_PROVIDER_SWITCHED SSE so the UI can show a banner when a fallback
   * fires. Previously this was only logged and sent to an internal agent inbox — never surfaced to
   * the user.
   */
  private final com.navneet.executespec.sse.SseBroadcaster sseBroadcaster;

  /**
   * 2026-04-26 Spring AI 1.1.4 {@code extra_body} bypass.
   *
   * <p>Spring AI 1.1.4's {@code OpenAiChatModel} adds an {@code extra_body} field to the request
   * body whenever options are non-default — even {@code temperature(0.0)} alone is enough to
   * trigger it. The OpenAI Chat Completions endpoint then rejects with {@code 400 - "Unknown
   * parameter: 'extra_body'"} (verified across gpt-4o / gpt-4o-mini / gpt-5.4 / gpt-5.4-mini).
   *
   * <p>The V2 path already routes around this via {@code OpenAiNativeToolClient}. V1 has 17+ live
   * callers (ExecutionPlannerService, ArchitectureDocGenerator, RepoArchitectureGenerator,
   * ProjectDocGeneratorService, MemoryCompactionService, RunLedgerAgent, ConversationAgent,
   * PromptOptimiserAgent, RunTitleSummarizer, ConversationController, ProjectBriefService,
   * ValidationReportGenerator, AccuracyAssessmentService, TestPlannerAgent, TestWorkerAgent,
   * TestReportGenerator) — patching the router central is one fix that covers all of them.
   *
   * <p>When provider=openai, callWithModelOptions delegates to this client (it handles both
   * text-only and tool-bearing calls and returns a Spring AI ChatResponse so downstream code stays
   * unchanged).
   */
  private final com.navneet.executespec.v2.agent.OpenAiNativeToolClient openAiNativeToolClient;

  /** Wraps a ChatResponse with the provider and model that actually served the request. */
  public record LlmResponse(ChatResponse response, String provider, String model) {}

  /** Call the model with provider fallback according to the guardrail rules. */
  public LlmResponse callWithFallbackTracked(
      String primaryProvider,
      String primaryModel,
      Prompt prompt,
      String runId,
      String agentId,
      FallbackMode mode,
      int iteration,
      boolean justCompacted) {
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

    Observation llmObs =
        Observation.createNotStarted("executespec.llm.call", getObservationRegistry())
            .lowCardinalityKeyValue("agent.id", agentId != null ? agentId : "unknown")
            .lowCardinalityKeyValue("run.id", runId != null ? runId : "unknown")
            .lowCardinalityKeyValue("fallback.mode", mode != null ? mode.name() : "unknown")
            .start();

    try {
      List<String[]> chain = resolveChain(primaryModel);

      // Per-run provider pinning: if this run was previously served by a different
      // provider, prefer that provider (shift startIndex). Pin is advisory — if pinned
      // provider is unhealthy we fall back normally and re-pin to the new provider.
      String pinnedProvider = pinStore.getPin(runId).orElse(null);
      if (pinnedProvider != null && !pinnedProvider.equalsIgnoreCase(primaryProvider)) {
        int pinnedIndex = findIndexInChain(chain, pinnedProvider);
        if (pinnedIndex >= 0 && healthTracker.isHealthy(toLlmProvider(pinnedProvider))) {
          log.debug("[FallbackLlmRouter] Run {} pinned to {} — reordering chain", runId, pinnedProvider);
          primaryProvider = chain.get(pinnedIndex)[0];
          primaryModel = chain.get(pinnedIndex)[1];
        }
      }

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
        String model = chain.get(i)[1];
        Instant callStart = Instant.now();

        llmObs.lowCardinalityKeyValue("provider", provider);
        llmObs.lowCardinalityKeyValue("model", model);

        // Skip providers disabled by org policy
        if (runKeys != null && !runKeys.isProviderAllowed(provider)) {
          log.info("[FallbackLlmRouter] Provider {} disabled by org policy — skipping", provider);
          continue;
        }

        // Skip providers marked unhealthy by global circuit breaker
        LlmProvider llmProvider = toLlmProvider(provider);
        if (!healthTracker.isHealthy(llmProvider)) {
          log.info(
              "[FallbackLlmRouter] Provider {} circuit OPEN — skipping for agent {} (iteration={})",
              provider,
              agentId,
              iteration);
          // If this was the pinned provider, clear the pin so next call re-pins to healthy one
          if (provider.equalsIgnoreCase(pinnedProvider)) {
            pinStore.clearPin(runId);
          }
          continue;
        }

        if (j > 0) {
          String fromProvider = chain.get((startIndex + j - 1) % chain.size())[0];
          llmObs.event(
              Observation.Event.of(
                  "fallback", "Falling back from " + fromProvider + " to " + provider));
          Counter.builder(LLM_FALLBACK)
              .tag("from", fromProvider)
              .tag("to", provider)
              .register(meterRegistry)
              .increment();
          // Provider switch mid-run: clear old pin so new provider gets pinned on success
          if (pinnedProvider != null && !fromProvider.equalsIgnoreCase(provider)) {
            pinStore.clearPin(runId);
            Counter.builder("executespec.llm.provider_switch")
                .tag("from", fromProvider)
                .tag("to", provider)
                .register(meterRegistry)
                .increment();
            log.warn(
                "[FallbackLlmRouter] Provider switch mid-run {} → {}: style consistency prompt will be"
                    + " used",
                fromProvider,
                provider);
          }
          log.warn(
              "[FallbackLlmRouter] Falling back: {} -> {} for agent {} (iteration={})",
              fromProvider,
              provider,
              agentId,
              iteration);
          inboxService.sendProviderFallback(
              runId,
              agentId,
              fromProvider,
              provider,
              lastError != null ? lastError.getMessage() : "unknown error");
          // v2.5.9 Bug #4 — user-visible SSE so the UI can surface a
          // "⚠ Provider switched" banner. Payload uses pipe-separated
          // {from}|{to}|{reason} so the frontend can parse + render
          // without adding a new event shape.
          try {
            String reason = lastError != null ? lastError.getMessage() : "unknown";
            String payload =
                fromProvider
                    + "|"
                    + provider
                    + "|"
                    + (reason == null ? "" : reason.replace("|", "/"));
            sseBroadcaster.emit(
                runId,
                new com.navneet.executespec.sse.RunEvent(
                    "RUN_PROVIDER_SWITCHED", runId, payload, null, Instant.now()));
          } catch (Exception sseEx) {
            log.debug("[FallbackLlmRouter] SSE emit RUN_PROVIDER_SWITCHED failed: {}", sseEx.getMessage());
          }
        }

        try {
          ChatResponse response = callWithModelOptions(provider, model, prompt, runId);
          Duration callDuration = Duration.between(callStart, Instant.now());
          Counter.builder(LLM_CALLS)
              .tag("provider", provider)
              .tag("model", model)
              .register(meterRegistry)
              .increment();
          Timer.builder(LLM_DURATION)
              .tag("provider", provider)
              .tag("model", model)
              .register(meterRegistry)
              .record(callDuration);
          healthTracker.recordSuccess(llmProvider);
          modelPerfTracker.recordCall(provider, model, callDuration.toMillis(), true);
          // Pin this run to the provider that just succeeded
          pinStore.pinIfAbsent(runId, provider);
          if (j > 0) {
            log.info("[FallbackLlmRouter] Fallback succeeded: {} for {}", provider, agentId);
          }
          recordTokenMetrics(response, provider, model, llmObs, agentId, callDuration, true);
          llmObs.lowCardinalityKeyValue("outcome", "success");
          return new LlmResponse(response, provider, model);
        } catch (Exception e) {
          if (isPerMinuteRateLimit(e)) {
            // 429 = transient quota event — record metric but do NOT open circuit breaker
            healthTracker.recordRateLimit(llmProvider);
            LlmResponse retryResult =
                handleRetryAfterSleep(
                    "rate_limit",
                    "Per-minute rate limit",
                    30_000,
                    provider,
                    model,
                    prompt,
                    runId,
                    agentId,
                    iteration,
                    callStart,
                    llmProvider,
                    llmObs);
            if (retryResult != null) return retryResult;
            lastError = e;
          } else if (isOverloaded(e)) {
            LlmResponse retryResult =
                handleRetryAfterSleep(
                    "overloaded",
                    "Provider overloaded (529)",
                    30_000,
                    provider,
                    model,
                    prompt,
                    runId,
                    agentId,
                    iteration,
                    callStart,
                    llmProvider,
                    llmObs);
            if (retryResult != null) return retryResult;
            lastError = e;
          } else if (!isFatalClientError(e) && isTransientServerError(e)) {
            // Transient server error (5xx, "Failed to generate content", network issue)
            // Apply exponential backoff before falling over — gives provider time to recover
            LlmResponse retryResult =
                handleRetryAfterSleep(
                    "server_error",
                    "Transient server error",
                    30_000,
                    provider,
                    model,
                    prompt,
                    runId,
                    agentId,
                    iteration,
                    callStart,
                    llmProvider,
                    llmObs);
            if (retryResult != null) return retryResult;
            // All retry attempts exhausted — now record the failure and fall over
            healthTracker.recordFailure(llmProvider);
            Duration failDuration = Duration.between(callStart, Instant.now());
            modelPerfTracker.recordCall(provider, model, failDuration.toMillis(), false);
            Counter.builder(LLM_ERRORS)
                .tag("provider", provider)
                .tag("error_type", "server_error_retry_exhausted")
                .register(meterRegistry)
                .increment();
            lastError = e;
          } else {
            // Fatal or unknown error (401 auth, 403 forbidden, 400 bad request, etc.)
            // Fall over immediately — retrying won't help
            healthTracker.recordFailure(llmProvider);
            Duration failDuration = Duration.between(callStart, Instant.now());
            modelPerfTracker.recordCall(provider, model, failDuration.toMillis(), false);
            log.warn(
                "[FallbackLlmRouter] Provider {} failed for {} (non-retriable, iteration={}): {}",
                provider,
                agentId,
                iteration,
                e.getMessage());
            Counter.builder(LLM_ERRORS)
                .tag("provider", provider)
                .tag("error_type", "provider_error")
                .register(meterRegistry)
                .increment();
            lastError = e;
          }
        }
      }

      // If all providers were skipped due to circuit breaker (lastError is null),
      // retry the primary provider anyway — same "force primary" pattern as ModelResolver.
      // But respect org policy: if the primary is policy-blocked, don't bypass.
      if (lastError == null) {
        if (runKeys != null && !runKeys.isProviderAllowed(primaryProvider)) {
          log.warn(
              "[FallbackLlmRouter] All providers circuit-open AND primary {} disabled by org policy —"
                  + " cannot force",
              primaryProvider);
          throw new ProviderUnavailableException(
              "All providers circuit-open and primary provider "
                  + primaryProvider
                  + " is disabled by org policy for agent "
                  + agentId);
        }
        log.warn(
            "[FallbackLlmRouter] All providers circuit-open — forcing primary {} for {}",
            primaryProvider,
            agentId);
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
          "All providers in chain exhausted for agent "
              + agentId
              + ". Last error: "
              + (lastError != null ? lastError.getMessage() : "none"));
    } catch (Exception e) {
      llmObs.error(e);
      throw e;
    } finally {
      llmObs.stop();
    }
  }

  /** Convenience wrapper for callers that only need the ChatResponse (IMMEDIATE mode agents). */
  public ChatResponse callWithFallback(
      String primaryProvider,
      String primaryModel,
      Prompt prompt,
      String runId,
      String agentId,
      FallbackMode mode,
      int iteration,
      boolean justCompacted) {
    return callWithFallbackTracked(
            primaryProvider, primaryModel, prompt, runId, agentId, mode, iteration, justCompacted)
        .response();
  }

  /**
   * Direct LLM call that bypasses health checks, fallback chains, and per-run key resolution. Uses
   * system API keys only. Intended for eval/admin tooling where isolation per model is required.
   *
   * @param provider provider name (anthropic, google, openai)
   * @param model exact model string
   * @param prompt the prompt to send
   * @return response with provider/model metadata
   */
  public LlmResponse callDirect(String provider, String model, Prompt prompt) {
    Instant start = Instant.now();
    ChatResponse response = callWithModelOptions(provider, model, prompt, "eval-system");
    Duration duration = Duration.between(start, Instant.now());
    Counter.builder(LLM_CALLS)
        .tag("provider", provider)
        .tag("model", model)
        .register(meterRegistry)
        .increment();
    Timer.builder(LLM_DURATION)
        .tag("provider", provider)
        .tag("model", model)
        .register(meterRegistry)
        .record(duration);
    return new LlmResponse(response, provider, model);
  }

  // ── Retry & metrics helpers ──────────────────────────────────────────────

  /** Records token usage counters and observation keys (best-effort, never throws). */
  private void recordTokenMetrics(
      ChatResponse response, String provider, String model, Observation obs) {
    recordTokenMetrics(response, provider, model, obs, null, null, null);
  }

  private void recordTokenMetrics(
      ChatResponse response,
      String provider,
      String model,
      Observation obs,
      String agentId,
      Duration callDuration,
      Boolean success) {
    try {
      var usage = response.getMetadata().getUsage();
      long inputTokens = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0L;
      long outputTokens = usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0L;

      Counter.builder(LLM_TOKENS_INPUT)
          .tag("provider", provider)
          .tag("model", model)
          .register(meterRegistry)
          .increment(inputTokens);
      Counter.builder(LLM_TOKENS_OUTPUT)
          .tag("provider", provider)
          .tag("model", model)
          .register(meterRegistry)
          .increment(outputTokens);

      // Distribution summaries — histogram view for SLO percentiles
      io.micrometer.core.instrument.DistributionSummary.builder(LLM_TOKENS_INPUT_DIST)
          .tag("provider", provider)
          .tag("model", model)
          .register(meterRegistry)
          .record(inputTokens);
      io.micrometer.core.instrument.DistributionSummary.builder(LLM_TOKENS_OUTPUT_DIST)
          .tag("provider", provider)
          .tag("model", model)
          .register(meterRegistry)
          .record(outputTokens);

      obs.highCardinalityKeyValue("tokens.prompt", String.valueOf(inputTokens));
      obs.highCardinalityKeyValue("tokens.completion", String.valueOf(outputTokens));
      obs.highCardinalityKeyValue(
          "tokens.total",
          String.valueOf(usage.getTotalTokens() != null ? usage.getTotalTokens() : 0L));

      // Extended latency timer with agent_tier + success for SLO tracking
      if (callDuration != null) {
        String tier = resolveAgentTier(agentId);
        String successTag = Boolean.TRUE.equals(success) ? "true" : "false";
        Timer.builder(LLM_LATENCY)
            .tag("provider", provider)
            .tag("model", model)
            .tag("agent_tier", tier)
            .tag("success", successTag)
            .register(meterRegistry)
            .record(callDuration);
      }
    } catch (Exception ignored) {
      // token extraction is best-effort
    }
  }

  /** Maps agent name to tier label for SLO tagging. */
  private static String resolveAgentTier(String agentId) {
    if (agentId == null) return "UNKNOWN";
    return switch (agentId.toLowerCase()) {
      case "planneragent", "revieweragentv2", "revieweragent" -> "PLANNING";
      case "workeragent",
              "compileragent",
              "testplanneragent",
              "testworkeragent",
              "apitestsuiteagent",
              "conflictresolveragent",
              "promptoptimiseragent",
              "validationreportgenerator" ->
          "WORKHORSE";
      default -> "LIGHTWEIGHT";
    };
  }

  /**
   * Exponential-backoff retry for rate-limit and overload scenarios.
   *
   * <p>Retry schedule — exponential backoff with 15s multiplier:
   *
   * <pre>
   *   Attempt 1: sleep  15 s
   *   Attempt 2: sleep  30 s
   *   Attempt 3: sleep  60 s
   *   Attempt 4: sleep 120 s  ← covers full 2-minute quota window
   * </pre>
   *
   * Continues retrying as long as the error is still a rate-limit or overload. Falls through
   * (returns null) only when: - a non-transient error is encountered, or - all 4 attempts are
   * exhausted.
   *
   * @return successful LlmResponse, or null to signal fall-over to next provider
   */
  private static final long[] RATE_LIMIT_BACKOFF_DELAYS_MS = {15_000, 30_000, 60_000, 120_000};

  private LlmResponse handleRetryAfterSleep(
      String eventKey,
      String eventDescription,
      long ignoredSleepMs,
      String provider,
      String model,
      Prompt prompt,
      String runId,
      String agentId,
      int iteration,
      Instant callStart,
      LlmProvider llmProvider,
      Observation llmObs) {
    llmObs.event(Observation.Event.of(eventKey, eventDescription + " on " + provider));
    int maxAttempts = RATE_LIMIT_BACKOFF_DELAYS_MS.length;

    for (int attempt = 0; attempt < maxAttempts; attempt++) {
      long sleepMs = RATE_LIMIT_BACKOFF_DELAYS_MS[attempt];
      log.warn(
          "[FallbackLlmRouter] {} on {} for {} (iteration={}) — attempt {}/{}, sleeping {}ms before retry",
          eventDescription,
          provider,
          agentId,
          iteration,
          attempt + 1,
          maxAttempts,
          sleepMs);
      try {
        Thread.sleep(sleepMs);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        throw new ProviderUnavailableException(
            "Interrupted during " + eventKey + " sleep for " + agentId);
      }
      try {
        ChatResponse retryResponse = callWithModelOptions(provider, model, prompt, runId);
        Duration retryDuration = Duration.between(callStart, Instant.now());
        Counter.builder(LLM_CALLS)
            .tag("provider", provider)
            .tag("model", model)
            .register(meterRegistry)
            .increment();
        Counter.builder(LLM_RETRY_COUNT)
            .tag("provider", provider)
            .tag("model", model)
            .register(meterRegistry)
            .increment();
        Timer.builder(LLM_DURATION)
            .tag("provider", provider)
            .tag("model", model)
            .register(meterRegistry)
            .record(retryDuration);
        healthTracker.recordSuccess(llmProvider);
        log.info(
            "[FallbackLlmRouter] Post-{} retry succeeded: {} for {} (attempt {}/{})",
            eventKey,
            provider,
            agentId,
            attempt + 1,
            maxAttempts);
        recordTokenMetrics(retryResponse, provider, model, llmObs);
        llmObs.lowCardinalityKeyValue("outcome", "success_after_retry");
        return new LlmResponse(retryResponse, provider, model);
      } catch (Exception retryEx) {
        boolean stillTransient =
            isPerMinuteRateLimit(retryEx)
                || isOverloaded(retryEx)
                || (!isFatalClientError(retryEx) && isTransientServerError(retryEx));
        if (stillTransient && attempt < maxAttempts - 1) {
          // Still transient — continue to next backoff level
          log.warn(
              "[FallbackLlmRouter] Still {} on {} (attempt {}/{}) — will retry with longer delay",
              eventKey,
              provider,
              attempt + 1,
              maxAttempts);
          continue;
        }
        // Non-transient error, fatal client error, or final attempt exhausted
        healthTracker.recordFailure(llmProvider);
        log.warn(
            "[FallbackLlmRouter] Post-{} retry exhausted on {} for {} (attempt {}/{}): {}",
            eventKey,
            provider,
            agentId,
            attempt + 1,
            maxAttempts,
            retryEx.getMessage());
        Counter.builder(LLM_ERRORS)
            .tag("provider", provider)
            .tag("error_type", eventKey + "_retry_exhausted")
            .register(meterRegistry)
            .increment();
        return null;
      }
    }
    return null;
  }

  // ── Internal ───────────────────────────────────────────────────────────────

  /**
   * Builds a provider-specific Prompt augmented with model options and calls it. Looks up the
   * per-run resolved keys from {@link RunKeyStore} and uses a dynamically-built ChatModel if the
   * user/org has provided their own key; otherwise falls through to the system singleton bean.
   */
  private ChatResponse callWithModelOptions(
      String provider, String model, Prompt prompt, String runId) {
    List<ToolCallback> toolCallbacks = List.of();
    Boolean internalToolExec = null;
    Integer maxTokens = null;
    AnthropicCacheOptions cacheOpts = null;
    AnthropicApi.ToolChoice anthropicToolChoice = null;
    Object openAiToolChoice = null;

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
      anthropicToolChoice = aco.getToolChoice();
    }
    if (prompt.getOptions() instanceof OpenAiChatOptions oaco) {
      openAiToolChoice = oaco.getToolChoice();
    }

    // Anthropic API requires max_tokens — default from agent.token-budget.max-tokens
    if (maxTokens == null) {
      maxTokens = agentProperties.getTokenBudget().getMaxTokens();
    }

    final var callbacks = toolCallbacks;
    final var toolExecEnabled = internalToolExec;
    final var maxTok = maxTokens;
    final var finalCacheOpts = cacheOpts;
    final var finalToolChoice = anthropicToolChoice;
    final var finalOpenAiToolChoice = openAiToolChoice;

    log.info(
        "[FallbackLlmRouter] callWithModelOptions: provider={} model={} tools={} internalExec={}"
            + " cacheStrategy={} toolChoice={} runId={}",
        provider,
        model,
        callbacks.size(),
        toolExecEnabled,
        finalCacheOpts != null && finalCacheOpts.getStrategy() != null
            ? finalCacheOpts.getStrategy()
            : "NONE",
        finalToolChoice != null ? finalToolChoice.type() : "auto",
        runId);

    // Resolve per-run keys (user → org → system)
    ResolvedKeys keys = runKeyStore.get(runId);

    // 2026-04-26 — OpenAI bypasses Spring AI 1.1.4 entirely (extra_body
    // bug — see field javadoc on openAiNativeToolClient). For Anthropic +
    // Gemini we still use Spring AI's ChatModel; both are fine on 1.1.4.
    ChatResponse response;
    if ("openai".equalsIgnoreCase(provider)) {
      String userKey = keys.openAiKey();
      if (userKey == null && "OWN_KEYS".equals(keys.keySourceMode())) {
        throw new ProviderUnavailableException(
            "OpenAI key not provided, mode=OWN_KEYS — no system fallback");
      }
      // userKey == null → native client falls back to its
      // @Value("${spring.ai.openai.api-key:}") system key.
      String openAiToolChoiceStr =
          finalOpenAiToolChoice == null ? null : finalOpenAiToolChoice.toString();
      response =
          openAiNativeToolClient.call(
              com.navneet.executespec.v2.agent.OpenAiNativeToolClient.OpenAiToolCallRequest
                  .builder()
                  .model(model)
                  .messages(prompt.getInstructions())
                  .tools(callbacks)
                  .toolChoice(openAiToolChoiceStr)
                  .temperature(0.0)
                  .maxCompletionTokens(maxTok)
                  .apiKey(userKey)
                  .build());
    } else {
      Prompt augmented =
          switch (provider.toLowerCase()) {
            case "anthropic" -> {
              var builder =
                  AnthropicChatOptions.builder().model(model).maxTokens(maxTok).temperature(0.0);
              if (!callbacks.isEmpty()) builder.toolCallbacks(callbacks);
              if (toolExecEnabled != null) builder.internalToolExecutionEnabled(toolExecEnabled);
              if (finalCacheOpts != null) builder.cacheOptions(finalCacheOpts);
              if (finalToolChoice != null) builder.toolChoice(finalToolChoice);
              yield new Prompt(prompt.getInstructions(), builder.build());
            }
            case "gemini" -> {
              var builder = GoogleGenAiChatOptions.builder().model(model).temperature(0.0);
              if (maxTok != null) builder.maxOutputTokens(maxTok);
              yield new Prompt(prompt.getInstructions(), builder.build());
            }
            default -> prompt;
          };
      response = resolveModel(provider, keys).call(augmented);
    }

    // Guard against null result — Gemini can return a ChatResponse with getResult() == null
    // (e.g., safety filter, empty response). Treat as a provider error to trigger fallback.
    if (response == null
        || response.getResult() == null
        || response.getResult().getOutput() == null) {
      throw new ProviderUnavailableException(
          provider
              + " returned an empty/null response (model="
              + model
              + ") — possible safety filter or empty generation");
    }
    return response;
  }

  /**
   * Returns the ChatModel for the given provider. If the resolved keys contain a user/org-level
   * key, a fresh model instance is created with that key; otherwise the system singleton bean is
   * used.
   */
  private org.springframework.ai.chat.model.ChatModel resolveModel(
      String provider, ResolvedKeys keys) {
    return switch (provider.toLowerCase()) {
      case "anthropic" -> {
        String key = keys.anthropicKey();
        if (key != null) {
          log.debug("[FallbackLlmRouter] Using user/org Anthropic key for this call");
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
          log.debug("[FallbackLlmRouter] Using user/org OpenAI key for this call");
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
      case "gemini" -> {
        String key = keys.geminiKey();
        if (key != null) {
          log.debug("[FallbackLlmRouter] Using user/org Gemini key for this call");
          yield buildGeminiModel(key);
        }
        if ("OWN_KEYS".equals(keys.keySourceMode())) {
          throw new ProviderUnavailableException(
              "Gemini key not provided, mode=OWN_KEYS — no system fallback");
        }
        GoogleGenAiChatModel systemBean =
            ctx.getBeanProvider(GoogleGenAiChatModel.class).getIfAvailable();
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
    return chain != null && !chain.isEmpty()
        ? chain
        : modelRegistry.fallbackChainForTier("CAPABLE");
  }

  private int findIndexInChain(List<String[]> chain, String provider) {
    for (int i = 0; i < chain.size(); i++) {
      if (chain.get(i)[0].equalsIgnoreCase(provider)) return i;
    }
    return 0;
  }

  // ── Provider string → enum mapping ─────────────────────────────────────────

  /** Maps the chain provider string ("anthropic", "gemini", "openai") to LlmProvider enum. */
  private static LlmProvider toLlmProvider(String provider) {
    return switch (provider.toLowerCase()) {
      case "gemini" -> LlmProvider.GEMINI;
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
    return msg.contains("529")
        || msg.toLowerCase().contains("overloaded_error")
        || msg.toLowerCase().contains("overloaded");
  }

  /**
   * Returns true for transient server-side errors that are worth retrying: 5xx HTTP errors, Google
   * "Failed to generate content", connection resets, timeouts. These failures are NOT the caller's
   * fault and often resolve within seconds.
   */
  private boolean isTransientServerError(Exception e) {
    if (e == null) return false;
    String msg = e.getMessage();
    if (msg == null) return false;
    String lower = msg.toLowerCase();
    // 5xx HTTP errors
    if (msg.contains("500") || msg.contains("502") || msg.contains("503") || msg.contains("504"))
      return true;
    // Google Gemini content generation failure (quota or transient infra)
    if (lower.contains("failed to generate content")) return true;
    // Generic server/service unavailability
    if (lower.contains("server_error")
        || lower.contains("service_unavailable")
        || lower.contains("internal error")) return true;
    // Network-level issues
    if (lower.contains("connection") || lower.contains("timeout") || lower.contains("i/o error"))
      return true;
    return false;
  }

  /**
   * Returns true for fatal client-side errors that should NOT be retried. Retrying would just
   * produce the same error (wrong key, malformed request, etc.).
   */
  private boolean isFatalClientError(Exception e) {
    if (e == null) return false;
    String msg = e.getMessage();
    if (msg == null) return false;
    String lower = msg.toLowerCase();
    // Authentication failures — key is invalid/expired, no point retrying
    if (msg.contains("401")
        || lower.contains("unauthorized")
        || lower.contains("invalid api key")
        || lower.contains("authentication_error")) return true;
    // Forbidden — org/project blocked
    if (msg.contains("403") || lower.contains("forbidden")) return true;
    // Bad request — prompt or parameter issue, retrying won't fix it
    if (msg.contains("400") || lower.contains("bad_request_error")) return true;
    return false;
  }

  // ── Call counter lifecycle ─────────────────────────────────────────────────

  /** Call when a run completes (success or failure) to free memory. */
  public void clearRunCallCounter(String runId) {
    if (runId != null) {
      runCallCounters.invalidate(runId);
      runUserTierCache.invalidate(runId);
      pinStore.clearPin(runId); // clean up Redis pin
    }
  }

  /** Get current LLM call count for a run (for observability). */
  public int getRunCallCount(String runId) {
    AtomicInteger counter = runCallCounters.getIfPresent(runId);
    return counter != null ? counter.get() : 0;
  }

  // ── FREE tier model lock ──────────────────────────────────────────────────

  /** Register the user's subscription tier for a run. Called by RunOrchestrator at run start. */
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
    public ProviderUnavailableException(String message) {
      super(message);
    }
  }

  public static class MidTaskProviderFailureException extends RuntimeException {
    public MidTaskProviderFailureException(String message) {
      super(message);
    }
  }
}

package com.navneet.executespec.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.navneet.executespec.billing.service.ManagedAiUsageService;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.cost.RunStepUsageRecorder;
import com.navneet.executespec.observability.Metrics;
import com.navneet.executespec.router.OpenRouterModelMapper;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.tool.ToolRegistry;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.anthropic.api.AnthropicCacheOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * V2 LLM router — native-tool-only, provider-agnostic single-call dispatcher.
 *
 * <p>Phase 2 scope: routes one {@link LlmRequest} to the correct provider with native tool calling,
 * returns Spring AI's {@link ChatResponse} uniformly. Tool callbacks are always sourced from {@link
 * ToolRegistry#bind(com.navneet.executespec.tool.ExecutionContext)} so concurrent tasks never share
 * state.
 *
 * <p>Provider routing:
 *
 * <ul>
 *   <li><b>anthropic</b>: Spring AI {@link AnthropicChatModel} with native {@link
 *       AnthropicChatOptions#toolCallbacks}. Verified working in Phase 0.
 *   <li><b>gemini</b>: Spring AI {@link GoogleGenAiChatModel} with native {@link
 *       GoogleGenAiChatOptions#toolCallbacks} (requires Spring AI 1.1.4+). Verified working in
 *       Phase 0.
 *   <li><b>openai</b>: {@link OpenAiNativeToolClient} — direct REST POST that bypasses Spring AI
 *       1.1.4's broken {@code extra_body} serialization. Verified working in Phase 0.
 * </ul>
 *
 * <p><b>Per-run API-key resolution (V165+):</b> the router resolves customer-configured BYOK keys
 * via {@link RunKeyContextStore} keyed by {@code request.context().runId()} and threads them into
 * provider-side dispatch. When no customer key is present the call falls through to the platform
 * default (HYBRID semantics). For Anthropic the per-key model cache ({@link #anthropicByKey}) wraps
 * Spring AI's singleton bean — keyed by SHA-256(apiKey), Caffeine LRU 128 × 1h TTL — so customer
 * prompt-caching stays on the customer's API account. OpenAI + Gemini wrappers already accept a
 * per-request key, so the router just threads it through.
 *
 * <p><strong>Audit 2026-05-12 review item M #68 — CLOSED (RL-2026-05-062):</strong> multi-provider
 * failover is now wired in {@link #maybeFailover}. After the same-provider bounded transient retry
 * is exhausted, the router walks the primary model's YAML failover chain ({@link
 * com.navneet.executespec.router.ModelRegistry#fallbackChainFor}) and tries each different provider
 * once (each with its own transient retry). Gated by {@link #FAILOVER_FLAG} (default OFF until
 * validated). BYOK-safe: a run on a customer key only fails over to a provider the customer also
 * keyed (never silently to a platform key); {@code deepseek}/{@code openrouter} are never targets.
 * A dedicated {@code ProviderHealthTracker} circuit breaker on this path is still future work (the
 * legacy {@link FallbackLlmRouter} has one); tier-based model resolution happens upstream in {@code
 * ModelResolver}.
 *
 * <p>{@code internalToolExecutionEnabled(false)} is set on every Spring-AI-routed call so the
 * WorkerAgent loop (Phase 3) keeps control of dispatch — needed for context checkpointing and
 * smoke-test integration. The OpenAI wrapper makes the same assumption (it never executes tools
 * internally).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LlmRouter {

  private final ToolRegistry toolRegistry;
  private final OpenAiNativeToolClient openAiNativeToolClient;
  private final GeminiNativeToolClient geminiNativeToolClient;
  private final Metrics metrics;
  private final RunCostTracker costTracker;
  private final RunStepUsageRecorder stepUsageRecorder;
  private final SseBroadcaster broadcaster;
  private final RunKeyContextStore keyContextStore;
  private final AnthropicForcedToolClient anthropicForcedToolClient;
  private final com.navneet.executespec.router.ModelRegistry modelRegistry;
  private final com.navneet.executespec.config.featureflag.FeatureFlagService featureFlagService;
  private final ManagedAiUsageService managedAiUsageService;

  @Qualifier("anthropicChatModel") private final ObjectProvider<AnthropicChatModel> anthropicModelProvider;

  /**
   * Provider-transport kill switch. Maven test forks force this off; live tests must opt in
   * explicitly after reserving provider budget. Initialized true so constructor-only unit tests
   * that use mocked transports remain unchanged outside a Spring context.
   */
  @Value("${executespec.live-llm.enabled:true}")
  private boolean liveLlmEnabled = true;

  @Value("${executespec.live-llm.test-mode:false}")
  private boolean liveLlmTestMode;

  @Value("${executespec.live-llm.reservation-id:}")
  private String liveLlmReservationId;

  /**
   * Multi-provider failover (closes audit M #68). When the same-provider transient retry below is
   * exhausted, fail over to the next provider(s) in the model's YAML failover chain. Gated by this
   * flag (seeded default OFF, V226 — flip ON once validated) so production is byte-identical until
   * enabled. BYOK-safe: a run already using a customer key for the primary only fails over to a
   * provider the customer ALSO has a key for (never silently crosses to a platform key), and the
   * enterprise-BYOK providers (deepseek / openrouter) are never failover targets.
   */
  static final String FAILOVER_FLAG = "router.failover.enabled";

  /**
   * Dispatch a text-only request through the canonical V2 provider transport.
   *
   * <p>This is the migration seam for legacy non-tool callers. It deliberately does not reproduce
   * {@link FallbackLlmRouter}'s circuit-breaker/fallback policy: provider selection and any
   * escalation must be decided by the caller/model policy, while transport, BYOK resolution,
   * managed-AI accounting, retries, and telemetry stay in one router. An empty tool override is
   * important here; text callers must not bind the mutable worker tool registry just to make a
   * request.
   *
   * @param provider resolved provider identifier
   * @param model resolved model identifier
   * @param messages system/user (or other text) messages
   * @param runId run/session identifier used for key and usage attribution
   * @param agentRole stable producer role for usage attribution
   * @param stepId stable producer step for usage attribution
   */
  public ChatResponse callText(
      String provider,
      String model,
      List<Message> messages,
      String runId,
      String agentRole,
      String stepId) {
    String effectiveRunId =
        (runId == null || runId.isBlank())
            ? "text-" + Integer.toHexString(messages.hashCode())
            : runId;
    String taskId =
        (agentRole == null || agentRole.isBlank()) ? "__text__" : "__text__-" + agentRole;
    return call(
        LlmRequest.builder()
            .provider(provider)
            .model(model)
            .messages(messages)
            .context(
                new com.navneet.executespec.tool.ExecutionContext(
                    effectiveRunId,
                    taskId,
                    Path.of(System.getProperty("java.io.tmpdir", "/tmp"))
                        .toAbsolutePath()
                        .normalize()))
            .toolsOverride(List.of())
            .agentRole(agentRole)
            .stepId(stepId)
            .build());
  }

  /**
   * Providers that are intentionally never failover targets (enterprise BYOK, picked on purpose).
   */
  private static final Set<String> FAILOVER_EXCLUDED_PROVIDERS =
      Set.of("deepseek", "openrouter", "fireworks");

  /**
   * Per-customer-key Anthropic model cache. Spring AI's {@link AnthropicChatModel} is a singleton
   * with the API key bound at bean construction, so per-call key override requires a fresh model
   * (cheap HTTP-client + serializer registration ~50ms first time, ~0ms cache-hit). Keyed by
   * SHA-256(apiKey) so the plaintext key never lives in the cache key. 128 entries × 1h TTL covers
   * any realistic mix of concurrent BYOK customers; eviction returns to bean-construction.
   */
  private final Cache<String, AnthropicChatModel> anthropicByKey =
      Caffeine.newBuilder().maximumSize(128).expireAfterAccess(Duration.ofHours(1)).build();

  // Auto-configured Spring AI bean for Anthropic (the one provider whose native
  // tool calling works correctly on Spring AI 1.1.4). OpenAI and Gemini both have
  // Spring AI 1.1.4 bugs and go through our own wrappers instead.
  // OpenRouter (Enterprise) — OpenAI-wire-compatible gateway, so it reuses OpenAiNativeToolClient
  // with a per-call base-URL + the verified slug map. Org BYOK keys flow through
  // RunKeyContext.keyFor("openrouter") like every other provider; this is the system fallback.
  @Value("${spring.ai.openrouter.api-key:}")
  private String openRouterApiKey;

  @Value("${spring.ai.openrouter.base-url:https://openrouter.ai/api}")
  private String openRouterBaseUrl;

  // 2026-05-28 (RL-2026-05-052) — DeepSeek native client. Same OpenAI-wire-compatible plumbing as
  // OpenRouter, but the endpoint is DeepSeek's own — no gateway hop. Org BYOK keys via
  // RunKeyContext.keyFor("deepseek") same as every other provider; this is the system fallback.
  @Value("${spring.ai.deepseek.api-key:}")
  private String deepSeekApiKey;

  @Value("${spring.ai.deepseek.base-url:https://api.deepseek.com}")
  private String deepSeekBaseUrl;

  // 2026-06-09 — Fireworks AI: OpenAI-wire-compatible US-hosted fast inference for the Chinese open
  // models. Same plumbing as DeepSeek (per-call base-URL). System fallback key; org BYOK via
  // RunKeyContext.keyFor("fireworks").
  @Value("${spring.ai.fireworks.api-key:}")
  private String fireworksApiKey;

  @Value("${spring.ai.fireworks.base-url:https://api.fireworks.ai/inference/v1}")
  private String fireworksBaseUrl;

  /**
   * Bounded same-provider retry on transient provider errors. RCA 2026-05-24: the SWE-bench Lite
   * matrix lost 3 runs to single 502/503 blips (incl. Gemini discarding 51 iterations of work on
   * one 502). Retrying the SAME provider keeps per-provider benchmark numbers honest (no silent
   * failover) and makes customer runs survive transient upstream errors.
   */
  private static final int TRANSIENT_RETRY_MAX_ATTEMPTS = 4;

  private static final long TRANSIENT_RETRY_BASE_MS = 2000L;

  /**
   * Dispatch a single LLM call.
   *
   * @return Spring AI {@link ChatResponse} (uniform shape regardless of provider)
   */
  public ChatResponse call(LlmRequest request) {
    if (!liveLlmEnabled) {
      throw new IllegalStateException(
          "Live LLM transport is disabled. Explicitly authorize the live-llm-test profile and"
              + " reserve provider budget before dispatch.");
    }
    if (liveLlmTestMode) {
      ProviderTestBudgetGuard.authorize(
          liveLlmReservationId, request.provider(), request.model(), request.context().runId());
    }
    // If the caller supplied an explicit tool list (e.g. the planner restricting
    // to just submitPlan), honor it; otherwise bind every registered V2 tool.
    List<ToolCallback> tools =
        (request.toolsOverride() != null)
            ? request.toolsOverride()
            : toolRegistry.bind(request.context());

    // Per-run BYOK lookup — null when the run hasn't pre-resolved keys
    // (test harnesses, smoke endpoints) or when the customer has no
    // configured keys. Either case falls through to platform defaults
    // for HYBRID semantics; OWN_KEYS hard-fail mode is enforced by
    // ApiKeyResolver.validateKeyAvailability at submit time.
    RunKeyContext keys = keyContextStore.get(request.context().runId());
    String resolvedKey = (keys == null) ? null : keys.keyFor(request.provider());
    RunKeyContext.Source source =
        (keys == null) ? RunKeyContext.Source.PLATFORM : keys.sourceFor(request.provider());
    // No-key fallback is reflected as PLATFORM source for the metric,
    // even when the run has BYOK configured for *another* provider.
    if (resolvedKey == null) source = RunKeyContext.Source.PLATFORM;

    ManagedAiUsageService.CallAuthorization authorization =
        managedAiUsageService.authorizeCall(
            request.context().runId(),
            request.provider(),
            request.model(),
            request.agentRole(),
            keys,
            source);
    if (authorization.modelChanged()) {
      request =
          request.toBuilder()
              .provider(authorization.provider())
              .model(authorization.model())
              .build();
      resolvedKey = (keys == null) ? null : keys.keyFor(request.provider());
      source = (keys == null) ? RunKeyContext.Source.PLATFORM : keys.sourceFor(request.provider());
      if (resolvedKey == null) source = RunKeyContext.Source.PLATFORM;
      broadcaster.emit(
          request.context().runId(),
          RunEvent.log(
              request.context().runId(), request.context().taskId(), authorization.reason()));
    }

    log.info(
        "LlmRouter -> {} {} keySource={} (tools={}, messages={})",
        request.provider(),
        request.model(),
        source,
        tools.size(),
        request.messages().size());
    try {
      return dispatchWithRetry(request, tools, resolvedKey, source, keys);
    } catch (RuntimeException primaryError) {
      ChatResponse failed = maybeFailover(request, tools, keys, source, primaryError);
      if (failed != null) return failed;
      throw primaryError;
    }
  }

  /**
   * Same-provider bounded transient retry (the original {@link #call} body). Returns on the first
   * success (recording cost + metrics) or throws the last error once attempts are exhausted / the
   * error is permanent.
   */
  private ChatResponse dispatchWithRetry(
      LlmRequest request,
      List<ToolCallback> tools,
      String resolvedKey,
      RunKeyContext.Source source,
      RunKeyContext keys) {
    RuntimeException lastError = null;
    for (int attempt = 1; attempt <= TRANSIENT_RETRY_MAX_ATTEMPTS; attempt++) {
      long start = System.currentTimeMillis();
      try {
        ChatResponse resp = dispatch(request, tools, resolvedKey);
        long elapsed = System.currentTimeMillis() - start;
        metrics.recordLlmLatency(
            request.provider(), request.model(), "ok", java.time.Duration.ofMillis(elapsed));
        metrics.recordKeySource(request.provider(), source.name());
        String runId = request.context().runId();
        String taskId = request.context().taskId();
        RunCostTracker.CallUsage callUsage =
            costTracker.record(runId, taskId, request.provider(), request.model(), resp);
        stepUsageRecorder.record(
            runId,
            taskId,
            request.agentRole(),
            request.stepId(),
            retryOf(request, attempt),
            request.provider(),
            request.model(),
            callUsage);
        managedAiUsageService.recordSuccessfulCall(
            runId,
            taskId,
            request.agentRole(),
            request.stepId(),
            request.provider(),
            request.model(),
            callUsage,
            keys,
            source);
        RunCostTracker.Snapshot snap = costTracker.snapshot(runId);
        broadcaster.emit(
            runId, RunEvent.tokenUsage(runId, taskId, request.provider(), request.model(), snap));
        log.info(
            "LlmRouter <- {} {} ok in {}ms (attempt {})",
            request.provider(),
            request.model(),
            elapsed,
            attempt);
        return resp;
      } catch (RuntimeException e) {
        long elapsed = System.currentTimeMillis() - start;
        metrics.recordLlmLatency(
            request.provider(), request.model(), "failed", java.time.Duration.ofMillis(elapsed));
        metrics.recordKeySource(request.provider(), source.name());
        log.warn(
            "LlmRouter <- {} {} FAILED in {}ms (attempt {}/{}): {}",
            request.provider(),
            request.model(),
            elapsed,
            attempt,
            TRANSIENT_RETRY_MAX_ATTEMPTS,
            e.getMessage());
        lastError = e;
        if (attempt < TRANSIENT_RETRY_MAX_ATTEMPTS && isTransientError(e)) {
          long backoffMs = TRANSIENT_RETRY_BASE_MS * (1L << (attempt - 1));
          log.warn(
              "LlmRouter transient error on {} — retrying in {}ms", request.provider(), backoffMs);
          backoffSleep(backoffMs);
          continue;
        }
        throw e;
      }
    }
    throw lastError; // unreachable: loop body always returns or throws
  }

  /** Backoff sleep between same-provider retries. Package-private so tests can no-op it (spy). */
  void backoffSleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("interrupted during retry backoff", ie);
    }
  }

  /**
   * Multi-provider failover (audit M #68). Invoked only after the same-provider retry is exhausted.
   * Walks the primary model's YAML failover chain ({@link
   * com.navneet.executespec.router.ModelRegistry#fallbackChainFor}) and tries each
   * <em>different</em> provider once (each with its own same-provider transient retry). Returns the
   * first success, or {@code null} if failover is disabled, the error isn't transient, or every
   * alternate fails.
   *
   * <p>BYOK rule (the discussed mechanism): if the primary call used a customer key ({@code source
   * != PLATFORM}) we only fail over to a provider the customer ALSO keyed — never silently fall to
   * a platform key for a BYOK run. Platform-key runs (FREE/PRO on system keys) may fail over
   * freely. {@code deepseek}/{@code openrouter} are never targets.
   */
  private ChatResponse maybeFailover(
      LlmRequest request,
      List<ToolCallback> tools,
      RunKeyContext keys,
      RunKeyContext.Source source,
      RuntimeException primaryError) {
    if (!featureFlagService.isEnabled(FAILOVER_FLAG)) return null;
    if (!isTransientError(primaryError)) return null;

    String primaryProvider = request.provider().toLowerCase(Locale.ROOT);
    boolean primaryUsedCustomerKey = source != RunKeyContext.Source.PLATFORM;
    List<String[]> chain = modelRegistry.fallbackChainFor(request.model());
    if (chain == null || chain.isEmpty()) return null;

    for (String[] entry : chain) {
      String altProvider = entry[0] == null ? "" : entry[0].toLowerCase(Locale.ROOT);
      String altModel = entry[1];
      if (altProvider.isEmpty() || altModel == null) continue;
      if (altProvider.equals(primaryProvider)) continue;
      if (FAILOVER_EXCLUDED_PROVIDERS.contains(altProvider)) continue;

      String altKey = (keys == null) ? null : keys.keyFor(altProvider);
      // Don't cross a BYOK run onto a platform key for a provider it never keyed.
      if (primaryUsedCustomerKey && altKey == null) {
        log.info(
            "LlmRouter failover skipping {} — BYOK run has no customer key for it", altProvider);
        continue;
      }
      RunKeyContext.Source altSource =
          (altKey == null || keys == null)
              ? RunKeyContext.Source.PLATFORM
              : keys.sourceFor(altProvider);

      log.warn(
          "LlmRouter FAILOVER {} {} -> {} {} (primary error: {})",
          primaryProvider,
          request.model(),
          altProvider,
          altModel,
          primaryError.getMessage());
      String runId = request.context().runId();
      broadcaster.emit(
          runId,
          RunEvent.log(
              runId,
              request.context().taskId(),
              "⚠ provider failover: "
                  + primaryProvider
                  + " unavailable, retrying on "
                  + altProvider
                  + " ("
                  + altModel
                  + ")"));
      LlmRequest altRequest =
          request.toBuilder()
              .provider(altProvider)
              .model(altModel)
              .retryOf(attributionKey(request))
              .build();
      ManagedAiUsageService.CallAuthorization authorization =
          managedAiUsageService.authorizeCall(
              altRequest.context().runId(),
              altRequest.provider(),
              altRequest.model(),
              altRequest.agentRole(),
              keys,
              altSource);
      if (authorization.modelChanged()) {
        altRequest =
            altRequest.toBuilder()
                .provider(authorization.provider())
                .model(authorization.model())
                .build();
        altKey = (keys == null) ? null : keys.keyFor(altRequest.provider());
        altSource =
            (altKey == null || keys == null)
                ? RunKeyContext.Source.PLATFORM
                : keys.sourceFor(altRequest.provider());
        broadcaster.emit(
            runId, RunEvent.log(runId, altRequest.context().taskId(), authorization.reason()));
      }
      try {
        return dispatchWithRetry(altRequest, tools, altKey, altSource, keys);
      } catch (RuntimeException altError) {
        log.warn(
            "LlmRouter failover target {} also failed: {}", altProvider, altError.getMessage());
        // try the next alternate
      }
    }
    return null;
  }

  private static String retryOf(LlmRequest request, int attempt) {
    if (attempt > 1) return attributionKey(request) + "#attempt-" + (attempt - 1);
    return request.retryOf();
  }

  private static String attributionKey(LlmRequest request) {
    String role =
        request.agentRole() == null || request.agentRole().isBlank()
            ? "unknown"
            : request.agentRole();
    String step =
        request.stepId() == null || request.stepId().isBlank() ? "llm.call" : request.stepId();
    return role + ":" + step;
  }

  /**
   * Transient = retryable on the same provider: any 5xx, connection reset / I/O timeout, or
   * rate-limit (429). Permanent client errors (400/401/403/404) are NOT retried. Walks the cause
   * chain + falls back to message sniffing since the three provider clients throw different types.
   */
  private static boolean isTransientError(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof org.springframework.web.client.HttpServerErrorException) return true;
      // 429 by TYPE (robust) — a TooManyRequests is always retryable; the message-sniff below is
      // only a fallback for clients that don't throw the typed Spring exception.
      if (t instanceof org.springframework.web.client.HttpClientErrorException.TooManyRequests)
        return true;
      if (t instanceof org.springframework.web.client.ResourceAccessException) return true;
      if (t instanceof java.net.SocketTimeoutException) return true;
      String m = t.getMessage();
      if (m != null) {
        String lm = m.toLowerCase();
        if (lm.contains("503")
            || lm.contains("502")
            || lm.contains("504")
            || lm.contains("service unavailable")
            || lm.contains("bad gateway")
            || lm.contains("gateway timeout")
            || lm.contains("overloaded")
            || lm.contains("upstream connect")
            || lm.contains("connection reset")
            || lm.contains("connection termination")
            || lm.equals("closed")
            || lm.contains("stream closed")
            || lm.contains("unexpected end")
            || lm.contains("premature eof")
            || lm.contains("timed out")
            || lm.contains("temporarily unavailable")
            || lm.contains("429")
            || lm.contains("rate limit")) {
          return true;
        }
      }
    }
    return false;
  }

  private ChatResponse dispatch(LlmRequest request, List<ToolCallback> tools, String apiKey) {
    return switch (request.provider().toLowerCase(Locale.ROOT)) {
      case "anthropic" -> callAnthropic(request, tools, apiKey);
      case "openai" -> callOpenAi(request, tools, apiKey);
      case "gemini" -> callGemini(request, tools, apiKey);
      case "openrouter" -> callOpenRouter(request, tools, apiKey);
      case "deepseek" -> callDeepSeek(request, tools, apiKey);
      case "fireworks" -> callFireworks(request, tools, apiKey);
      default ->
          throw new IllegalArgumentException(
              "Unknown provider: "
                  + request.provider()
                  + " (expected anthropic|openai|gemini|openrouter|deepseek|fireworks)");
    };
  }

  private ChatResponse callAnthropic(LlmRequest r, List<ToolCallback> tools, String apiKey) {
    // Pick the per-customer-key model when BYOK is in play; fall back to
    // the platform singleton otherwise. The cache lookup is keyed by
    // SHA-256(apiKey) so the plaintext never sits in the cache key.
    AnthropicChatModel model =
        (apiKey != null && !apiKey.isBlank())
            ? perKeyAnthropicModel(apiKey)
            : anthropicModelProvider.getIfAvailable();
    if (model == null) throw new IllegalStateException("Anthropic model not configured");
    // RL-2026-05-059 — FORCED tool-choice salvage. Spring AI's AnthropicChatOptions can't set
    // tool_choice, so when the WorkerAgent Lever-B salvage requests it (toolChoice="required"),
    // bypass to the low-level AnthropicApi with ToolChoiceAny (the model MUST emit a tool call).
    // Fail-safe: null result → fall through to the normal AnthropicChatModel path, so this can
    // never regress production Anthropic traffic and only runs on an already-failing salvage call.
    if ("required".equals(r.toolChoice())) {
      ChatResponse forced =
          anthropicForcedToolClient.forcedToolCall(
              r.model(), r.messages(), tools, r.maxTokens(), apiKey);
      if (forced != null) return forced;
      log.info("Anthropic forced-salvage produced nothing — falling through to auto tool-choice");
    }
    // Prompt caching — must-have for cost control on multi-iteration tasks.
    // Strategy CONVERSATION_HISTORY caches the system prompt, tool definitions,
    // and messages up through the last-but-one in the history. Each subsequent
    // iteration pays only for the tail delta. Cache reads are billed at 10% of
    // the input rate, cache writes at 125% (break-even ~2 iters).
    // Cache writes require ≥1024 tokens of cached content per Anthropic's API;
    // Spring AI estimates via contentLengthFunction (defaults to a token-approximating
    // function). Explicit min-content-length thresholds here ensure the resolver
    // actually attaches cache_control blocks to SYSTEM + TOOL + long USER/ASSISTANT
    // messages instead of silently skipping them. Verify activation via
    // cacheWrite / cacheRead values > 0 on the RunCostTracker log lines.
    // Per-key models get their own per-key prompt cache on Anthropic's
    // side (cache scope = API account), which is the correct isolation
    // for BYOK — Customer A's cached prompts never leak to Customer B.
    AnthropicCacheOptions cache =
        AnthropicCacheOptions.builder()
            .strategy(AnthropicCacheStrategy.CONVERSATION_HISTORY)
            .contentLengthFunction(s -> s == null ? 0 : s.length() / 4) // ~chars→tokens
            .messageTypeMinContentLength(MessageType.SYSTEM, 256)
            .messageTypeMinContentLength(MessageType.USER, 512)
            .messageTypeMinContentLength(MessageType.ASSISTANT, 512)
            .messageTypeMinContentLength(MessageType.TOOL, 512)
            .build();
    boolean dropTemperature =
        ANTHROPIC_DEPRECATED_TEMP_MODELS.contains(r.model())
            || AnthropicModelOptions.shouldOmitTemperature(r.model());
    var b =
        AnthropicChatOptions.builder()
            .model(r.model())
            .toolCallbacks(tools)
            .cacheOptions(cache)
            .internalToolExecutionEnabled(false);
    if (!dropTemperature) b.temperature(r.temperature());
    if (r.maxTokens() != null) b.maxTokens(r.maxTokens());
    try {
      return model.call(new Prompt(r.messages(), b.build()));
    } catch (RuntimeException ex) {
      if (!AnthropicModelOptions.isTemperatureDeprecatedError(ex)) throw ex;
      ANTHROPIC_DEPRECATED_TEMP_MODELS.add(r.model());
      log.warn(
          "Anthropic rejected `temperature` for model={} — retrying without it (will skip on"
              + " subsequent calls this JVM)",
          r.model());
      var retry =
          AnthropicChatOptions.builder()
              .model(r.model())
              .toolCallbacks(tools)
              .cacheOptions(cache)
              .internalToolExecutionEnabled(false);
      if (r.maxTokens() != null) retry.maxTokens(r.maxTokens());
      return model.call(new Prompt(r.messages(), retry.build()));
    }
  }

  /**
   * Per-customer-key Anthropic model — built once per unique key and cached. Key cache key is
   * SHA-256(apiKey) so the plaintext never appears as a cache key. Caffeine handles eviction (128 ×
   * 1h TTL).
   */
  private AnthropicChatModel perKeyAnthropicModel(String apiKey) {
    String hash = sha256Hex(apiKey);
    return anthropicByKey.get(
        hash,
        h -> {
          AnthropicApi api = AnthropicApi.builder().apiKey(apiKey).build();
          return AnthropicChatModel.builder().anthropicApi(api).build();
        });
  }

  private static String sha256Hex(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) hex.append(String.format("%02x", b));
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is JDK-mandatory; this branch is unreachable. If it
      // ever fires, fail loud rather than silently using a weaker hash
      // that could collide and leak keys across customers.
      throw new IllegalStateException("SHA-256 unavailable — refusing to fall back", e);
    }
  }

  /** Models whose API has rejected `temperature` with the deprecation error within this JVM. */
  private static final Set<String> ANTHROPIC_DEPRECATED_TEMP_MODELS = ConcurrentHashMap.newKeySet();

  static {
    // Pre-seed the known frontier Anthropic IDs that reject `temperature`/`top_p`/`top_k` with a
    // 400 (Opus 4.7 onward — verified live 2026-06-18). Without this, the FIRST pinned call to one
    // of these models eats one 400 + self-healing retry per JVM; pre-seeding skips `temperature`
    // directly. The runtime catch in callAnthropic still covers any future model not listed here.
    // RL-2026-06-051.
    ANTHROPIC_DEPRECATED_TEMP_MODELS.add("claude-opus-4-7");
    ANTHROPIC_DEPRECATED_TEMP_MODELS.add("claude-opus-4-8");
    ANTHROPIC_DEPRECATED_TEMP_MODELS.add("claude-sonnet-5");
  }

  private ChatResponse callGemini(LlmRequest r, List<ToolCallback> tools, String apiKey) {
    // Routes through GeminiNativeToolClient — direct REST POST, bypasses Spring AI
    // 1.1.4's broken multi-turn response parsing (Optional.get() on empty Optional
    // inside GoogleGenAiChatModel.responseCandidateToGeneration).
    //
    // When the caller supplied an explicit toolsOverride (e.g. the planner restricting
    // to submitPlan only), force Gemini to ANY mode — the model MUST emit a function
    // call. This prevents gemini-2.5-flash's "emit Python code as function call"
    // degradation under heavy prompts that otherwise causes MALFORMED_FUNCTION_CALL.
    boolean forceToolCall = r.toolsOverride() != null && !r.toolsOverride().isEmpty();
    return geminiNativeToolClient.call(
        GeminiNativeToolClient.GeminiToolCallRequest.builder()
            .model(r.model())
            .messages(r.messages())
            .tools(tools)
            .temperature(r.temperature())
            .maxOutputTokens(r.maxTokens())
            .forceToolCall(forceToolCall)
            .apiKey(apiKey)
            .readTimeoutSeconds(r.readTimeoutSeconds())
            .build());
  }

  private ChatResponse callOpenAi(LlmRequest r, List<ToolCallback> tools, String apiKey) {
    // Phase 3: pass the full Spring AI message list — wrapper serializes
    // SystemMessage / UserMessage / AssistantMessage(+toolCalls) / ToolResponseMessage
    // to OpenAI's chat-completions wire format, supporting multi-turn agent loops.
    return openAiNativeToolClient.call(
        OpenAiNativeToolClient.OpenAiToolCallRequest.builder()
            .model(r.model())
            .messages(r.messages())
            .tools(tools)
            .temperature(r.temperature())
            .maxCompletionTokens(r.maxTokens())
            .toolChoice(r.toolChoice())
            .apiKey(apiKey)
            .build());
  }

  /**
   * OpenRouter (Enterprise) — OpenAI-wire-compatible gateway. Reuses {@link OpenAiNativeToolClient}
   * with a per-call base-URL override + the verified matrix-id → slug map. The model is constrained
   * to the matrix allow-list ({@link OpenRouterModelMapper}); an unmapped model fails fast so we
   * never send an unverified slug.
   */
  private ChatResponse callOpenRouter(LlmRequest r, List<ToolCallback> tools, String apiKey) {
    String key = (apiKey != null && !apiKey.isBlank()) ? apiKey : openRouterApiKey;
    if (key == null || key.isBlank()) {
      throw new IllegalStateException(
          "OpenRouter api-key not configured (org BYOK key or spring.ai.openrouter.api-key)");
    }
    return openAiNativeToolClient.call(
        OpenAiNativeToolClient.OpenAiToolCallRequest.builder()
            .model(OpenRouterModelMapper.toSlug(r.model()))
            .messages(r.messages())
            .tools(tools)
            .temperature(r.temperature())
            .maxCompletionTokens(r.maxTokens())
            .toolChoice(r.toolChoice())
            .apiKey(key)
            .baseUrl(openRouterBaseUrl)
            .extraBody(
                Map.of(
                    "provider", Map.of("sort", "latency"),
                    "usage", Map.of("include", true)))
            .build());
  }

  /**
   * Native DeepSeek dispatcher (RL-2026-05-052, 2026-05-28). DeepSeek's API is OpenAI-wire-
   * compatible, so we reuse {@link OpenAiNativeToolClient} with a per-call base-URL. Unlike
   * OpenRouter, there's no slug-map indirection — DeepSeek's own model names are passed straight
   * through (e.g. {@code deepseek-v4-pro} → {@code deepseek-v4-pro}). The OpenRouter route stays
   * available for comparison (use model id {@code deepseek-v4-pro-or} on provider=openrouter).
   *
   * <p>Why native: in the 2026-05-28 mixed10 eval, OpenRouter-routed DeepSeek averaged ~30 min /
   * instance vs Anthropic native ~7 min — most of the gap is OpenRouter's routing overhead. Hitting
   * api.deepseek.com directly should reclaim most of that.
   */
  private ChatResponse callDeepSeek(LlmRequest r, List<ToolCallback> tools, String apiKey) {
    String key = (apiKey != null && !apiKey.isBlank()) ? apiKey : deepSeekApiKey;
    if (key == null || key.isBlank()) {
      throw new IllegalStateException(
          "DeepSeek api-key not configured (org BYOK key or spring.ai.deepseek.api-key)");
    }
    // 2026-05-28 (RL-2026-05-052) — strip our internal "-native" suffix before sending to
    // api.deepseek.com. The matrix carries `deepseek-v4-pro-native` to disambiguate from the
    // OpenRouter-routed `deepseek-v4-pro`, but the DeepSeek API itself only accepts the base
    // model names (`deepseek-v4-pro` / `deepseek-v4-flash`). Verified from the API error:
    // `"The supported API model names are deepseek-v4-pro or deepseek-v4-flash"`.
    String apiModel = stripNativeSuffix(r.model());
    return openAiNativeToolClient.call(
        OpenAiNativeToolClient.OpenAiToolCallRequest.builder()
            .model(apiModel)
            .messages(r.messages())
            .tools(tools)
            .temperature(r.temperature())
            .maxCompletionTokens(r.maxTokens())
            .toolChoice(r.toolChoice())
            .apiKey(key)
            .baseUrl(deepSeekBaseUrl)
            .build());
  }

  /**
   * Fireworks AI dispatcher (2026-06-09). OpenAI-wire-compatible US-hosted fast inference — reuses
   * {@link OpenAiNativeToolClient} with a per-call base-URL, like DeepSeek. The matrix's short
   * model ids map to Fireworks' {@code accounts/fireworks/models/…} slugs via {@code
   * FireworksModelMapper}. Added to fix the OpenRouter latency on Chinese models (mimo ~30 tok/s vs
   * Fireworks' 100+).
   */
  private ChatResponse callFireworks(LlmRequest r, List<ToolCallback> tools, String apiKey) {
    String key = (apiKey != null && !apiKey.isBlank()) ? apiKey : fireworksApiKey;
    if (key == null || key.isBlank()) {
      throw new IllegalStateException(
          "Fireworks api-key not configured (org BYOK key or spring.ai.fireworks.api-key)");
    }
    return openAiNativeToolClient.call(
        OpenAiNativeToolClient.OpenAiToolCallRequest.builder()
            .model(com.navneet.executespec.router.FireworksModelMapper.toSlug(r.model()))
            .messages(r.messages())
            .tools(tools)
            .temperature(r.temperature())
            .maxCompletionTokens(r.maxTokens())
            .toolChoice(r.toolChoice())
            .apiKey(key)
            .baseUrl(fireworksBaseUrl)
            .build());
  }

  /**
   * Strip the {@code -native} suffix we use in our model-matrix to disambiguate the native DeepSeek
   * route from the OpenRouter-routed entry of the same base model. Idempotent.
   */
  static String stripNativeSuffix(String model) {
    if (model == null) return null;
    return model.endsWith("-native")
        ? model.substring(0, model.length() - "-native".length())
        : model;
  }
}

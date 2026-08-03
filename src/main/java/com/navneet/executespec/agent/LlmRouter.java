package com.navneet.executespec.agent;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.navneet.executespec.billing.service.ManagedAiUsageService;
import com.navneet.executespec.cost.ProjectedCallBudgetExceededException;
import com.navneet.executespec.cost.ProjectedCallCostAdmission;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.cost.RunStepUsageRecorder;
import com.navneet.executespec.executioncontext.LlmExecutionDispatchGuard;
import com.navneet.executespec.executioncontext.LlmExecutionOperationAdmissionPolicy;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.executioncontext.PhysicalProviderAttemptAdmissionService;
import com.navneet.executespec.observability.Metrics;
import com.navneet.executespec.router.OpenRouterModelMapper;
import com.navneet.executespec.security.ContextSanitizer;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.tool.ToolRegistry;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
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
 * <p><b>Durable API-key resolution (V282+):</b> every customer request carries an exact execution
 * context id plus tenant scope. {@link LlmExecutionDispatchGuard} reloads its immutable
 * provider/model/key-source policy and resolves the current encrypted credential immediately before
 * each physical attempt. Missing, stale, cross-tenant, out-of-policy or exhausted contexts fail
 * before transport; OWN_KEYS never falls through to a platform key. The legacy per-run key cache
 * remains only as compatibility plumbing when enforcement is explicitly disabled. Anthropic builds
 * a bounded, single-attempt model for each admitted physical request so Spring AI cannot hide
 * retries from the durable attempt ledger; provider-side prompt caching remains scoped by the
 * resolved API account. OpenAI + Gemini wrappers accept the per-request resolved key directly.
 *
 * <p><strong>Audit 2026-05-12 review item M #68 — CLOSED (RL-2026-05-062):</strong> multi-provider
 * failover is now wired in {@link #maybeFailover}. After the same-provider bounded transient retry
 * is exhausted, the router walks the primary model's YAML failover chain ({@link
 * com.navneet.executespec.router.ModelRegistry#fallbackChainFor}) and tries each different provider
 * once (each with its own transient retry). Graduated ON by V266 and gated by {@link
 * #FAILOVER_FLAG} so operators can disable it during incident response. BYOK-safe: a run on a
 * customer key only fails over to a provider the customer also keyed (never silently to a platform
 * key). DeepSeek has an explicit provider-local recovery chain (native -> OpenRouter -> Fireworks);
 * other providers use their configured tier chain. Provider health and tier-based model resolution
 * happen in the canonical routing seams upstream in {@code ModelResolver}; this router owns the
 * actual provider dispatch and bounded retry.
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
  private final ProjectedCallCostAdmission projectedCallCostAdmission;
  private final RunStepUsageRecorder stepUsageRecorder;
  private final SseBroadcaster broadcaster;
  private final RunKeyContextStore keyContextStore;
  private final LlmExecutionDispatchGuard executionDispatchGuard;
  private final LlmExecutionOperationAdmissionPolicy operationAdmissionPolicy;
  private final PhysicalProviderAttemptAdmissionService physicalAttemptAdmission;
  private final LlmExecutionPolicyFactory executionPolicyFactory;
  private final RunKeySourceModeResolver runKeySourceModeResolver;
  private final AnthropicForcedToolClient anthropicForcedToolClient;
  private final com.navneet.executespec.router.ModelRegistry modelRegistry;
  private final com.navneet.executespec.config.featureflag.FeatureFlagService featureFlagService;
  private final ManagedAiUsageService managedAiUsageService;
  private final com.navneet.executespec.router.RunRoutePolicyService runRoutePolicyService;

  /**
   * RR-392 (2026-07-20) — the shared last-mile spend authorization. This router previously
   * re-implemented the live-LLM kill switch and the test-reservation guard inline from its own
   * {@code @Value} fields, so {@link ProviderDispatchAuthorization} — the class documented as
   * guarding "every provider-spending transport" — existed only on operator fixture controllers and
   * was not the production boundary it claimed to be. There is now exactly one implementation of
   * the rule, and it is injected here.
   */
  private final ProviderDispatchAuthorization providerDispatchAuthorization;

  /**
   * RR-044 (2026-07-22, P1) — provider exceptions can echo request headers/bodies (including API
   * keys and customer content). Every raw {@code e.getMessage()} that reaches a log line is routed
   * through the shared redactor so credentials cannot enter application logs or telemetry.
   */
  private final ContextSanitizer contextSanitizer;

  private final AnthropicAttemptModelFactory anthropicAttemptModelFactory;

  @Value("${spring.ai.anthropic.api-key:}")
  private String platformAnthropicApiKey;

  @Value("${executespec.llm.execution-context-enforcement.enabled:true}")
  private boolean executionContextEnforcementEnabled = true;

  /**
   * Multi-provider failover (closes audit M #68). When the same-provider transient retry below is
   * exhausted, fail over to the next provider(s) in the model's YAML failover chain. Gated by this
   * flag (graduated ON by V266; operators may disable it for incident response). BYOK-safe: a run
   * already using a customer key for the primary only fails over to a provider the customer ALSO
   * has a key for (never silently crosses to a platform key). DeepSeek's recovery path is native
   * DeepSeek -> OpenRouter -> Fireworks, subject to key and policy availability.
   */
  static final String FAILOVER_FLAG = "router.failover.enabled";

  /**
   * Dispatch a text-only request through the canonical V2 provider transport.
   *
   * <p>This is the canonical seam for text-only callers. The same bounded retry and flag-gated
   * failover policy apply, while transport, BYOK resolution, managed-AI accounting, and telemetry
   * stay in one router. An empty tool override is important here; text callers must not bind the
   * mutable worker tool registry just to make a request.
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
    return callText(provider, model, messages, runId, agentRole, stepId, (Integer) null);
  }

  /** Text seam that preserves an already-bound process-local operation authority. */
  public ChatResponse callText(
      String provider,
      String model,
      List<Message> messages,
      ExecutionContext context,
      String agentRole,
      String stepId) {
    return callTextWithContext(
        provider,
        model,
        messages,
        context,
        agentRole,
        stepId,
        null,
        LlmRequest.PromptCacheStrategy.DEFAULT,
        false);
  }

  /** Customer-safe text seam: the durable tenant/policy identity is explicit on the request. */
  public ChatResponse callText(
      String provider,
      String model,
      List<Message> messages,
      String runId,
      String agentRole,
      String stepId,
      LlmExecutionReference reference) {
    return callText(
        provider,
        model,
        messages,
        runId,
        agentRole,
        stepId,
        null,
        LlmRequest.PromptCacheStrategy.DEFAULT,
        reference,
        false);
  }

  /** Customer-safe strict text seam for an exact provider/model pin. */
  public ChatResponse callTextStrict(
      String provider,
      String model,
      List<Message> messages,
      String runId,
      String agentRole,
      String stepId,
      Integer maxTokens,
      LlmExecutionReference reference) {
    return callText(
        provider,
        model,
        messages,
        runId,
        agentRole,
        stepId,
        maxTokens,
        LlmRequest.PromptCacheStrategy.DEFAULT,
        reference,
        true);
  }

  /**
   * Customer-safe strict text seam with an explicit transient-retry profile.
   *
   * <p>Use this for human-triggered, bounded project operations that must not inherit the long
   * unattended pipeline retry ladder.
   */
  public ChatResponse callTextStrict(
      String provider,
      String model,
      List<Message> messages,
      String runId,
      String agentRole,
      String stepId,
      Integer maxTokens,
      LlmExecutionReference reference,
      LlmRequest.RetryProfile retryProfile) {
    return callTextStrict(
        provider,
        model,
        messages,
        runId,
        agentRole,
        stepId,
        maxTokens,
        reference,
        retryProfile,
        null);
  }

  /**
   * Strict text seam that signals a durable caller only after physical-attempt admission and local
   * transport preflight, immediately before provider I/O.
   */
  public ChatResponse callTextStrict(
      String provider,
      String model,
      List<Message> messages,
      String runId,
      String agentRole,
      String stepId,
      Integer maxTokens,
      LlmExecutionReference reference,
      LlmRequest.RetryProfile retryProfile,
      Runnable physicalAttemptStartedCallback) {
    return callText(
        provider,
        model,
        messages,
        runId,
        agentRole,
        stepId,
        maxTokens,
        LlmRequest.PromptCacheStrategy.DEFAULT,
        reference,
        true,
        retryProfile,
        physicalAttemptStartedCallback);
  }

  /** Customer-safe bounded text seam with an explicit prompt-cache policy. */
  public ChatResponse callText(
      String provider,
      String model,
      List<Message> messages,
      String runId,
      String agentRole,
      String stepId,
      Integer maxTokens,
      LlmRequest.PromptCacheStrategy promptCacheStrategy,
      LlmExecutionReference reference) {
    return callText(
        provider,
        model,
        messages,
        runId,
        agentRole,
        stepId,
        maxTokens,
        promptCacheStrategy,
        reference,
        false);
  }

  /** Text seam with strict provider pinning; transient failures never fail over cross-provider. */
  public ChatResponse callTextStrict(
      String provider,
      String model,
      List<Message> messages,
      String runId,
      String agentRole,
      String stepId) {
    return callTextStrict(provider, model, messages, runId, agentRole, stepId, null);
  }

  public ChatResponse callTextStrict(
      String provider,
      String model,
      List<Message> messages,
      String runId,
      String agentRole,
      String stepId,
      Integer maxTokens) {
    return callText(
        provider,
        model,
        messages,
        runId,
        agentRole,
        stepId,
        maxTokens,
        LlmRequest.PromptCacheStrategy.DEFAULT,
        null,
        true);
  }

  /** Text seam with an explicit output cap for compatibility surfaces with a bounded contract. */
  public ChatResponse callText(
      String provider,
      String model,
      List<Message> messages,
      String runId,
      String agentRole,
      String stepId,
      Integer maxTokens) {
    return callText(
        provider,
        model,
        messages,
        runId,
        agentRole,
        stepId,
        maxTokens,
        LlmRequest.PromptCacheStrategy.DEFAULT);
  }

  /**
   * Text seam with an explicit output cap and provider-neutral prompt-cache intent. The cache
   * strategy is consumed only by providers that support it; all other transports retain their
   * existing request shape.
   */
  public ChatResponse callText(
      String provider,
      String model,
      List<Message> messages,
      String runId,
      String agentRole,
      String stepId,
      Integer maxTokens,
      LlmRequest.PromptCacheStrategy promptCacheStrategy) {
    return callText(
        provider,
        model,
        messages,
        runId,
        agentRole,
        stepId,
        maxTokens,
        promptCacheStrategy,
        null,
        false);
  }

  private ChatResponse callText(
      String provider,
      String model,
      List<Message> messages,
      String runId,
      String agentRole,
      String stepId,
      Integer maxTokens,
      LlmRequest.PromptCacheStrategy promptCacheStrategy,
      LlmExecutionReference reference,
      boolean strictProvider) {
    return callText(
        provider,
        model,
        messages,
        runId,
        agentRole,
        stepId,
        maxTokens,
        promptCacheStrategy,
        reference,
        strictProvider,
        null);
  }

  private ChatResponse callText(
      String provider,
      String model,
      List<Message> messages,
      String runId,
      String agentRole,
      String stepId,
      Integer maxTokens,
      LlmRequest.PromptCacheStrategy promptCacheStrategy,
      LlmExecutionReference reference,
      boolean strictProvider,
      LlmRequest.RetryProfile retryProfile) {
    return callText(
        provider,
        model,
        messages,
        runId,
        agentRole,
        stepId,
        maxTokens,
        promptCacheStrategy,
        reference,
        strictProvider,
        retryProfile,
        null);
  }

  private ChatResponse callText(
      String provider,
      String model,
      List<Message> messages,
      String runId,
      String agentRole,
      String stepId,
      Integer maxTokens,
      LlmRequest.PromptCacheStrategy promptCacheStrategy,
      LlmExecutionReference reference,
      boolean strictProvider,
      LlmRequest.RetryProfile retryProfile,
      Runnable physicalAttemptStartedCallback) {
    String effectiveRunId =
        (runId == null || runId.isBlank())
            ? "text-" + Integer.toHexString(messages.hashCode())
            : runId;
    if (reference == null) {
      reference = executionPolicyFactory.findForRun(effectiveRunId).orElse(null);
    }
    ExecutionContext context =
        new ExecutionContext(
            effectiveRunId,
            "__text__",
            Path.of(System.getProperty("java.io.tmpdir", "/tmp")).toAbsolutePath().normalize(),
            reference);
    return callTextWithContext(
        provider,
        model,
        messages,
        context,
        agentRole,
        stepId,
        maxTokens,
        promptCacheStrategy,
        strictProvider,
        retryProfile,
        physicalAttemptStartedCallback);
  }

  private ChatResponse callTextWithContext(
      String provider,
      String model,
      List<Message> messages,
      ExecutionContext context,
      String agentRole,
      String stepId,
      Integer maxTokens,
      LlmRequest.PromptCacheStrategy promptCacheStrategy,
      boolean strictProvider) {
    return callTextWithContext(
        provider,
        model,
        messages,
        context,
        agentRole,
        stepId,
        maxTokens,
        promptCacheStrategy,
        strictProvider,
        null);
  }

  private ChatResponse callTextWithContext(
      String provider,
      String model,
      List<Message> messages,
      ExecutionContext context,
      String agentRole,
      String stepId,
      Integer maxTokens,
      LlmRequest.PromptCacheStrategy promptCacheStrategy,
      boolean strictProvider,
      LlmRequest.RetryProfile retryProfile) {
    return callTextWithContext(
        provider,
        model,
        messages,
        context,
        agentRole,
        stepId,
        maxTokens,
        promptCacheStrategy,
        strictProvider,
        retryProfile,
        null);
  }

  private ChatResponse callTextWithContext(
      String provider,
      String model,
      List<Message> messages,
      ExecutionContext context,
      String agentRole,
      String stepId,
      Integer maxTokens,
      LlmRequest.PromptCacheStrategy promptCacheStrategy,
      boolean strictProvider,
      LlmRequest.RetryProfile retryProfile,
      Runnable physicalAttemptStartedCallback) {
    if (context == null) throw new IllegalArgumentException("context required");
    String taskId =
        (agentRole == null || agentRole.isBlank()) ? "__text__" : "__text__-" + agentRole;
    // Anthropic's current API requires an explicit max_tokens field even for text-only calls.
    // Resolve the model's YAML output cap here so compatibility callers cannot accidentally emit
    // an invalid request when they omit the optional argument. Mock/unknown registries return 0;
    // preserve the transport default in that case.
    Integer effectiveMaxTokens = maxTokens;
    if (effectiveMaxTokens == null && modelRegistry != null) {
      int configuredMaxTokens = modelRegistry.maxOutputForModel(model);
      if (configuredMaxTokens > 0) effectiveMaxTokens = configuredMaxTokens;
    }
    return call(
        LlmRequest.builder()
            .provider(provider)
            .model(model)
            .messages(messages)
            .context(context.child(taskId))
            .toolsOverride(List.of())
            .agentRole(agentRole)
            .stepId(stepId)
            .maxTokens(effectiveMaxTokens)
            .strictProvider(strictProvider)
            .retryProfile(retryProfile)
            .physicalAttemptStartedCallback(physicalAttemptStartedCallback)
            .promptCacheStrategy(promptCacheStrategy)
            .build());
  }

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

  /** Native DeepSeek thinking/tool responses can take longer than other OpenAI-wire providers. */
  @Value("${spring.ai.deepseek.read-timeout-seconds:300}")
  private int deepSeekReadTimeoutSeconds = 300;

  /**
   * Fireworks long agentic tool turns (500K-token GLM contexts) routinely exceed the generic 120s
   * OpenAI-wire window; the provider keeps generating and bills the discarded completion while the
   * client cancels at exactly 120s. Same window as native DeepSeek.
   */
  @Value("${spring.ai.fireworks.read-timeout-seconds:300}")
  private int fireworksReadTimeoutSeconds = 300;

  /**
   * Gemini thinking-model worker calls routinely exceed the client's static 60s default (calibrated
   * 2026-05-08 from cmatrix run 84252898: T3 timed out after a 17-min worker phase). This window
   * replaces the WorkerAgent-side global read-timeout pin: the router resolves it for EVERY Gemini
   * call, so callers no longer need a provider-blind override to avoid the 60s default.
   */
  @Value("${spring.ai.gemini.read-timeout-seconds:300}")
  private int geminiReadTimeoutSeconds = 300;

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
   * failover) and makes customer runs survive transient upstream errors. This is public because
   * durable logical-call budgets must reserve the exact same physical-attempt envelope.
   *
   * <p>Since 2026-08-03 the envelope is surface-split via {@link LlmRequest#retryProfile()}: this
   * constant is the PIPELINE (default) ceiling; interactive chat turns use the smaller {@link
   * #INTERACTIVE_TRANSIENT_RETRY_MAX_ATTEMPTS}.
   */
  public static final int TRANSIENT_RETRY_MAX_ATTEMPTS = 6;

  /**
   * Retry ceiling for human-facing chat turns ({@link LlmRequest.RetryProfile#INTERACTIVE}). A
   * person is watching these calls: the pipeline ladder's ~155s worst-case backoff tail reads as a
   * hang, so interactive turns keep the pre-campaign envelope (4 attempts, 2s/4s/8s — worst case
   * ~14s of backoff) and surface the failure to the user instead of waiting out a long throttling
   * window.
   */
  public static final int INTERACTIVE_TRANSIENT_RETRY_MAX_ATTEMPTS = 4;

  /** One retry for optional post-run enrichment whose deterministic fallback is already usable. */
  public static final int AUXILIARY_TRANSIENT_RETRY_MAX_ATTEMPTS = 2;

  /**
   * PIPELINE spacing — accuracy over latency (2026-08-01, TB 2.1 campaign): one initial call plus
   * five retries at 5s/15s/25s/45s/65s. The old 2s/4s/8s ladder re-hit a still-pressured provider
   * fast enough to exhaust the envelope inside one throttling window; wider gaps trade wall clock
   * for a materially higher chance the transient clears before the envelope is spent. Nobody is
   * watching a worker/planner/reviewer call, so the wall-clock cost is acceptable there.
   */
  private static final long[] PIPELINE_RETRY_BACKOFF_MS = {
    5_000L, 15_000L, 25_000L, 45_000L, 65_000L
  };

  /** INTERACTIVE spacing — the pre-campaign 2s/4s/8s ladder (RL-2026-05-029). */
  private static final long[] INTERACTIVE_RETRY_BACKOFF_MS = {2_000L, 4_000L, 8_000L};

  /** AUXILIARY spacing — fail over to the caller's structured fallback after one short retry. */
  private static final long[] AUXILIARY_RETRY_BACKOFF_MS = {2_000L};

  private static final Duration DEFAULT_TRANSPORT_TIMEOUT = Duration.ofSeconds(120);
  private static final Duration ANTHROPIC_TRANSPORT_TIMEOUT = Duration.ofSeconds(300);
  private static final int ANTHROPIC_FALLBACK_MAX_TOKENS = 16_000;
  private static final Duration TRANSPORT_SAFETY_MARGIN = Duration.ofSeconds(15);

  /**
   * Dispatch a single LLM call.
   *
   * @return Spring AI {@link ChatResponse} (uniform shape regardless of provider)
   */
  public ChatResponse call(LlmRequest request) {
    // RR-392 — one shared fail-closed authorization for every provider-spending dispatch.
    providerDispatchAuthorization.authorize(
        request.provider(), request.model(), request.context().runId());
    if (executionContextEnforcementEnabled && request.llmExecutionContextId() == null) {
      throw new com.navneet.executespec.executioncontext.LlmDispatchPolicyException(
          com.navneet.executespec.executioncontext.LlmDispatchPolicyException.Reason
              .CONTEXT_REQUIRED,
          "Provider dispatch requires an explicit durable customer or PLATFORM_INTERNAL context");
    }
    var routePolicy = runRoutePolicyService.forRun(request.context().runId());
    request = enforceDurableRoutePolicy(request, routePolicy);
    // If the caller supplied an explicit tool list (e.g. the planner restricting
    // to just submitPlan), honor it; otherwise bind every registered V2 tool.
    List<ToolCallback> tools =
        (request.toolsOverride() != null)
            ? request.toolsOverride()
            : toolRegistry.bind(request.context());
    LlmExecutionOperationAdmissionPolicy.Decision operationAdmissionDecision =
        operationAdmissionPolicy.decide(request);
    if (operationAdmissionDecision.rejectsBeforeTransport()) {
      throw new IllegalStateException(
          "LLM operation admission rejected before transport: "
              + operationAdmissionDecision.reason());
    }

    // V282 exact dispatch policy takes precedence over every cache. Legacy internal/test callers
    // without a durable reference keep the old path only until their explicit PLATFORM_INTERNAL
    // contexts are migrated; customer entry points attach the reference before reaching here.
    LlmExecutionDispatchGuard.ResolvedDispatch durableDispatch =
        request.llmExecutionContextId() == null
            ? null
            : operationAdmissionDecision.usesAtomicV1()
                ? executionDispatchGuard.reauthorize(request)
                : executionDispatchGuard.authorize(request);

    // Per-run BYOK lookup — null when the run hasn't pre-resolved keys
    // (test harnesses, smoke endpoints) or when the customer has no
    // configured keys. Either case falls through to platform defaults
    // for HYBRID semantics; OWN_KEYS hard-fail mode is enforced by
    // ApiKeyResolver.validateKeyAvailability at submit time.
    RunKeyContext keys =
        durableDispatch == null
            ? keyContextStore.get(request.context().runId())
            : contextForManagedAi(durableDispatch);
    String resolvedKey =
        durableDispatch == null
            ? ((keys == null) ? null : keys.keyFor(request.provider()))
            : durableDispatch.apiKey();
    RunKeyContext.Source source =
        durableDispatch == null
            ? ((keys == null) ? RunKeyContext.Source.PLATFORM : keys.sourceFor(request.provider()))
            : durableDispatch.keySource();
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
      if (routePolicy.exactLock() || request.strictProvider()) {
        throw new IllegalStateException(
            "Exact run route cannot be changed by managed-AI policy: " + authorization.reason());
      }
      request =
          request.toBuilder()
              .provider(authorization.provider())
              .model(authorization.model())
              .build();
      if (durableDispatch != null) {
        durableDispatch = executionDispatchGuard.reauthorize(request);
        resolvedKey = durableDispatch.apiKey();
        source = durableDispatch.keySource();
        keys = contextForManagedAi(durableDispatch);
      } else {
        resolvedKey = (keys == null) ? null : keys.keyFor(request.provider());
        source =
            (keys == null) ? RunKeyContext.Source.PLATFORM : keys.sourceFor(request.provider());
      }
      if (resolvedKey == null) source = RunKeyContext.Source.PLATFORM;
      broadcaster.emit(
          request.context().runId(),
          RunEvent.log(
              request.context().runId(), request.context().taskId(), authorization.reason()));
    }

    // BYOK boundary — fail CLOSED (deep-dive §7 #2, 2026-07-17). If this call is about to dispatch
    // on a PLATFORM key but the run is a pure-BYOK (OWN_KEYS) run, the customer key context was
    // lost
    // (cache eviction / Redis outage / cross-JVM resume) or routing moved to a provider the
    // customer
    // did not key. Either way, spending on platform credentials would violate the customer's cost +
    // data boundary. Refuse the dispatch rather than silently fall back. HYBRID/SYSTEM runs keep
    // their intentional platform fallback. Guarded so a missing/unattributable run stays
    // permissive.
    if (source == RunKeyContext.Source.PLATFORM
        && runKeySourceModeResolver.isOwnKeysRun(request.context().runId())) {
      throw new ByokContextUnavailableException(
          "BYOK_CONTEXT_UNAVAILABLE: run "
              + request.context().runId()
              + " is OWN_KEYS but no customer key is available for provider "
              + request.provider()
              + " at dispatch — refusing to spend on platform credentials.");
    }

    log.info(
        "LlmRouter -> {} {} keySource={} (tools={}, messages={})",
        request.provider(),
        request.model(),
        source,
        tools.size(),
        request.messages().size());
    try {
      return dispatchWithRetry(
          request, tools, resolvedKey, source, keys, operationAdmissionDecision);
    } catch (RuntimeException primaryError) {
      ChatResponse failed =
          maybeFailover(request, tools, keys, source, primaryError, operationAdmissionDecision);
      if (failed != null) return failed;
      throw primaryError;
    }
  }

  private static RunKeyContext contextForManagedAi(
      LlmExecutionDispatchGuard.ResolvedDispatch dispatch) {
    var context = dispatch.context();
    RunKeyContext.Source source = dispatch.keySource();
    String key = dispatch.apiKey();
    return new RunKeyContext(
        "anthropic".equalsIgnoreCase(context.getRequestedProvider()) ? key : null,
        "openai".equalsIgnoreCase(context.getRequestedProvider()) ? key : null,
        "gemini".equalsIgnoreCase(context.getRequestedProvider()) ? key : null,
        "openrouter".equalsIgnoreCase(context.getRequestedProvider()) ? key : null,
        "deepseek".equalsIgnoreCase(context.getRequestedProvider()) ? key : null,
        "fireworks".equalsIgnoreCase(context.getRequestedProvider()) ? key : null,
        source,
        source,
        source,
        source,
        source,
        source,
        context.getUserId(),
        context.getCredentialOrgId());
  }

  LlmRequest enforceDurableRoutePolicy(
      LlmRequest request, com.navneet.executespec.router.RunRoutePolicyService.RoutePolicy policy) {
    if (policy == null || !policy.exactLock()) return request;
    String expectedProvider = normalizeProvider(policy.requestedProvider());
    String actualProvider = normalizeProvider(request.provider());
    String expectedModel = modelRegistry.canonicalize(policy.requestedModel());
    String actualModel = modelRegistry.canonicalize(request.model());
    if (!expectedProvider.equals(actualProvider) || !expectedModel.equals(actualModel)) {
      throw new IllegalStateException(
          "Exact run route violation: requested "
              + expectedProvider
              + "/"
              + expectedModel
              + " but dispatch attempted "
              + actualProvider
              + "/"
              + actualModel);
    }
    return request.strictProvider() ? request : request.toBuilder().strictProvider(true).build();
  }

  private static String normalizeProvider(String provider) {
    if (provider == null) return "";
    String normalized = provider.trim().toLowerCase(Locale.ROOT);
    return "google".equals(normalized) ? "gemini" : normalized;
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
      RunKeyContext keys,
      LlmExecutionOperationAdmissionPolicy.Decision operationAdmissionDecision) {
    RuntimeException lastError = null;
    boolean geminiAuditedTransport = "gemini".equalsIgnoreCase(request.provider());
    boolean anthropicAuditedTransport = "anthropic".equalsIgnoreCase(request.provider());
    boolean providerOwnsPhysicalAccounting = geminiAuditedTransport || anthropicAuditedTransport;
    AtomicInteger physicalAttemptOrdinal = new AtomicInteger();
    AtomicReference<String> parentAttemptId = new AtomicReference<>(request.retryOf());
    GeminiNativeToolClient.AuditContext geminiAuditContext =
        geminiAuditedTransport
            ? new GeminiNativeToolClient.AuditContext(
                request, source.name(), physicalAttemptOrdinal, parentAttemptId)
            : null;
    PhysicalAttemptChain anthropicAuditContext =
        anthropicAuditedTransport
            ? new PhysicalAttemptChain(
                request,
                resolvedKey,
                source,
                keys,
                operationAdmissionDecision,
                physicalAttemptOrdinal,
                parentAttemptId)
            : null;
    final int maxAttempts = transientRetryMaxAttempts(request);
    final long[] backoffLadderMs = transientRetryBackoffMs(request);
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      // This is the last common seam before every physical chat transport. The request already
      // carries the managed-AI or failover route, so price that effective provider/model rather
      // than the worker's earlier requested route. Rechecking each bounded retry also observes any
      // spend recorded by another in-process call before this attempt starts.
      projectedCallCostAdmission.admit(request, tools);
      // Audit intent is write-ahead and fail-closed. If this durable append fails, the provider
      // transport is never invoked and no unaccounted Enterprise spend can occur.
      PhysicalDispatch physicalDispatch =
          providerOwnsPhysicalAccounting
              ? null
              : preparePhysicalAttempt(
                  request,
                  resolvedKey,
                  source,
                  attempt,
                  attemptKind(request, attempt),
                  parentAttemptId.get(),
                  operationAdmissionDecision);
      RunKeyContext.Source physicalSource =
          physicalDispatch == null ? source : physicalDispatch.keySource();
      long start = System.currentTimeMillis();
      ChatResponse resp;
      CanonicalDispatchResult canonicalResult = null;
      try {
        // Keep this try/catch strictly around the provider transport. Once a provider has returned
        // successfully, no cost/accounting/telemetry projection is allowed to turn that physical
        // success into a provider failure or cause the exact same request to be spent again.
        if (geminiAuditedTransport) {
          GeminiNativeToolClient.AuditedCallResult geminiResult =
              callGeminiAudited(request, tools, resolvedKey, geminiAuditContext);
          canonicalResult =
              new CanonicalDispatchResult(
                  geminiResult.response(),
                  geminiResult.callUsage(),
                  geminiResult.usageEventId(),
                  geminiResult.costProjectionAvailable(),
                  source);
          resp = canonicalResult.response();
        } else if (anthropicAuditedTransport) {
          canonicalResult =
              callAnthropicAudited(request, tools, resolvedKey, anthropicAuditContext);
          resp = canonicalResult.response();
        } else {
          resp =
              dispatch(
                  request, tools, physicalDispatch.apiKey(), physicalDispatch.transportDeadline());
        }
      } catch (RuntimeException e) {
        long elapsed = System.currentTimeMillis() - start;
        metrics.recordLlmLatency(
            request.provider(), request.model(), "failed", java.time.Duration.ofMillis(elapsed));
        metrics.recordKeySource(request.provider(), physicalSource.name());
        if (!providerOwnsPhysicalAccounting) {
          if (physicalDispatch.atomicV1()) {
            stepUsageRecorder.recordFailure(physicalDispatch.handle(), request, elapsed, e);
          } else {
            stepUsageRecorder.recordFailure(
                request,
                request.provider(),
                request.model(),
                physicalDispatch.keySource().name(),
                attempt,
                elapsed,
                e);
          }
          parentAttemptId.set(physicalDispatch.handle().physicalAttemptId());
        }
        log.warn(
            "LlmRouter <- {} {} FAILED in {}ms (attempt {}/{}): {}",
            request.provider(),
            request.model(),
            elapsed,
            attempt,
            maxAttempts,
            contextSanitizer.sanitizeException(e));
        lastError = e;
        if (attempt < maxAttempts && isTransientError(e)) {
          long backoffMs = backoffLadderMs[Math.min(attempt - 1, backoffLadderMs.length - 1)];
          log.warn(
              "LlmRouter transient error on {} — retrying in {}ms", request.provider(), backoffMs);
          backoffSleep(backoffMs);
          continue;
        }
        throw e;
      }

      long elapsed = System.currentTimeMillis() - start;
      final int physicalAttempt = attempt;
      RunKeyContext.Source attemptSource =
          canonicalResult != null && canonicalResult.keySource() != null
              ? canonicalResult.keySource()
              : physicalDispatch == null ? source : physicalDispatch.keySource();
      recordPostSuccessMetric(
          "transport_metrics",
          () -> {
            metrics.recordLlmLatency(
                request.provider(), request.model(), "ok", java.time.Duration.ofMillis(elapsed));
            metrics.recordKeySource(request.provider(), attemptSource.name());
          });
      String runId = request.context().runId();
      String taskId = request.context().taskId();

      RunCostTracker.CallUsage callUsage;
      boolean costProjectionAvailable;
      String canonicalUsageEventId;
      if (canonicalResult != null) {
        callUsage = canonicalResult.callUsage();
        costProjectionAvailable = canonicalResult.costProjectionAvailable();
        canonicalUsageEventId = canonicalResult.usageEventId();
        metrics.recordLlmPostSuccessProjection(
            "run_cost", costProjectionAvailable ? "ok" : "failed");
      } else {
        costProjectionAvailable = true;
        canonicalUsageEventId = null;
        try {
          callUsage = costTracker.record(runId, taskId, request.provider(), request.model(), resp);
          metrics.recordLlmPostSuccessProjection("run_cost", "ok");
        } catch (RuntimeException projectionFailure) {
          costProjectionAvailable = false;
          callUsage = unknownCostUsage(request, resp);
          recordPostSuccessProjectionFailure(request, "run_cost", projectionFailure);
        }
      }

      final RunCostTracker.CallUsage usageForLedger = callUsage;
      final String[] usageEventId = {canonicalUsageEventId};
      if (canonicalResult == null) {
        recordPostSuccessMetric(
            "attempt_ledger",
            () -> {
              if (physicalDispatch.atomicV1()) {
                usageEventId[0] =
                    stepUsageRecorder.recordSuccess(
                        physicalDispatch.handle(), request, elapsed, usageForLedger, resp);
              } else {
                usageEventId[0] =
                    stepUsageRecorder.recordSuccess(
                        request,
                        request.provider(),
                        request.model(),
                        physicalDispatch.keySource().name(),
                        physicalAttempt,
                        elapsed,
                        usageForLedger,
                        resp);
              }
            });
        parentAttemptId.set(physicalDispatch.handle().physicalAttemptId());
      }

      if (costProjectionAvailable) {
        recordPostSuccessMetric(
            "managed_ai",
            () ->
                managedAiUsageService.recordSuccessfulCall(
                    usageEventId[0],
                    runId,
                    taskId,
                    request.agentRole(),
                    request.stepId(),
                    request.provider(),
                    request.model(),
                    usageForLedger,
                    keys,
                    attemptSource));
      } else {
        // A negative sentinel in the canonical row makes the cost explicitly unknown. Do not feed
        // it into the managed-credit projection; reconciliation will expose the missing projection.
        metrics.recordLlmPostSuccessProjection("managed_ai", "skipped_unknown_cost");
      }

      recordPostSuccessMetric(
          "token_event",
          () -> {
            RunCostTracker.Snapshot snap = costTracker.snapshot(runId);
            broadcaster.emit(
                runId,
                RunEvent.tokenUsage(runId, taskId, request.provider(), request.model(), snap));
          });
      log.info(
          "LlmRouter <- {} {} ok in {}ms (attempt {})",
          request.provider(),
          request.model(),
          elapsed,
          attempt);
      return resp;
    }
    throw lastError; // unreachable: loop body always returns or throws
  }

  private void recordPostSuccessMetric(String projection, Runnable action) {
    try {
      action.run();
      metrics.recordLlmPostSuccessProjection(projection, "ok");
    } catch (RuntimeException projectionFailure) {
      metrics.recordLlmPostSuccessProjection(projection, "failed");
      log.error(
          "LLM post-success projection failed projection={} (provider response will not be"
              + " retried): {}",
          projection,
          projectionFailure.getClass().getSimpleName());
    }
  }

  private void recordPostSuccessProjectionFailure(
      LlmRequest request, String projection, RuntimeException failure) {
    metrics.recordLlmPostSuccessProjection(projection, "failed");
    log.error(
        "LLM post-success projection failed run={} task={} provider={} projection={}"
            + " (provider response will not be retried): {}",
        request.context().runId(),
        request.context().taskId(),
        request.provider(),
        projection,
        failure.getClass().getSimpleName());
  }

  /**
   * Preserve the successful physical-attempt fact when the in-memory cost projection itself fails.
   * Cost {@code -1} is the report's explicit unknown-cost sentinel; it must never be projected into
   * managed credits. Generic token fields remain useful evidence without inventing a price.
   */
  private static RunCostTracker.CallUsage unknownCostUsage(LlmRequest request, ChatResponse resp) {
    var usage = resp == null || resp.getMetadata() == null ? null : resp.getMetadata().getUsage();
    long input = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
    long output =
        usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
    long cacheWrite = responseMetadataLong(resp, "cache_creation_input_tokens");
    long cacheRead = responseMetadataLong(resp, "cache_read_input_tokens");
    long thinking = responseMetadataLong(resp, "thinking_tokens");
    long reasoning = thinking > 0 ? thinking : responseMetadataLong(resp, "reasoning_tokens");
    if (thinking > 0) output = Math.min(Long.MAX_VALUE, output + thinking);
    return new RunCostTracker.CallUsage(
        request.provider(),
        request.model(),
        input,
        output,
        cacheWrite,
        cacheRead,
        -1.0,
        0,
        reasoning);
  }

  private static long responseMetadataLong(ChatResponse response, String key) {
    if (response == null || response.getMetadata() == null) return 0;
    Object value = response.getMetadata().get(key);
    return value instanceof Number number ? Math.max(0, number.longValue()) : 0;
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
   * freely. The configured DeepSeek chain is native -> OpenRouter -> Fireworks.
   */
  private ChatResponse maybeFailover(
      LlmRequest request,
      List<ToolCallback> tools,
      RunKeyContext keys,
      RunKeyContext.Source source,
      RuntimeException primaryError,
      LlmExecutionOperationAdmissionPolicy.Decision operationAdmissionDecision) {
    if (request.strictProvider() || !featureFlagService.isEnabled(FAILOVER_FLAG)) return null;
    if (!isTransientError(primaryError)) return null;

    String primaryProvider = request.provider().toLowerCase(Locale.ROOT);
    boolean primaryUsedCustomerKey = source != RunKeyContext.Source.PLATFORM;
    List<String[]> chain = modelRegistry.fallbackChainFor(request.model(), primaryProvider);
    if (chain == null || chain.isEmpty()) return null;

    for (String[] entry : chain) {
      String altProvider = entry[0] == null ? "" : entry[0].toLowerCase(Locale.ROOT);
      String altModel = entry[1];
      if (altProvider.isEmpty() || altModel == null) continue;
      if (altProvider.equals(primaryProvider)) continue;
      LlmRequest altRequest =
          request.toBuilder()
              .provider(altProvider)
              .model(altModel)
              .retryOf(attributionKey(request))
              .build();
      LlmExecutionDispatchGuard.ResolvedDispatch durableAlt = null;
      if (request.llmExecutionContextId() != null) {
        try {
          durableAlt = executionDispatchGuard.reauthorize(altRequest);
        } catch (com.navneet.executespec.executioncontext.LlmDispatchPolicyException rejected) {
          log.info(
              "LlmRouter failover skipping {}/{} — durable policy rejected it ({})",
              altProvider,
              altModel,
              rejected.reason());
          continue;
        }
      }
      String altKey =
          durableAlt == null
              ? ((keys == null) ? null : keys.keyFor(altProvider))
              : durableAlt.apiKey();
      // Don't cross a BYOK run onto a platform key for a provider it never keyed.
      if (durableAlt == null && primaryUsedCustomerKey && altKey == null) {
        log.info(
            "LlmRouter failover skipping {} — BYOK run has no customer key for it", altProvider);
        continue;
      }
      RunKeyContext.Source altSource =
          durableAlt == null
              ? ((altKey == null || keys == null)
                  ? RunKeyContext.Source.PLATFORM
                  : keys.sourceFor(altProvider))
              : durableAlt.keySource();

      log.warn(
          "LlmRouter FAILOVER {} {} -> {} {} (primary error: {})",
          primaryProvider,
          request.model(),
          altProvider,
          altModel,
          contextSanitizer.sanitizeException(primaryError));
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
        if (durableAlt != null) {
          durableAlt = executionDispatchGuard.reauthorize(altRequest);
          altKey = durableAlt.apiKey();
          altSource = durableAlt.keySource();
        } else {
          altKey = (keys == null) ? null : keys.keyFor(altRequest.provider());
          altSource =
              (altKey == null || keys == null)
                  ? RunKeyContext.Source.PLATFORM
                  : keys.sourceFor(altRequest.provider());
        }
        broadcaster.emit(
            runId, RunEvent.log(runId, altRequest.context().taskId(), authorization.reason()));
      }
      try {
        return dispatchWithRetry(
            altRequest, tools, altKey, altSource, keys, operationAdmissionDecision);
      } catch (ProjectedCallBudgetExceededException budgetExceeded) {
        // A route that cannot fit the customer's ceiling is not a provider failure. Do not mask it
        // behind the primary 5xx or walk to another billable transport.
        throw budgetExceeded;
      } catch (RuntimeException altError) {
        log.warn(
            "LlmRouter failover target {} also failed: {}",
            altProvider,
            contextSanitizer.sanitizeException(altError));
        // try the next alternate
      }
    }
    return null;
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

  private PhysicalDispatch preparePhysicalAttempt(
      LlmRequest request,
      String legacyApiKey,
      RunKeyContext.Source legacySource,
      int attemptNumber,
      String attemptKind,
      String parentAttemptId,
      LlmExecutionOperationAdmissionPolicy.Decision decision) {
    if (decision.usesAtomicV1()) {
      PhysicalProviderAttemptAdmissionService.AdmittedPhysicalAttempt admitted =
          physicalAttemptAdmission.admitChatAttempt(
              request,
              attemptNumber,
              attemptKind,
              parentAttemptId,
              transportTimeout(request),
              TRANSPORT_SAFETY_MARGIN);
      return new PhysicalDispatch(
          admitted.handle(),
          admitted.apiKey(),
          admitted.keySource(),
          admitted.transportDeadline(),
          true);
    }
    RunStepUsageRecorder.AttemptHandle handle =
        "anthropic".equalsIgnoreCase(request.provider())
            ? stepUsageRecorder.prepareAttempt(
                request,
                request.provider(),
                request.model(),
                legacySource.name(),
                attemptNumber,
                attemptKind,
                parentAttemptId)
            : stepUsageRecorder.prepareAttempt(
                request, request.provider(), request.model(), legacySource.name(), attemptNumber);
    return new PhysicalDispatch(handle, legacyApiKey, legacySource, null, false);
  }

  private static String attemptKind(LlmRequest request, int ordinal) {
    if (ordinal > 1) return "RETRY";
    return request.retryOf() == null || request.retryOf().isBlank() ? "PRIMARY" : "FAILOVER";
  }

  /**
   * Effective transport timeout for one physical call: the per-provider window, tightened (never
   * widened) by an explicit per-call {@code readTimeoutSeconds} override. The router — not the
   * caller — owns how long a provider socket may stay open, so a caller carrying a large remaining
   * wall-clock budget (e.g. a worker hours from its absolute deadline) cannot inflate an HTTP read
   * timeout past the provider's window. Callers use the override only to SHORTEN the window, e.g.
   * deadline-capped worker calls near the shutdown reserve. (2026-08-03, productionizing the TB
   * campaign's global worker 300s pin.)
   */
  private Duration transportTimeout(LlmRequest request) {
    Duration window = providerTransportWindow(request.provider());
    if (request.readTimeoutSeconds() != null && request.readTimeoutSeconds() > 0) {
      Duration override = Duration.ofSeconds(request.readTimeoutSeconds());
      return override.compareTo(window) < 0 ? override : window;
    }
    return window;
  }

  private Duration providerTransportWindow(String provider) {
    if ("anthropic".equalsIgnoreCase(provider)) return ANTHROPIC_TRANSPORT_TIMEOUT;
    if ("deepseek".equalsIgnoreCase(provider)) {
      return Duration.ofSeconds(Math.max(1, deepSeekReadTimeoutSeconds));
    }
    if ("fireworks".equalsIgnoreCase(provider)) {
      return Duration.ofSeconds(Math.max(1, fireworksReadTimeoutSeconds));
    }
    if ("gemini".equalsIgnoreCase(provider)) {
      return Duration.ofSeconds(Math.max(1, geminiReadTimeoutSeconds));
    }
    return DEFAULT_TRANSPORT_TIMEOUT;
  }

  /** PIPELINE unless the request explicitly opts into the interactive envelope. */
  private static int transientRetryMaxAttempts(LlmRequest request) {
    return switch (request.retryProfile() == null
        ? LlmRequest.RetryProfile.PIPELINE
        : request.retryProfile()) {
      case INTERACTIVE -> INTERACTIVE_TRANSIENT_RETRY_MAX_ATTEMPTS;
      case AUXILIARY -> AUXILIARY_TRANSIENT_RETRY_MAX_ATTEMPTS;
      case PIPELINE -> TRANSIENT_RETRY_MAX_ATTEMPTS;
    };
  }

  private static long[] transientRetryBackoffMs(LlmRequest request) {
    return switch (request.retryProfile() == null
        ? LlmRequest.RetryProfile.PIPELINE
        : request.retryProfile()) {
      case INTERACTIVE -> INTERACTIVE_RETRY_BACKOFF_MS;
      case AUXILIARY -> AUXILIARY_RETRY_BACKOFF_MS;
      case PIPELINE -> PIPELINE_RETRY_BACKOFF_MS;
    };
  }

  /**
   * The read timeout actually handed to a wire client: the V1-admitted transport deadline when one
   * exists, otherwise the resolved per-provider window (already min-capped by any caller override
   * in {@link #transportTimeout}). Never the raw caller override — that let a provider-blind caller
   * widen a provider's socket window (2026-08-03).
   */
  private Integer admittedReadTimeoutSeconds(LlmRequest request, Instant transportDeadline) {
    if (transportDeadline == null) return (int) transportTimeout(request).toSeconds();
    long millis = Duration.between(Instant.now(), transportDeadline).toMillis();
    return (int) Math.max(1, Math.min(Integer.MAX_VALUE, (millis + 999L) / 1000L));
  }

  /**
   * Transient = retryable on the same provider: any 5xx, connection reset / I/O timeout, or
   * rate-limit (429). Permanent client errors (400/401/403/404) are NOT retried. Walks the cause
   * chain + falls back to message sniffing since the three provider clients throw different types.
   */
  static boolean isTransientError(Throwable e) {
    if (ProviderStartFenceException.causedBy(e)) return false;
    // User cancellation is terminal for this dispatch. HTTP clients commonly wrap an interrupt in
    // ResourceAccessException, which is otherwise retryable; classifying it as transient caused a
    // cancelled provider call to enter the 2s/4s/8s retry loop and risk more spend.
    if (Thread.currentThread().isInterrupted()) return false;
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof InterruptedException
          || t instanceof java.io.InterruptedIOException
          || t instanceof java.nio.channels.ClosedByInterruptException) return false;
    }
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

  private ChatResponse dispatch(
      LlmRequest request, List<ToolCallback> tools, String apiKey, Instant transportDeadline) {
    return switch (request.provider().toLowerCase(Locale.ROOT)) {
      case "anthropic" -> callAnthropic(request, tools, apiKey);
      case "openai" -> callOpenAi(request, tools, apiKey, transportDeadline);
      case "gemini" -> callGemini(request, tools, apiKey);
      case "openrouter" -> callOpenRouter(request, tools, apiKey, transportDeadline);
      case "deepseek" -> callDeepSeek(request, tools, apiKey, transportDeadline);
      case "fireworks" -> callFireworks(request, tools, apiKey, transportDeadline);
      default ->
          throw new IllegalArgumentException(
              "Unknown provider: "
                  + request.provider()
                  + " (expected anthropic|openai|gemini|openrouter|deepseek|fireworks)");
    };
  }

  private ChatResponse callAnthropic(LlmRequest r, List<ToolCallback> tools, String apiKey) {
    return callAnthropicInternal(r, tools, apiKey, null).response();
  }

  private CanonicalDispatchResult callAnthropicAudited(
      LlmRequest r, List<ToolCallback> tools, String apiKey, PhysicalAttemptChain auditContext) {
    return callAnthropicInternal(r, tools, apiKey, auditContext);
  }

  private CanonicalDispatchResult callAnthropicInternal(
      LlmRequest r, List<ToolCallback> tools, String apiKey, PhysicalAttemptChain auditContext) {
    // Anthropic requires max_tokens on every request. Normalize at the provider transport seam so
    // direct operation-context calls, managed-route substitutions, and Anthropic failover targets
    // all receive a valid request without rewriting the logical request used by audit/fencing.
    int configuredMaxTokens =
        r.maxTokens() != null ? r.maxTokens() : modelRegistry.maxOutputForModel(r.model());
    int effectiveMaxTokens =
        configuredMaxTokens > 0 ? configuredMaxTokens : ANTHROPIC_FALLBACK_MAX_TOKENS;
    // Pick the per-customer-key model when BYOK is in play; fall back to
    // the platform singleton otherwise. The cache lookup is keyed by
    // SHA-256(apiKey) so the plaintext never sits in the cache key.
    boolean deprecatedTemperatureModel =
        ANTHROPIC_DEPRECATED_TEMP_MODELS.contains(r.model())
            || AnthropicModelOptions.shouldOmitTemperature(r.model());
    // RL-2026-05-059 — FORCED tool-choice salvage. Spring AI's AnthropicChatOptions can't set
    // tool_choice, so when the WorkerAgent Lever-B salvage requests it (toolChoice="required"),
    // bypass to the low-level AnthropicApi with ToolChoiceAny (the model MUST emit a tool call).
    // Fail-safe: null result → fall through to the normal AnthropicChatModel path, so this can
    // never regress production Anthropic traffic and only runs on an already-failing salvage call.
    if ("required".equals(r.toolChoice())
        && anthropicForcedToolClient.canAttempt(r.messages(), tools, apiKey)) {
      // The forced-tool seam can return a paid but semantically unusable HTTP 200 and then fall
      // through to the normal Anthropic transport. Admit each physical request independently so
      // that first response's newly recorded spend can fence the fallback request.
      projectedCallCostAdmission.admit(r, tools);
      PhysicalDispatch forcedAttempt = prepareAnthropicAttempt(auditContext);
      String forcedApiKey = forcedAttempt == null ? apiKey : forcedAttempt.apiKey();
      long forcedStartedAt = System.currentTimeMillis();
      AnthropicForcedToolClient.ForcedToolCallOutcome forced;
      try {
        forced =
            anthropicForcedToolClient.forcedToolCallOutcome(
                r.model(),
                r.messages(),
                tools,
                effectiveMaxTokens,
                forcedApiKey,
                admittedReadTimeoutSeconds(
                    r, forcedAttempt == null ? null : forcedAttempt.transportDeadline()),
                r.physicalAttemptStartedCallback());
      } catch (RuntimeException forcedFailure) {
        recordAnthropicFailure(auditContext, forcedAttempt, forcedStartedAt, forcedFailure);
        throw forcedFailure;
      }
      if (forced.failure() != null || forced.response() == null) {
        recordAnthropicFailure(
            auditContext,
            forcedAttempt,
            forcedStartedAt,
            forced.failure() == null
                ? new IllegalStateException("Anthropic forced tool attempt returned no response")
                : forced.failure());
      } else if (forced.usable()) {
        return completeAnthropicSuccess(
            r, forced.response(), auditContext, forcedAttempt, forcedStartedAt);
      } else {
        completeAnthropicIntermediateSuccess(
            r, forced.response(), auditContext, forcedAttempt, forcedStartedAt);
      }
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
    AnthropicCacheStrategy cacheStrategy =
        switch (r.promptCacheStrategy()) {
          case SYSTEM_ONLY -> AnthropicCacheStrategy.SYSTEM_ONLY;
          case CONVERSATION_HISTORY, DEFAULT -> AnthropicCacheStrategy.CONVERSATION_HISTORY;
          case NONE -> null;
        };
    AnthropicCacheOptions cache =
        cacheStrategy == null
            ? null
            : AnthropicCacheOptions.builder()
                .strategy(cacheStrategy)
                .contentLengthFunction(s -> s == null ? 0 : s.length() / 4) // ~chars→tokens
                .messageTypeMinContentLength(MessageType.SYSTEM, 256)
                .messageTypeMinContentLength(MessageType.USER, 512)
                .messageTypeMinContentLength(MessageType.ASSISTANT, 512)
                .messageTypeMinContentLength(MessageType.TOOL, 512)
                .build();
    boolean dropTemperature = deprecatedTemperatureModel;
    var b =
        AnthropicChatOptions.builder()
            .model(r.model())
            .toolCallbacks(tools)
            .internalToolExecutionEnabled(false);
    if (cache != null) b.cacheOptions(cache);
    if (!dropTemperature) b.temperature(r.temperature());
    b.maxTokens(effectiveMaxTokens);
    projectedCallCostAdmission.admit(r, tools);
    PhysicalDispatch primaryAttempt = prepareAnthropicAttempt(auditContext);
    String primaryApiKey = primaryAttempt == null ? apiKey : primaryAttempt.apiKey();
    long primaryStartedAt = System.currentTimeMillis();
    ChatResponse primaryResponse;
    try {
      AnthropicChatModel model =
          anthropicModelForAttempt(r, primaryApiKey, effectiveMaxTokens, primaryAttempt);
      Prompt primaryPrompt = new Prompt(r.messages(), b.build());
      r.signalPhysicalAttemptStarted();
      primaryResponse = model.call(primaryPrompt);
      if (primaryResponse == null)
        throw new IllegalStateException("Anthropic returned empty response");
    } catch (RuntimeException ex) {
      recordAnthropicFailure(auditContext, primaryAttempt, primaryStartedAt, ex);
      if (ProviderStartFenceException.causedBy(ex)) throw ex;
      if (!AnthropicModelOptions.isTemperatureDeprecatedError(ex)) throw ex;
      ANTHROPIC_DEPRECATED_TEMP_MODELS.add(r.model());
      log.warn(
          "Anthropic rejected `temperature` for model={} — retrying without it (will skip on"
              + " subsequent calls this JVM)",
          r.model());
      projectedCallCostAdmission.admit(r, tools);
      PhysicalDispatch retryAttempt = prepareAnthropicAttempt(auditContext);
      String retryApiKey = retryAttempt == null ? apiKey : retryAttempt.apiKey();
      var retry =
          AnthropicChatOptions.builder()
              .model(r.model())
              .toolCallbacks(tools)
              .internalToolExecutionEnabled(false);
      if (cache != null) retry.cacheOptions(cache);
      retry.maxTokens(effectiveMaxTokens);
      long retryStartedAt = System.currentTimeMillis();
      ChatResponse retryResponse;
      try {
        AnthropicChatModel model =
            anthropicModelForAttempt(r, retryApiKey, effectiveMaxTokens, retryAttempt);
        Prompt retryPrompt = new Prompt(r.messages(), retry.build());
        r.signalPhysicalAttemptStarted();
        retryResponse = model.call(retryPrompt);
        if (retryResponse == null)
          throw new IllegalStateException("Anthropic returned empty response");
      } catch (RuntimeException retryFailure) {
        recordAnthropicFailure(auditContext, retryAttempt, retryStartedAt, retryFailure);
        throw retryFailure;
      }
      return completeAnthropicSuccess(r, retryResponse, auditContext, retryAttempt, retryStartedAt);
    }
    return completeAnthropicSuccess(
        r, primaryResponse, auditContext, primaryAttempt, primaryStartedAt);
  }

  private PhysicalDispatch prepareAnthropicAttempt(PhysicalAttemptChain auditContext) {
    if (auditContext == null) return null;
    int ordinal = auditContext.attemptOrdinal().incrementAndGet();
    String attemptKind =
        ordinal == 1 && auditContext.request().retryOf() != null
            ? "FAILOVER"
            : ordinal == 1 ? "PRIMARY" : "RETRY";
    return preparePhysicalAttempt(
        auditContext.request(),
        auditContext.legacyApiKey(),
        auditContext.legacySource(),
        ordinal,
        attemptKind,
        auditContext.parentAttemptId().get(),
        auditContext.admissionDecision());
  }

  private void recordAnthropicFailure(
      PhysicalAttemptChain auditContext,
      PhysicalDispatch attempt,
      long startedAt,
      Throwable failure) {
    if (auditContext == null || attempt == null) return;
    stepUsageRecorder.recordFailure(
        attempt.handle(),
        auditContext.request(),
        Math.max(0, System.currentTimeMillis() - startedAt),
        failure);
    auditContext.parentAttemptId().set(attempt.handle().physicalAttemptId());
  }

  private CanonicalDispatchResult completeAnthropicSuccess(
      LlmRequest request,
      ChatResponse response,
      PhysicalAttemptChain auditContext,
      PhysicalDispatch attempt,
      long startedAt) {
    if (auditContext == null || attempt == null) {
      return new CanonicalDispatchResult(
          response, null, null, true, auditContext == null ? null : auditContext.legacySource());
    }
    RunCostTracker.CallUsage usage;
    boolean costProjectionAvailable = true;
    try {
      usage =
          costTracker.record(
              request.context().runId(),
              request.context().taskId(),
              "anthropic",
              request.model(),
              response);
    } catch (RuntimeException projectionFailure) {
      costProjectionAvailable = false;
      usage = unknownCostUsage(request, response);
      log.error(
          "Anthropic final HTTP success cost projection failed; provider response will not be"
              + " retried: {}",
          projectionFailure.getClass().getSimpleName());
    }
    String usageEventId =
        stepUsageRecorder.recordSuccess(
            attempt.handle(),
            request,
            Math.max(0, System.currentTimeMillis() - startedAt),
            usage,
            response);
    auditContext.parentAttemptId().set(attempt.handle().physicalAttemptId());
    return new CanonicalDispatchResult(
        response, usage, usageEventId, costProjectionAvailable, attempt.keySource());
  }

  /** Account a paid but semantically unusable HTTP 200 before issuing the fallback request. */
  private void completeAnthropicIntermediateSuccess(
      LlmRequest request,
      ChatResponse response,
      PhysicalAttemptChain auditContext,
      PhysicalDispatch attempt,
      long startedAt) {
    CanonicalDispatchResult result =
        completeAnthropicSuccess(request, response, auditContext, attempt, startedAt);
    if (!result.costProjectionAvailable()) {
      metrics.recordLlmPostSuccessProjection("managed_ai", "skipped_unknown_cost");
      return;
    }
    recordPostSuccessMetric(
        "managed_ai",
        () ->
            managedAiUsageService.recordSuccessfulCall(
                result.usageEventId(),
                request.context().runId(),
                request.context().taskId(),
                request.agentRole(),
                request.stepId(),
                "anthropic",
                request.model(),
                result.callUsage(),
                auditContext.keys(),
                attempt.keySource()));
  }

  /** Build one bounded, non-retrying Anthropic transport for one admitted physical attempt. */
  private AnthropicChatModel anthropicModelForAttempt(
      LlmRequest request,
      String apiKeyOverride,
      Integer maxTokens,
      PhysicalDispatch physicalAttempt) {
    String key =
        (apiKeyOverride != null && !apiKeyOverride.isBlank())
            ? apiKeyOverride
            : platformAnthropicApiKey;
    int timeoutSeconds =
        admittedReadTimeoutSeconds(
            request, physicalAttempt == null ? null : physicalAttempt.transportDeadline());
    return anthropicAttemptModelFactory.create(key, request.model(), maxTokens, timeoutSeconds);
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
    ANTHROPIC_DEPRECATED_TEMP_MODELS.add("claude-fable-5");
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
    return geminiNativeToolClient.call(geminiRequest(r, tools, apiKey));
  }

  private GeminiNativeToolClient.AuditedCallResult callGeminiAudited(
      LlmRequest r,
      List<ToolCallback> tools,
      String apiKey,
      GeminiNativeToolClient.AuditContext auditContext) {
    return geminiNativeToolClient.callAudited(geminiRequest(r, tools, apiKey), auditContext);
  }

  private GeminiNativeToolClient.GeminiToolCallRequest geminiRequest(
      LlmRequest r, List<ToolCallback> tools, String apiKey) {
    boolean forceToolCall = r.toolsOverride() != null && !r.toolsOverride().isEmpty();
    return GeminiNativeToolClient.GeminiToolCallRequest.builder()
        .model(r.model())
        .messages(r.messages())
        .tools(tools)
        .temperature(r.temperature())
        .maxOutputTokens(r.maxTokens())
        .forceToolCall(forceToolCall)
        .toolChoice(r.toolChoice())
        .apiKey(apiKey)
        // Resolved (window-capped) value, not the raw override: a null override previously fell
        // through to the Gemini client's static 60s default, which is too tight for worker calls.
        .readTimeoutSeconds((int) transportTimeout(r).toSeconds())
        .build();
  }

  private ChatResponse callOpenAi(
      LlmRequest r, List<ToolCallback> tools, String apiKey, Instant transportDeadline) {
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
            .physicalAttemptStartedCallback(r.physicalAttemptStartedCallback())
            .readTimeoutSeconds(admittedReadTimeoutSeconds(r, transportDeadline))
            .build());
  }

  /**
   * OpenRouter (Enterprise) — OpenAI-wire-compatible gateway. Reuses {@link OpenAiNativeToolClient}
   * with a per-call base-URL override + the verified matrix-id → slug map. The model is constrained
   * to the matrix allow-list ({@link OpenRouterModelMapper}); an unmapped model fails fast so we
   * never send an unverified slug.
   */
  private ChatResponse callOpenRouter(
      LlmRequest r, List<ToolCallback> tools, String apiKey, Instant transportDeadline) {
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
            .physicalAttemptStartedCallback(r.physicalAttemptStartedCallback())
            .readTimeoutSeconds(admittedReadTimeoutSeconds(r, transportDeadline))
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
  private ChatResponse callDeepSeek(
      LlmRequest r, List<ToolCallback> tools, String apiKey, Instant transportDeadline) {
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
            .physicalAttemptStartedCallback(r.physicalAttemptStartedCallback())
            .readTimeoutSeconds(admittedReadTimeoutSeconds(r, transportDeadline))
            .baseUrl(deepSeekBaseUrl)
            .extraBody(deepSeekExtraBody(r))
            .build());
  }

  static Map<String, Object> deepSeekExtraBody(LlmRequest request) {
    // Outline, classifier, and task expansion are bounded structured transforms, not open-ended
    // reasoning jobs. DeepSeek thinking can consume their entire output allowance before emitting
    // JSON/tool arguments, turning metadata planning into fallback or expensive expansion stalls.
    // Coherence is likewise a small bounded schema-patch decision. DeepSeek thinking consumed the
    // entire
    // enforced 1,500-token ceiling before emitting patchPlan. Reviewer passes have the same
    // failure mode at larger scale: run dc6df05f spent three separate 8K completions entirely on
    // hidden reasoning, emitted no tool call, and failed the review protocol after nine minutes.
    // Review is already an iterative inspect/edit/verify loop; disable single-call thinking for
    // that bounded tool loop so the model must expose an action instead of silently consuming the
    // complete output budget. Normal generation workers retain provider-default thinking.
    String taskId =
        request.context() == null || request.context().taskId() == null
            ? ""
            : request.context().taskId();
    if ("SmartClassifierAgent".equals(request.agentRole())
        || "OutlinePlannerAgent".equals(request.agentRole())
        || "TaskExpanderAgent".equals(request.agentRole())
        || "CoherencePlannerAgent".equals(request.agentRole())
        || "required".equals(request.toolChoice())
        || taskId.contains("-reviewer-")) {
      return Map.of("thinking", Map.of("type", "disabled"));
    }
    return Map.of();
  }

  /**
   * Fireworks AI dispatcher (2026-06-09). OpenAI-wire-compatible US-hosted fast inference — reuses
   * {@link OpenAiNativeToolClient} with a per-call base-URL, like DeepSeek. The matrix's short
   * model ids map to Fireworks' {@code accounts/fireworks/models/…} slugs via {@code
   * FireworksModelMapper}. Added to fix the OpenRouter latency on Chinese models (mimo ~30 tok/s vs
   * Fireworks' 100+).
   */
  private ChatResponse callFireworks(
      LlmRequest r, List<ToolCallback> tools, String apiKey, Instant transportDeadline) {
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
            .physicalAttemptStartedCallback(r.physicalAttemptStartedCallback())
            .readTimeoutSeconds(admittedReadTimeoutSeconds(r, transportDeadline))
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

  private record PhysicalAttemptChain(
      LlmRequest request,
      String legacyApiKey,
      RunKeyContext.Source legacySource,
      RunKeyContext keys,
      LlmExecutionOperationAdmissionPolicy.Decision admissionDecision,
      AtomicInteger attemptOrdinal,
      AtomicReference<String> parentAttemptId) {}

  private record PhysicalDispatch(
      RunStepUsageRecorder.AttemptHandle handle,
      String apiKey,
      RunKeyContext.Source keySource,
      Instant transportDeadline,
      boolean atomicV1) {}

  private record CanonicalDispatchResult(
      ChatResponse response,
      RunCostTracker.CallUsage callUsage,
      String usageEventId,
      boolean costProjectionAvailable,
      RunKeyContext.Source keySource) {}
}

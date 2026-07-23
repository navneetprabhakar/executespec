package com.navneet.executespec.executioncontext;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

import com.navneet.executespec.agent.ApiKeyResolver;
import com.navneet.executespec.agent.LlmRequest;
import com.navneet.executespec.agent.ResolvedKeys;
import com.navneet.executespec.agent.RunKeyContext;
import com.navneet.executespec.cost.RunStepUsageRepository;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelAccessService;
import com.navneet.executespec.router.ModelRegistry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Reloads and enforces the exact V282 snapshot immediately before provider dispatch. Credentials
 * are resolved from the current encrypted user/org rows and never read from a cache or persisted.
 */
@Service
@RequiredArgsConstructor
public class LlmExecutionDispatchGuard {

  private static final Set<LlmExecutionContext.Status> DISPATCHABLE =
      Set.of(LlmExecutionContext.Status.QUEUED, LlmExecutionContext.Status.RUNNING);

  private final LlmExecutionContextService contextService;
  private final ApiKeyResolver apiKeyResolver;
  private final ModelRegistry modelRegistry;
  private final RunStepUsageRepository usageRepository;
  private final LlmExecutionBudgetGate budgetGate;

  @Value("${spring.ai.anthropic.api-key:}")
  private String platformAnthropicKey;

  @Value("${spring.ai.openai.api-key:}")
  private String platformOpenAiKey;

  @Value("${spring.ai.google.genai.api-key:}")
  private String platformGeminiKey;

  @Value("${spring.ai.openrouter.api-key:}")
  private String platformOpenRouterKey;

  @Value("${spring.ai.deepseek.api-key:}")
  private String platformDeepSeekKey;

  @Value("${spring.ai.fireworks.api-key:}")
  private String platformFireworksKey;

  public ResolvedDispatch authorize(LlmRequest request) {
    if (request.llmExecutionContextId() == null || request.llmExecutionScope() == null) {
      throw reject(
          LlmDispatchPolicyException.Reason.CONTEXT_REQUIRED,
          "A durable LLM execution context is required before provider dispatch");
    }
    LlmExecutionContext context =
        contextService
            .load(request.llmExecutionScope(), request.llmExecutionContextId())
            .orElseThrow(
                () ->
                    reject(
                        LlmDispatchPolicyException.Reason.CONTEXT_NOT_FOUND,
                        "LLM execution context is missing or outside the supplied tenant"));
    if (!DISPATCHABLE.contains(context.getStatus())
        || (context.getExpiresAt() != null && !context.getExpiresAt().isAfter(Instant.now()))) {
      throw reject(
          LlmDispatchPolicyException.Reason.CONTEXT_NOT_DISPATCHABLE,
          "LLM execution context is terminal, cancelled, or expired");
    }

    LlmPolicySnapshot policy = context.policySnapshot();
    String provider = normalizeProvider(request.provider());
    String model = modelRegistry.canonicalize(request.model());
    if (!policy.allowedProviders().contains(provider)) {
      throw reject(
          LlmDispatchPolicyException.Reason.PROVIDER_NOT_ALLOWED,
          "Provider is outside the durable execution policy");
    }
    validateModel(policy, provider, model);
    if (policy.routePinned()
        && (!provider.equals(policy.requestedProvider())
            || !model.equals(modelRegistry.canonicalize(policy.requestedModel())))) {
      throw reject(
          LlmDispatchPolicyException.Reason.EXACT_ROUTE_VIOLATION,
          "Dispatch differs from the exact pinned provider/model");
    }
    if (!policy.allowProviderFailover()
        && policy.requestedProvider() != null
        && !provider.equals(policy.requestedProvider())) {
      throw reject(
          LlmDispatchPolicyException.Reason.PROVIDER_NOT_ALLOWED,
          "Provider failover is disabled by the durable execution policy");
    }
    checkFencing(context, request.llmExecutionAttempt());
    checkBudget(context, request.maxTokens());

    if (context.getScopeMode() == LlmExecutionScope.ScopeMode.PLATFORM_INTERNAL) {
      return platformCapabilityDispatch(context, provider, policy);
    }
    if (!request.llmExecutionScope().isolationKey().equals(context.getIsolationKey())
        || !request.llmExecutionScope().orgId().equals(context.getOrgId())) {
      throw reject(
          LlmDispatchPolicyException.Reason.TENANT_MISMATCH,
          "LLM execution context tenant does not match the request scope");
    }
    ApiKeyResolver.CredentialResolution current =
        apiKeyResolver.resolveWithProvenance(context.getKeySubjectUserId());
    if (!context.getOrgId().equals(current.orgId())) {
      throw reject(
          LlmDispatchPolicyException.Reason.TENANT_MISMATCH,
          "Credential subject no longer belongs to the execution-context org");
    }
    LlmPolicySnapshot.KeySourceMode currentMode = keySourceMode(current.keys().keySourceMode());
    if (currentMode != policy.keySourceMode()) {
      throw reject(
          LlmDispatchPolicyException.Reason.POLICY_STALE,
          "Org key-source policy changed after this execution context was created");
    }
    if (!current.keys().isProviderAllowed(provider)) {
      throw reject(
          LlmDispatchPolicyException.Reason.PROVIDER_NOT_ALLOWED,
          "Provider is disabled by the org's current policy");
    }
    LlmPolicySnapshot.CredentialSource expected = policy.providerKeySources().get(provider);
    return switch (expected) {
      case USER, ORG -> customerDispatch(context, provider, current, expected);
      case PLATFORM -> {
        if (currentMode == LlmPolicySnapshot.KeySourceMode.OWN_KEYS) {
          throw reject(
              LlmDispatchPolicyException.Reason.POLICY_STALE,
              "OWN_KEYS context cannot dispatch with platform credentials");
        }
        yield platformCapabilityDispatch(context, provider, policy);
      }
    };
  }

  /**
   * Authorizes a non-chat provider capability against the same durable tenant/key policy used by
   * {@link #authorize(LlmRequest)}. This is intentionally stricter than chat failover: a project
   * embedding request must stay on the project's selected provider because vector compatibility is
   * part of the persisted corpus contract.
   *
   * <p>The capability implementation owns its fixed model/dimension allow-list; this guard owns
   * tenant isolation, provider allow-list, current credential source, key-source mode, and budget.
   */
  public ResolvedDispatch authorizeProviderCapability(
      LlmExecutionReference reference, String requestedProvider) {
    if (reference == null) {
      throw reject(
          LlmDispatchPolicyException.Reason.CONTEXT_REQUIRED,
          "A durable LLM execution context is required before provider dispatch");
    }
    LlmExecutionContext context =
        contextService
            .load(reference.scope(), reference.contextId())
            .orElseThrow(
                () ->
                    reject(
                        LlmDispatchPolicyException.Reason.CONTEXT_NOT_FOUND,
                        "LLM execution context is missing or outside the supplied tenant"));
    if (!DISPATCHABLE.contains(context.getStatus())
        || (context.getExpiresAt() != null && !context.getExpiresAt().isAfter(Instant.now()))) {
      throw reject(
          LlmDispatchPolicyException.Reason.CONTEXT_NOT_DISPATCHABLE,
          "LLM execution context is terminal, cancelled, or expired");
    }

    String provider = normalizeProvider(requestedProvider);
    LlmPolicySnapshot policy = context.policySnapshot();
    if (!policy.allowedProviders().contains(provider)) {
      throw reject(
          LlmDispatchPolicyException.Reason.PROVIDER_NOT_ALLOWED,
          "Provider is outside the durable execution policy");
    }
    if (policy.requestedProvider() == null
        || !provider.equals(normalizeProvider(policy.requestedProvider()))) {
      throw reject(
          LlmDispatchPolicyException.Reason.EXACT_ROUTE_VIOLATION,
          "Provider capability differs from the project's selected provider");
    }
    checkFencing(context, null);
    checkBudget(context, null);

    if (context.getScopeMode() == LlmExecutionScope.ScopeMode.PLATFORM_INTERNAL) {
      return platformCapabilityDispatch(context, provider, policy);
    }
    if (!reference.scope().isolationKey().equals(context.getIsolationKey())
        || !reference.scope().orgId().equals(context.getOrgId())) {
      throw reject(
          LlmDispatchPolicyException.Reason.TENANT_MISMATCH,
          "LLM execution context tenant does not match the request scope");
    }
    ApiKeyResolver.CredentialResolution current =
        apiKeyResolver.resolveWithProvenance(context.getKeySubjectUserId());
    if (!context.getOrgId().equals(current.orgId())) {
      throw reject(
          LlmDispatchPolicyException.Reason.TENANT_MISMATCH,
          "Credential subject no longer belongs to the execution-context org");
    }
    LlmPolicySnapshot.KeySourceMode currentMode = keySourceMode(current.keys().keySourceMode());
    if (currentMode != policy.keySourceMode()) {
      throw reject(
          LlmDispatchPolicyException.Reason.POLICY_STALE,
          "Org key-source policy changed after this execution context was created");
    }
    if (!current.keys().isProviderAllowed(provider)) {
      throw reject(
          LlmDispatchPolicyException.Reason.PROVIDER_NOT_ALLOWED,
          "Provider is disabled by the org's current policy");
    }
    LlmPolicySnapshot.CredentialSource expected = policy.providerKeySources().get(provider);
    if (expected == null) {
      throw reject(
          LlmDispatchPolicyException.Reason.CREDENTIAL_UNAVAILABLE,
          "Execution policy has no credential source for the provider capability");
    }
    return switch (expected) {
      case USER, ORG -> customerDispatch(context, provider, current, expected);
      case PLATFORM -> {
        if (currentMode == LlmPolicySnapshot.KeySourceMode.OWN_KEYS) {
          throw reject(
              LlmDispatchPolicyException.Reason.POLICY_STALE,
              "OWN_KEYS context cannot dispatch with platform credentials");
        }
        yield platformCapabilityDispatch(context, provider, policy);
      }
    };
  }

  private ResolvedDispatch customerDispatch(
      LlmExecutionContext context,
      String provider,
      ApiKeyResolver.CredentialResolution current,
      LlmPolicySnapshot.CredentialSource expected) {
    String key = keyFor(current.keys(), provider);
    if (key == null || key.isBlank()) {
      throw reject(
          LlmDispatchPolicyException.Reason.CREDENTIAL_UNAVAILABLE,
          "The authorized customer credential is no longer available");
    }
    ApiKeyResolver.CredentialOrigin actual = current.originFor(provider);
    String actualName = actual == null ? null : actual.name();
    if (!expected.name().equals(actualName)) {
      throw reject(
          LlmDispatchPolicyException.Reason.CREDENTIAL_SOURCE_CHANGED,
          "The provider credential source changed after authorization");
    }
    RunKeyContext.Source source =
        expected == LlmPolicySnapshot.CredentialSource.USER
            ? RunKeyContext.Source.USER
            : RunKeyContext.Source.ORG;
    return new ResolvedDispatch(key, source, context);
  }

  /**
   * Resolves the actual platform credential once this guard has authorized PLATFORM as the exact
   * key source. Every caller — chat and non-chat provider capabilities alike — receives a usable
   * key or an explicit {@code CREDENTIAL_UNAVAILABLE} rejection; a {@code PLATFORM} dispatch is
   * never returned with a withheld (null/blank) key, because a caller that needs the key on its own
   * transport would otherwise degrade silently instead of failing.
   */
  private ResolvedDispatch platformCapabilityDispatch(
      LlmExecutionContext context, String provider, LlmPolicySnapshot policy) {
    if (policy.providerKeySources().get(provider) != LlmPolicySnapshot.CredentialSource.PLATFORM) {
      throw reject(
          LlmDispatchPolicyException.Reason.CREDENTIAL_SOURCE_CHANGED,
          "Execution policy did not authorize a platform credential");
    }
    String key = platformKey(provider);
    if (key == null || key.isBlank()) {
      throw reject(
          LlmDispatchPolicyException.Reason.CREDENTIAL_UNAVAILABLE,
          "The authorized platform credential is not configured");
    }
    return new ResolvedDispatch(key, RunKeyContext.Source.PLATFORM, context);
  }

  private void validateModel(LlmPolicySnapshot policy, String provider, String model) {
    if (!modelRegistry.isKnownModel(model)) {
      throw reject(
          LlmDispatchPolicyException.Reason.MODEL_NOT_ALLOWED,
          "Unknown model is not eligible for customer dispatch");
    }
    String registeredProvider = normalizeProvider(modelRegistry.providerFor(model));
    LlmProvider routeProvider = LlmProvider.valueOf(provider.toUpperCase(Locale.ROOT));
    if (!ModelAccessService.canRouteModel(routeProvider, model, registeredProvider)) {
      throw reject(
          LlmDispatchPolicyException.Reason.MODEL_NOT_ALLOWED,
          "Model cannot be served by the requested provider");
    }
    var allowed = policy.allowedProviderModels().get(provider);
    if (allowed == null || !allowed.contains(model)) {
      throw reject(
          LlmDispatchPolicyException.Reason.MODEL_NOT_ALLOWED,
          "Model is outside the durable provider allow-list");
    }
  }

  /**
   * RR-321 — atomic. The former implementation ran three independent read-only aggregates and then
   * returned permission, so concurrent authorizers could all pass at the last unit of any budget
   * dimension. Delegates to {@link LlmExecutionBudgetGate}, which evaluates every dimension under a
   * pessimistic row lock and commits a provider-call reservation before transport.
   */
  private void checkBudget(LlmExecutionContext context, Integer requestedMaxOutputTokens) {
    budgetGate.reserveProviderCall(context, requestedMaxOutputTokens);
  }

  /**
   * RR-322 / RR-1666 — execution fencing, BUILT BUT NOT WIRED. The intent: a worker whose lease has
   * been stolen must not keep issuing provider calls against the same context alongside its
   * replacement. The two fences below are the mechanism, and they are individually correct and
   * unit-tested — but neither can FIRE in production today, because nothing on any real dispatch
   * path ever populates the state they read:
   *
   * <ul>
   *   <li><b>Expired-lease fence.</b> Rejects when a context carries a lease owner whose lease has
   *       expired. {@code leaseOwner}/{@code leaseExpiresAt} are set ONLY by {@link
   *       LlmExecutionContextService#claim}, which has ZERO production callers. Every real context
   *       is {@code QUEUED} with a null lease, so this branch is unreachable in production.
   *   <li><b>Attempt-mismatch fence.</b> Rejects when the caller declares a claim generation
   *       ({@link LlmRequest#llmExecutionAttempt()}) that no longer matches the context's current
   *       {@code attempt}. No production caller sets {@code llmExecutionAttempt} — it is always
   *       {@code null} — and {@code attempt} is only ever advanced by {@code claim}, which nothing
   *       calls, so this branch is unreachable in production too.
   * </ul>
   *
   * <p>Consequence: the durable-attempt contract in {@code executioncontext/CLAUDE.md} does not
   * hold at runtime yet. This is NOT closed by the RR-321 budget work — a duplicate/zombie worker
   * is still bounded only by the shared budget reservation, not fenced off.
   *
   * <p>What wiring remains (all outside this guard, hence not done here): the execution callers
   * must (a) {@code claim} the context — RUNNING + lease owner + attempt — when a worker takes
   * ownership, (b) heartbeat the lease during long work, (c) propagate the claimed {@code attempt}
   * onto every {@code LlmRequest.llmExecutionAttempt}, and (d) release ({@code complete}/{@code
   * fail}) on terminal — after which dispatch can be restricted to {@code RUNNING} and the lease
   * made mandatory. The blocker is that one run context is shared across planner/worker/reviewer/
   * compaction/report/chat, so a single per-context lease owner conflicts with concurrent
   * multi-agent use; wiring needs per-owner sub-leases or an ownership redesign plus context
   * hydration on the three {@code RunLifecycleService} resume/retry/re-review roots. Restricting
   * dispatch to {@code RUNNING} today would fail-close all customer dispatch, not fence a stale
   * worker.
   */
  private void checkFencing(LlmExecutionContext context, Integer declaredAttempt) {
    boolean leased = context.getLeaseOwner() != null && !context.getLeaseOwner().isBlank();
    if (leased
        && context.getLeaseExpiresAt() != null
        && !context.getLeaseExpiresAt().isAfter(Instant.now())) {
      throw reject(
          LlmDispatchPolicyException.Reason.CONTEXT_NOT_DISPATCHABLE,
          "LLM execution lease has expired; this worker is fenced out and must reclaim");
    }
    if (declaredAttempt != null && declaredAttempt != context.getAttempt()) {
      throw reject(
          LlmDispatchPolicyException.Reason.CONTEXT_NOT_DISPATCHABLE,
          "LLM execution attempt generation has advanced; this worker is fenced out");
    }
  }

  private String platformKey(String provider) {
    return switch (provider) {
      case "anthropic" -> platformAnthropicKey;
      case "openai" -> platformOpenAiKey;
      case "gemini" -> platformGeminiKey;
      case "openrouter" -> platformOpenRouterKey;
      case "deepseek" -> platformDeepSeekKey;
      case "fireworks" -> platformFireworksKey;
      default -> null;
    };
  }

  private static String keyFor(ResolvedKeys keys, String provider) {
    return switch (provider) {
      case "anthropic" -> keys.anthropicKey();
      case "openai" -> keys.openAiKey();
      case "gemini" -> keys.geminiKey();
      case "openrouter" -> keys.openRouterKey();
      case "deepseek" -> keys.deepSeekKey();
      case "fireworks" -> keys.fireworksKey();
      default -> null;
    };
  }

  private static LlmPolicySnapshot.KeySourceMode keySourceMode(String value) {
    try {
      return LlmPolicySnapshot.KeySourceMode.valueOf(
          value == null ? "HYBRID" : value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException invalid) {
      throw reject(
          LlmDispatchPolicyException.Reason.POLICY_STALE, "Org key-source policy is invalid");
    }
  }

  private static String normalizeProvider(String provider) {
    if (provider == null || provider.isBlank()) return "";
    String value = provider.trim().toLowerCase(Locale.ROOT);
    return "google".equals(value) ? "gemini" : value;
  }

  private static LlmDispatchPolicyException reject(
      LlmDispatchPolicyException.Reason reason, String message) {
    return new LlmDispatchPolicyException(reason, message);
  }

  public record ResolvedDispatch(
      String apiKey, RunKeyContext.Source keySource, LlmExecutionContext context) {}
}

package com.navneet.executespec.executioncontext;

import java.math.BigDecimal;
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
    checkBudget(context);

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
    checkBudget(context);

    if (context.getScopeMode() == LlmExecutionScope.ScopeMode.PLATFORM_INTERNAL) {
      return platformDispatch(context, provider, policy);
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
        yield platformDispatch(context, provider, policy);
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

  private ResolvedDispatch platformDispatch(
      LlmExecutionContext context, String provider, LlmPolicySnapshot policy) {
    if (policy.providerKeySources().get(provider) != LlmPolicySnapshot.CredentialSource.PLATFORM) {
      throw reject(
          LlmDispatchPolicyException.Reason.CREDENTIAL_SOURCE_CHANGED,
          "Execution policy did not authorize a platform credential");
    }
    if (!platformKeyPresent(provider)) {
      throw reject(
          LlmDispatchPolicyException.Reason.CREDENTIAL_UNAVAILABLE,
          "The authorized platform credential is not configured");
    }
    return new ResolvedDispatch(null, RunKeyContext.Source.PLATFORM, context);
  }

  /** Non-chat transports need the actual platform key after this guard authorizes its use. */
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

  private void checkBudget(LlmExecutionContext context) {
    String id = context.getId();
    if (context.getMaxProviderCalls() != null
        && usageRepository.countByLlmExecutionContextId(id) >= context.getMaxProviderCalls()) {
      throw budget();
    }
    long input = value(usageRepository.sumSuccessfulInputTokensByContextId(id));
    long output = value(usageRepository.sumSuccessfulOutputTokensByContextId(id));
    if ((context.getMaxInputTokens() != null && input >= context.getMaxInputTokens())
        || (context.getMaxOutputTokens() != null && output >= context.getMaxOutputTokens())
        || (context.getMaxTotalTokens() != null
            && safeAdd(input, output) >= context.getMaxTotalTokens())) {
      throw budget();
    }
    if (context.getMaxCostUsd() != null) {
      double spent = value(usageRepository.sumSuccessfulCostByContextId(id));
      if (BigDecimal.valueOf(spent).compareTo(context.getMaxCostUsd()) >= 0) throw budget();
    }
  }

  private boolean platformKeyPresent(String provider) {
    String key = platformKey(provider);
    return key != null && !key.isBlank();
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

  private static long value(Long value) {
    return value == null ? 0L : value;
  }

  private static double value(Double value) {
    return value == null ? 0D : value;
  }

  private static long safeAdd(long left, long right) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException overflow) {
      return Long.MAX_VALUE;
    }
  }

  private static LlmDispatchPolicyException budget() {
    return reject(
        LlmDispatchPolicyException.Reason.BUDGET_EXHAUSTED,
        "Durable LLM execution-context budget is exhausted");
  }

  private static LlmDispatchPolicyException reject(
      LlmDispatchPolicyException.Reason reason, String message) {
    return new LlmDispatchPolicyException(reason, message);
  }

  public record ResolvedDispatch(
      String apiKey, RunKeyContext.Source keySource, LlmExecutionContext context) {}
}

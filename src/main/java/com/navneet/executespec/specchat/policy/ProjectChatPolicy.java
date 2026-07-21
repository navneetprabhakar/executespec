package com.navneet.executespec.specchat.policy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.executioncontext.LlmPolicySnapshot;
import com.navneet.executespec.specchat.ChatToolPolicy;

/** Immutable, secret-free authority snapshot for exactly one customer chat turn. */
public record ProjectChatPolicy(
    String actorId,
    String actorOrgId,
    String orgId,
    String projectId,
    EffectiveProjectRole effectiveProjectRole,
    Set<ChatToolPolicy.Capability> allowedCapabilities,
    Set<String> allowedProviders,
    Map<String, List<String>> allowedProviderModels,
    LlmPolicySnapshot.KeySourceMode keySourceMode,
    Map<String, LlmPolicySnapshot.CredentialSource> providerKeySources,
    TurnBudgets budgets,
    Confirmation confirmation,
    Map<SourceKind, TrustLabel> sourceTrustLabels,
    AuditIdentity auditIdentity,
    LlmExecutionReference executionReference) {

  public ProjectChatPolicy {
    actorId = require(actorId, "actorId");
    orgId = require(orgId, "orgId");
    if (effectiveProjectRole == null) {
      throw new IllegalArgumentException("effectiveProjectRole required");
    }
    allowedCapabilities = immutableSet(allowedCapabilities);
    allowedProviders = immutableSet(allowedProviders);
    allowedProviderModels = immutableNestedMap(allowedProviderModels);
    if (keySourceMode == null) throw new IllegalArgumentException("keySourceMode required");
    providerKeySources = immutableMap(providerKeySources);
    if (budgets == null) throw new IllegalArgumentException("budgets required");
    if (confirmation == null) confirmation = Confirmation.none();
    sourceTrustLabels = immutableMap(sourceTrustLabels);
    if (auditIdentity == null) throw new IllegalArgumentException("auditIdentity required");
    if (executionReference == null) {
      throw new IllegalArgumentException("executionReference required");
    }
    if (!allowedProviders.equals(allowedProviderModels.keySet())
        || !allowedProviders.equals(providerKeySources.keySet())) {
      throw new IllegalArgumentException(
          "provider/model/key-source policy must describe the same exact providers");
    }
  }

  public enum EffectiveProjectRole {
    SUPER_ADMIN,
    ORG_ADMIN,
    PROJECT_ADMIN,
    PROJECT_DEVELOPER,
    PROJECT_VIEWER,
    UNSCOPED_USER
  }

  public enum SourceKind {
    SYSTEM_POLICY,
    USER_INPUT,
    PROJECT_DATA,
    REPOSITORY_DATA,
    UPLOADED_DOCUMENT,
    PUBLIC_WEB,
    TOOL_RESULT
  }

  public enum TrustLabel {
    TRUSTED_AUTHORITY,
    UNTRUSTED_DATA
  }

  public enum ConfirmationState {
    NONE,
    CONFIRMED
  }

  public record TurnBudgets(
      long maxTotalTokens,
      BigDecimal maxCostUsd,
      int maxProviderCalls,
      int maxToolCalls,
      int maxModelRounds) {
    public TurnBudgets {
      if (maxTotalTokens <= 0
          || maxCostUsd == null
          || maxCostUsd.signum() <= 0
          || maxProviderCalls <= 0
          || maxToolCalls <= 0
          || maxModelRounds <= 0) {
        throw new IllegalArgumentException("all chat turn budgets must be positive");
      }
      maxCostUsd = maxCostUsd.stripTrailingZeros();
    }
  }

  public record Confirmation(
      ConfirmationState state,
      String reference,
      Instant expiresAt,
      Set<ChatToolPolicy.Capability> confirmedCapabilities) {
    public Confirmation {
      if (state == null) throw new IllegalArgumentException("confirmation state required");
      confirmedCapabilities = immutableSet(confirmedCapabilities);
      if (state == ConfirmationState.CONFIRMED
          && (reference == null
              || reference.isBlank()
              || expiresAt == null
              || confirmedCapabilities.isEmpty())) {
        throw new IllegalArgumentException(
            "confirmed state requires a reference, expiry and at least one capability");
      }
      if (state == ConfirmationState.NONE) {
        reference = null;
        expiresAt = null;
        confirmedCapabilities = Set.of();
      }
    }

    public static Confirmation none() {
      return new Confirmation(ConfirmationState.NONE, null, null, Set.of());
    }

    public boolean confirms(ChatToolPolicy.Capability capability, Instant now) {
      return state == ConfirmationState.CONFIRMED
          && reference != null
          && expiresAt != null
          && now.isBefore(expiresAt)
          && confirmedCapabilities.contains(capability);
    }
  }

  public record AuditIdentity(
      String sessionId, String turnId, String correlationId, String policyContextId) {
    public AuditIdentity {
      correlationId = require(correlationId, "correlationId");
      policyContextId = require(policyContextId, "policyContextId");
    }
  }

  public void requireCapability(ChatToolPolicy.Capability capability) {
    if (capability == null || !allowedCapabilities.contains(capability)) {
      throw new ProjectChatPolicyDeniedException("chat capability is not allowed");
    }
  }

  public void requireConfirmed(ChatToolPolicy.Capability capability, Instant now) {
    requireCapability(capability);
    if (ChatToolPolicy.requiresConfirmation(capability)
        && !confirmation.confirms(capability, now)) {
      throw new ProjectChatPolicyDeniedException("chat capability requires confirmation");
    }
  }

  public void requireRoute(String provider, String model) {
    String normalizedProvider = normalize(provider);
    String normalizedModel = model == null ? null : model.trim();
    if (normalizedProvider == null || !allowedProviders.contains(normalizedProvider)) {
      throw new ProjectChatPolicyDeniedException("chat provider is not allowed");
    }
    List<String> models = allowedProviderModels.get(normalizedProvider);
    if (normalizedModel == null || models == null || !models.contains(normalizedModel)) {
      throw new ProjectChatPolicyDeniedException("chat model is not allowed for provider");
    }
  }

  private static String require(String value, String field) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
    return value.trim();
  }

  private static String normalize(String value) {
    if (value == null || value.isBlank()) return null;
    String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
    return "google".equals(normalized) ? "gemini" : normalized;
  }

  private static <T> Set<T> immutableSet(Set<T> values) {
    return values == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(values));
  }

  private static <K, V> Map<K, V> immutableMap(Map<K, V> values) {
    return values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }

  private static Map<String, List<String>> immutableNestedMap(Map<String, List<String>> values) {
    if (values == null) return Map.of();
    Map<String, List<String>> copy = new LinkedHashMap<>();
    values.forEach((provider, models) -> copy.put(provider, List.copyOf(models)));
    return Collections.unmodifiableMap(copy);
  }
}

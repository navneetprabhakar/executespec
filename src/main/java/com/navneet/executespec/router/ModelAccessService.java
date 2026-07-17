package com.navneet.executespec.router;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.navneet.executespec.auth.service.TierEnforcementService;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/**
 * Tier-based model access. The free tier may run only the locked free-tier provider's models
 * (configured at {@code model-matrix.free-tier.provider}, currently DeepSeek); every other tier
 * (PRO / TEAM / ENTERPRISE) may run any model. Used both to resolve per-user availability for the
 * catalog endpoint and to reject a run/agent-session whose chosen model the caller's tier can't
 * run.
 */
@Service
@RequiredArgsConstructor
public class ModelAccessService {

  /**
   * Explicit customer-facing provider deny-list. It is intentionally empty in 2.0: retired
   * providers are removed from {@link LlmProvider} and the model matrix, while keeping this seam
   * lets a future emergency quarantine fail closed without scattering provider checks through
   * controllers and routing services.
   */
  private static final Set<String> DISABLED_PROVIDERS = Set.of();

  private final ModelRegistry modelRegistry;
  private final TierEnforcementService tierService;

  /**
   * A model is free when it's on the model-level allow-list ({@code
   * model-matrix.free-tier.models}). When no list is configured we fall back to the legacy provider
   * lock ({@code free-tier.provider}).
   */
  public boolean isFreeModel(String modelId) {
    List<String> freeModels = modelRegistry.freeTierModels();
    if (!freeModels.isEmpty()) {
      return freeModels.contains(modelId)
          || freeModels.contains(modelRegistry.canonicalize(modelId));
    }
    String provider = modelRegistry.providerFor(modelId);
    return provider != null && provider.equalsIgnoreCase(modelRegistry.freeTierProvider());
  }

  /** Lowest tier permitted to run the model — FREE for the free-tier provider, otherwise PRO. */
  public String minTierFor(String modelId) {
    if (isDisabledModel(modelId)) return "DISABLED";
    return isFreeModel(modelId) ? "FREE" : "PRO";
  }

  public boolean userIsFreeTier(String userId) {
    return isFreeTier(tierService.resolveUserTier(userId));
  }

  /** True when the user's tier may run this model. */
  public boolean isAllowed(String userId, String modelId) {
    if (modelId == null || modelId.isBlank() || modelRegistry.providerFor(modelId) == null) {
      return false;
    }
    if (isDisabledModel(modelId)) return false;
    return !userIsFreeTier(userId) || isFreeModel(modelId);
  }

  public boolean isDisabledModel(String modelId) {
    String provider = modelRegistry.providerFor(modelId);
    return isDisabledProvider(provider);
  }

  public static boolean isDisabledProvider(String provider) {
    return provider != null
        && DISABLED_PROVIDERS.contains(provider.trim().toLowerCase(Locale.ROOT));
  }

  public static boolean isDisabledProviderModelSlug(String modelId) {
    if (modelId == null || modelId.isBlank()) {
      return false;
    }
    String normalized = modelId.trim().toLowerCase(Locale.ROOT);
    return DISABLED_PROVIDERS.stream()
        .anyMatch(provider -> normalized.equals(provider) || normalized.startsWith(provider + "-"));
  }

  public static void assertProviderEnabled(String provider) {
    if (isDisabledProvider(provider)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Provider '"
              + provider
              + "' is disabled; choose Anthropic, OpenAI, DeepSeek, OpenRouter, or Fireworks.");
    }
  }

  /**
   * Reject a run/session whose explicitly chosen model the caller's tier can't run. A blank model
   * means "let the router decide" and is always allowed (the free tier is locked to its model
   * downstream regardless).
   */
  public void assertModelAllowed(String userId, String modelName) {
    if (modelName == null || modelName.isBlank()) {
      return;
    }
    String provider = modelRegistry.providerFor(modelName);
    if (provider == null || provider.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown model: " + modelName);
    }
    if (isDisabledProvider(provider)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Model '" + modelName + "' is disabled because provider '" + provider + "' is disabled.");
    }
    if (!userIsFreeTier(userId)) {
      return;
    }
    String canonical = modelRegistry.canonicalize(modelName);
    if (!isFreeModel(canonical)) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "Model '" + modelName + "' isn't available on the free tier. Upgrade to Pro to use it.");
    }
  }

  /** Returns the canonical provider that owns a registered model, or {@code null} when unknown. */
  public String providerForModel(String modelName) {
    if (modelName == null || modelName.isBlank()) return null;
    return modelRegistry.providerFor(modelRegistry.canonicalize(modelName));
  }

  /**
   * Rejects a provider/model pair that cannot be dispatched together. This belongs at request
   * admission rather than in the asynchronous pipeline: silently dropping an incompatible model
   * makes the persisted route and the provider bill disagree with the user's selection.
   */
  public void assertProviderModelPair(String provider, String modelName) {
    if (modelName == null || modelName.isBlank()) return;
    String owner = providerForModel(modelName);
    if (owner == null || owner.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown model: " + modelName);
    }
    String normalizedProvider = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
    if ("google".equals(normalizedProvider)) normalizedProvider = "gemini";
    LlmProvider routeProvider;
    try {
      routeProvider = LlmProvider.valueOf(normalizedProvider.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException invalidProvider) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Unknown provider: " + provider, invalidProvider);
    }
    String canonical = modelRegistry.canonicalize(modelName);
    if (!canRouteModel(routeProvider, canonical, owner)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Model '" + modelName + "' is not served by provider '" + provider + "'.");
    }
  }

  /** Canonical native/aggregator routability rule shared by admission and orchestration. */
  public static boolean canRouteModel(
      LlmProvider provider, String canonicalModel, String ownerProvider) {
    if (provider == null || canonicalModel == null || canonicalModel.isBlank()) return false;
    if (provider.name().equalsIgnoreCase(ownerProvider)) return true;
    return switch (provider) {
      case OPENROUTER -> OpenRouterModelMapper.isSupported(canonicalModel);
      case FIREWORKS -> FireworksModelMapper.isSupported(canonicalModel);
      case DEEPSEEK -> canonicalModel.startsWith("deepseek-v4-");
      default -> false;
    };
  }

  public static boolean isFreeTier(String tier) {
    return tier == null || tier.isBlank() || "FREE".equalsIgnoreCase(tier);
  }
}

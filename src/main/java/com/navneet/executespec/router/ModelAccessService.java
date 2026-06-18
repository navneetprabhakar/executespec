package com.navneet.executespec.router;

import java.util.List;

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
    return isFreeModel(modelId) ? "FREE" : "PRO";
  }

  public boolean userIsFreeTier(String userId) {
    return isFreeTier(tierService.resolveUserTier(userId));
  }

  /** True when the user's tier may run this model. */
  public boolean isAllowed(String userId, String modelId) {
    return !userIsFreeTier(userId) || isFreeModel(modelId);
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

  public static boolean isFreeTier(String tier) {
    return tier == null || tier.isBlank() || "FREE".equalsIgnoreCase(tier);
  }
}

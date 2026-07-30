package com.navneet.executespec.integration;

import java.util.Locale;
import java.util.Set;

import com.navneet.executespec.router.AvailableProviderResolver;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelAccessService;
import com.navneet.executespec.state.entity.PipelineRun;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Synchronous admission for the exact provider/model authorized by a continuation approval.
 *
 * <p>This runs before the retry is queued or any provider attempt can be admitted. It deliberately
 * reuses the current user-tier, provider/model ownership, org-policy, and key-availability
 * authorities instead of trusting the candidate snapshot that originally produced the proposal.
 */
@Service
@RequiredArgsConstructor
public class ApprovedContinuationRouteAdmissionService {

  private final ModelAccessService modelAccessService;
  private final AvailableProviderResolver availableProviderResolver;

  public void assertAvailable(PipelineRun run, String provider, String model) {
    if (run == null || run.getRunId() == null || run.getRunId().isBlank()) {
      throw new IllegalStateException("Approved continuation route requires a durable run");
    }
    if (provider == null || provider.isBlank() || model == null || model.isBlank()) {
      throw new IllegalStateException(
          "Approved continuation route requires an exact provider and model");
    }

    String normalizedProvider = normalizeProvider(provider);
    LlmProvider routeProvider;
    try {
      routeProvider = LlmProvider.valueOf(normalizedProvider.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException invalidProvider) {
      throw new IllegalStateException(
          "Approved continuation provider is not registered: " + provider, invalidProvider);
    }

    String actorId = run.getCreatedByUserId();
    if (actorId == null || actorId.isBlank()) {
      throw new IllegalStateException(
          "Approved continuation route cannot be authorized without its submitting actor");
    }
    try {
      modelAccessService.assertProviderAllowed(actorId, normalizedProvider);
      modelAccessService.assertModelAllowed(actorId, model);
      modelAccessService.assertProviderModelPair(normalizedProvider, model);
    } catch (RuntimeException denied) {
      throw new IllegalStateException(
          "Approved continuation route is denied by current model/provider policy: "
              + normalizedProvider
              + "/"
              + model,
          denied);
    }

    Set<LlmProvider> available = availableProviderResolver.resolve(run.getRunId());
    if (available == null || !available.contains(routeProvider)) {
      throw new IllegalStateException(
          "Approved continuation route is unavailable under current org policy or credentials: "
              + normalizedProvider
              + "/"
              + model);
    }
  }

  private static String normalizeProvider(String provider) {
    String normalized = provider.trim().toLowerCase(Locale.ROOT);
    return "google".equals(normalized) ? "gemini" : normalized;
  }
}

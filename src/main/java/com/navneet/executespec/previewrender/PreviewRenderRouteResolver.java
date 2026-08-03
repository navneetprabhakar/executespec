package com.navneet.executespec.previewrender;

import java.util.Locale;
import java.util.Map;

import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelAccessService;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.OrgModelOverrideService;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.router.RunModelOverrides;
import com.navneet.executespec.state.entity.PipelineRun;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/** Resolves and validates the one immutable provider/model route used by a preview-render job. */
@Service
@RequiredArgsConstructor
class PreviewRenderRouteResolver {

  private final ModelResolver modelResolver;
  private final OrgModelOverrideService orgModelOverrideService;
  private final ModelAccessService modelAccessService;

  ResolvedModel resolve(PipelineRun run) {
    if (run == null) {
      throw new IllegalArgumentException("run required");
    }
    String userId = require(run.getCreatedByUserId(), "run owner");
    LlmProvider provider =
        parseProvider(
            run.isRoutePinned()
                ? require(run.getRequestedProvider(), "pinned provider")
                : firstNonBlank(run.getRequestedProvider(), run.getAiProvider()));
    ModelAccessService.assertProviderEnabled(provider.name());

    ResolvedModel route;
    if (run.isRoutePinned()) {
      String persistedModel = require(run.getRequestedModel(), "pinned model");
      modelAccessService.assertModelAllowed(userId, persistedModel);
      modelAccessService.assertProviderModelPair(provider.name(), persistedModel);
      route =
          ResolvedModel.builder()
              .requestedProvider(provider)
              .actualProvider(provider)
              .model(persistedModel)
              .agentTier(AgentTier.WORKHORSE)
              .fallback(false)
              .build();
    } else {
      modelAccessService.assertProviderAllowed(userId, provider.name());
      Map<String, String> overrides =
          orgModelOverrideService.overridesForRun(
              firstNonBlank(run.getBillingOrgId(), run.getOrgId()));
      Map<String, String> previous = RunModelOverrides.capture();
      RunModelOverrides.bind(overrides);
      try {
        route = modelResolver.resolveConfigured(provider, AgentTier.WORKHORSE);
      } finally {
        RunModelOverrides.bind(previous);
      }
      modelAccessService.assertModelAllowed(userId, route.getModel());
      modelAccessService.assertProviderModelPair(provider.name(), route.getModel());
    }

    if (route.getActualProvider() != provider
        || route.getRequestedProvider() != provider
        || route.getModel() == null
        || route.getModel().isBlank()) {
      throw new IllegalStateException("preview render route did not resolve exactly");
    }
    return route;
  }

  private static LlmProvider parseProvider(String value) {
    String normalized = require(value, "run provider").trim().toUpperCase(Locale.ROOT);
    if ("GOOGLE".equals(normalized)) normalized = "GEMINI";
    try {
      return LlmProvider.valueOf(normalized);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException("unknown persisted run provider: " + value, invalid);
    }
  }

  private static String firstNonBlank(String preferred, String fallback) {
    return preferred != null && !preferred.isBlank() ? preferred : fallback;
  }

  private static String require(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(field + " required");
    }
    return value;
  }
}

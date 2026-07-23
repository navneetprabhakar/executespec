package com.navneet.executespec.integration;

import java.util.Locale;

import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.billing.config.ManagedAiProperties;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelRegistry;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.state.RunMode;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Decides the durable route a submitted run advertises, before the pipeline exists.
 *
 * <p>Split out of {@code RunOrchestrator}, which owns the pipeline lifecycle. This class owns one
 * responsibility: reconciling three competing sources of route authority into the single set of
 * values persisted on {@code pipeline_run}, so the stored {@code plannerModel} / {@code
 * workerModel} / {@code reviewerModel} columns stay HONEST about what routing will actually call.
 *
 * <p>The three authorities, in the order they win:
 *
 * <ol>
 *   <li><b>Managed-tier lock</b> — a tier configured with {@code locked-provider} / {@code
 *       locked-model} is forced onto that route regardless of what was requested. This is
 *       systematic, not a rare tail: every FREE managed call takes it.
 *   <li><b>ENTERPRISE per-run pin</b> — an exact {@code modelName}, validated against the registry
 *       and rejected outright for non-ENTERPRISE callers rather than silently ignored.
 *   <li><b>Tier defaults</b> — the YAML matrix, resolved per role so the planner and reviewer
 *       columns show their PLANNING-tier model rather than the run-level worker default.
 * </ol>
 *
 * <p>Note what this resolver reports: the REQUESTED, tier-resolved route. The physically served
 * route is a separate fact, recorded in {@code run_step_usage} and projected onto {@code
 * agent_task.effective_*}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
class RunRouteContractResolver {

  private final AppUserRepository appUserRepository;
  private final ManagedAiProperties managedAiProperties;
  private final ModelRegistry modelRegistry;
  private final ModelResolver modelResolver;
  private final RunContextComposer contextComposer;

  /**
   * The route a run will advertise.
   *
   * @param provider effective provider, lower-case; a managed-tier lock may have replaced the
   *     requested one
   * @param canonicalPin validated ENTERPRISE exact-model pin, or null when the run is not pinned
   * @param workerModel run-level worker model
   * @param plannerModel PLANNING-tier planner model
   * @param reviewerModel PLANNING-tier reviewer model
   */
  record RouteContract(
      String provider,
      String canonicalPin,
      String workerModel,
      String plannerModel,
      String reviewerModel) {}

  /**
   * @param requestedProvider already-normalised provider name
   * @param modelName caller-supplied exact model pin, or null/blank for none
   * @throws ResponseStatusException 403 when a non-ENTERPRISE caller supplies a pin
   * @throws IllegalArgumentException when a pin cannot be routed through the chosen provider
   */
  RouteContract resolve(
      String runId,
      String requestedProvider,
      String modelName,
      String runMode,
      String createdByUserId) {
    String provider = requestedProvider;

    // Per-run provider+model pin (Beta, Enterprise-only — 2026-05-22): only ENTERPRISE runs may
    // pin a per-run model. The actual ROUTING is governed by the RunModelOverrides overlay bound
    // in runPipeline (also ENTERPRISE-gated); we resolve the owner tier once here so the persisted
    // plannerModel/workerModel/reviewerModel columns stay HONEST with that routing and never
    // advertise a pin that routing will ignore for a non-ENTERPRISE caller.
    String ownerTierAtSubmit =
        createdByUserId != null
            ? appUserRepository.findById(createdByUserId).map(u -> u.getTier()).orElse(null)
            : null;
    boolean enterpriseSubmit = "ENTERPRISE".equalsIgnoreCase(ownerTierAtSubmit);
    ManagedAiProperties.TierPolicy managedTierPolicy =
        managedAiProperties.policyFor(ownerTierAtSubmit);
    boolean managedTierRouteLocked =
        ownerTierAtSubmit != null
            && !ownerTierAtSubmit.isBlank()
            && managedTierPolicy.getLockedProvider() != null
            && !managedTierPolicy.getLockedProvider().isBlank()
            && managedTierPolicy.getLockedModel() != null
            && !managedTierPolicy.getLockedModel().isBlank();
    if (managedTierRouteLocked) {
      provider = managedTierPolicy.getLockedProvider().trim().toLowerCase(Locale.ROOT);
    }
    boolean hasPin = modelName != null && !modelName.isBlank();
    if (hasPin && !enterpriseSubmit) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Exact per-run model selection requires the Enterprise tier.");
    }
    String canonicalPin = null;
    if (hasPin && enterpriseSubmit) {
      String candidate = modelRegistry.canonicalize(modelName.trim());
      String owner = modelRegistry.providerFor(candidate);
      LlmProvider requestedLlmProvider = LlmProvider.valueOf(provider.toUpperCase(Locale.ROOT));
      if (owner == null || !canRoutePinnedModel(requestedLlmProvider, candidate, owner)) {
        throw new IllegalArgumentException(
            "Model '" + modelName + "' cannot be routed through provider '" + provider + "'.");
      }
      canonicalPin = candidate;
    }

    // The run-level worker model honours a supplied modelName only for ENTERPRISE; otherwise it is
    // the YAML workhorse default (= the model the worker will actually route to). This keeps the
    // pipeline's `model` value, the persisted workerModel column, and the tierConfigFor fallback
    // all consistent with the overlay-driven routing.
    //
    // 2026-05-06 — defaultModelFor was a hardcoded provider→model fallback that drifted from
    // the YAML matrix (returned Haiku/flash/mini when YAML PLANNING wanted Sonnet/Pro/gpt-5.4).
    // After the consolidation it routes through ModelRegistry.workhouseModelFor(provider) so
    // the run-level worker default tracks YAML.
    String model =
        canonicalPin != null
            ? canonicalPin
            : managedTierRouteLocked
                ? modelRegistry.canonicalize(managedTierPolicy.getLockedModel().trim())
                : contextComposer.defaultModelFor(provider);

    // Resolve per-tier models so RunDetail surfaces what the planner/reviewer
    // will actually call, not the run-level worker default — adversarial-test
    // Finding 14. An ENTERPRISE per-run pin is honoured for all three roles to
    // preserve the override contract.
    //
    // V182 PATCH-mode override: a patch run never invokes the planner or reviewer, but it *must*
    // run on PLANNING-tier (Sonnet 4.6 / GPT-5.4 / Gemini 3 Pro Preview) regardless of any
    // caller-supplied modelName. CodeGenEngine receives `plannerDbModel` as the worker model; the
    // reasoning gap between Haiku-class and Sonnet-class is bigger than any harness improvement on
    // bug-fix shapes.
    String plannerDbModel = model;
    String reviewerDbModel = model;
    boolean isPatch = RunMode.isPatch(runMode);
    if (!managedTierRouteLocked && (!hasPin || isPatch || !enterpriseSubmit)) {
      try {
        LlmProvider p = LlmProvider.valueOf(provider.toUpperCase());
        // v2.6.2 slice 5b — agent name is now OutlinePlannerAgent;
        // PlannerAgent was deleted along with the legacy single-phase planner.
        plannerDbModel =
            modelResolver.resolve("OutlinePlannerAgent", p, AgentTier.PLANNING).getModel();
        reviewerDbModel = modelResolver.resolve("ReviewerAgent", p, AgentTier.PLANNING).getModel();
      } catch (Exception ex) {
        log.debug(
            "Tier-based model resolution failed for run={} provider={}, falling back to run"
                + " default: {}",
            runId,
            provider,
            ex.getMessage());
      }
    }
    return new RouteContract(provider, canonicalPin, model, plannerDbModel, reviewerDbModel);
  }

  static boolean canRoutePinnedModel(
      LlmProvider provider, String canonicalModel, String ownerProvider) {
    return com.navneet.executespec.router.ModelAccessService.canRouteModel(
        provider, canonicalModel, ownerProvider);
  }
}

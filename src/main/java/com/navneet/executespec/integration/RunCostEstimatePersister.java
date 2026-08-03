package com.navneet.executespec.integration;

import static com.navneet.executespec.integration.RunPipelineSupport.computeRepoCountForEstimate;

import java.util.List;
import java.util.Optional;

import com.navneet.executespec.agent.ApiKeyResolver;
import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlanStrategy;
import com.navneet.executespec.cost.CostHistoryService;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Prices a planned run and persists the money columns that gate it.
 *
 * <p>Split out of {@code RunOrchestrator}, which owns the pipeline lifecycle. This class owns one
 * responsibility: turning a {@link Plan} into the run's cost estimate, credit charge and hard spend
 * ceiling, and writing them to {@code pipeline_run}.
 *
 * <p>It is the single place three separate policies meet, which is why they belong together rather
 * than scattered through the orchestrator:
 *
 * <ul>
 *   <li>historical k-NN pricing, matched on the exact expected physical provider/model route set;
 *   <li>the tier-aware formula fallback, which is model-aware;
 *   <li>the BYOK / platform hard-cap decision, including the customer's voluntary {@code
 *       userCostCap}.
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
class RunCostEstimatePersister {

  private final PipelineRunRepository runRepo;
  private final RunCostTracker costTracker;
  private final CostHistoryService costHistoryService;
  private final ApiKeyResolver apiKeyResolver;

  /**
   * Estimate the run's cost from its plan and persist {@code costEstimateLow/High}, {@code
   * creditsCharged} and {@code hardCapUsd}.
   */
  void persistEstimate(
      String runId, String provider, String model, Plan plan, String lowComplexityWorkerStartTier) {
    List<String> complexities =
        plan.tasks().stream()
            .map(t -> t.complexity() == null ? "MEDIUM" : t.complexity().name())
            .toList();
    // v2.6.2 slice 5 — pull the persisted plan strategy from the run row so
    // the estimate reflects the actual hierarchical-pipeline phase costs
    // (classifier + outline + expansion + coherence) instead of the legacy
    // single-phase shape. Falls back to null = legacy estimate when the
    // classifier hadn't run yet (classifier failure / pre-slice-1 row).
    PlanStrategy strategy =
        runRepo
            .findById(runId)
            .map(PipelineRun::getPlanStrategy)
            .map(PlanStrategy::fromStringSafe)
            .orElse(null);
    // 2026-05-13 audit Stage 1 — feed repoCount + isProjectScoped to the estimator so
    // post-planning costs (reviewer × per-repo wiring, codemap per repo) are accounted for.
    // Run 6024f510 (1 LOW task × 3 repos × project-scoped × Anthropic) was estimated at
    // $0.05-$0.09 against an actual $1.04 — the missing reviewer/codemap/cache-write
    // overhead was the bulk of the gap.
    PipelineRun runRow = runRepo.findById(runId).orElse(null);
    int repoCount = computeRepoCountForEstimate(runRow);
    boolean isProjectScoped = runRow != null && runRow.getProjectId() != null;

    // Audit 2026-05-13 Stage 2 — try historical k-NN first. Falls back to the Stage-1
    // formula when fewer than CostHistoryService.MIN_SAMPLES similar runs exist. As the
    // historical table fills (after deploy), this kicks in for popular shapes and the
    // formula remains the bootstrap fallback for novel shapes.
    String providerKey = provider == null ? null : provider.toUpperCase();
    String strategyKey = strategy == null ? null : strategy.name();
    // Only an EXPLICIT model pin should drive a model-specific estimate. The `model` argument is
    // always populated (it falls back to the provider's default model), so it cannot distinguish a
    // real pin from a default. Use the persisted V275 route intent instead: an EXACT_LOCK / pinned
    // run carries the customer-chosen model.
    String pinnedModel =
        runRow != null
                && runRow.isRoutePinned()
                && runRow.getRequestedModel() != null
                && !runRow.getRequestedModel().isBlank()
            ? runRow.getRequestedModel().trim()
            : null;
    // V372 makes history model-aware through a canonical physical-route fingerprint. Resolve the
    // expected route while the run's exact pin/org matrix is bound; an equivalent explicit pin now
    // shares its cohort, while a different model or provider/failover cannot contaminate it.
    //
    // RR-1551 — the history lookup is scoped to THIS run's owning org. The band it returns becomes
    // creditsCharged and hardCapUsd below, so pricing a tenant from other tenants' observed spend
    // was both a billing defect and a cross-tenant aggregate disclosure. A run with no orgId gets
    // the model-aware formula, never a global pool.
    String orgId = runRow == null ? null : runRow.getOrgId();
    String routeFingerprint =
        costTracker.expectedRouteFingerprint(
            provider, complexities, strategy, lowComplexityWorkerStartTier);
    String taskComplexityFingerprint = CostHistoryService.taskComplexityFingerprint(complexities);
    Optional<RunCostTracker.Estimate> historical =
        costHistoryService.estimateFromHistory(
            orgId,
            strategyKey,
            providerKey,
            routeFingerprint,
            repoCount,
            complexities.size(),
            taskComplexityFingerprint);
    RunCostTracker.Estimate est =
        historical.orElseGet(
            () ->
                costTracker.estimateRunTierAware(
                    provider, complexities, strategy, repoCount, isProjectScoped, pinnedModel));
    runRepo
        .findById(runId)
        .ifPresent(
            r -> {
              r.setCostEstimateLow(est.lowUsd());
              r.setCostEstimateHigh(est.highUsd());
              r.setCreditsCharged(est.credits());
              // GA-readiness P3.2 — honour customer-supplied cost cap (P3.2 helper).
              // 2026-05-08 — BYOK cost-cap exemption. When the user's org is OWN_KEYS, skip
              // OUR platform cap entirely; the customer pays their own API bill so we shouldn't
              // hard-stop them on ours. BUT we still honour their EXPLICIT userCostCap (typed
              // into NewRunModal) — that's their voluntary ceiling, not ours.
              //
              // Decision matrix:
              //   BYOK    + userCostCap set    → userCostCap   (customer's voluntary cap honoured)
              //   BYOK    + userCostCap null   → null          (no cap; fully BYOK)
              //   non-BYOK + (any)             → clamp(platform, userCostCap) (existing behaviour)
              boolean byok = apiKeyResolver.isOwnKeysMode(r.getCreatedByUserId());
              Double effectiveHardCap;
              if (byok) {
                Double userCap = r.getUserCostCap();
                effectiveHardCap = userCap != null && userCap > 0 ? userCap : null;
              } else {
                effectiveHardCap =
                    RunOrchestrator.clampHardCap(est.hardCapUsd(), r.getUserCostCap());
              }
              r.setHardCapUsd(effectiveHardCap);
              runRepo.save(r);
              log.info(
                  "RunOrchestrator run={} estimate strategy={} low=${} high=${} credits={}"
                      + " platformCap=${} effectiveCap={} tasks={}",
                  runId,
                  strategy,
                  String.format("%.4f", est.lowUsd()),
                  String.format("%.4f", est.highUsd()),
                  est.credits(),
                  String.format("%.2f", est.hardCapUsd()),
                  effectiveHardCap == null ? "none" : "$" + String.format("%.2f", effectiveHardCap),
                  est.taskCount());
            });
  }
}

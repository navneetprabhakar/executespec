package com.navneet.executespec.billing.service;

import java.time.Instant;
import java.util.List;

import com.navneet.executespec.agent.RunKeyContext;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.billing.config.ManagedAiProperties;
import com.navneet.executespec.billing.dto.ManagedAiDtos.ManagedAiBalanceResponse;
import com.navneet.executespec.billing.dto.ManagedAiDtos.ManagedAiPolicyResponse;
import com.navneet.executespec.billing.dto.ManagedAiDtos.ManagedAiUsageEventResponse;
import com.navneet.executespec.billing.entity.ManagedAiUsagePeriod;
import com.navneet.executespec.billing.repository.ManagedAiUsageEventRepository;
import com.navneet.executespec.cost.RunCostTracker;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ManagedAiUsageService {

  public static final String FULL = ManagedAiRoutingPolicy.FULL;
  public static final String VALUE_MODE = ManagedAiRoutingPolicy.VALUE_MODE;
  public static final String FRONTIER_LOCKED = ManagedAiRoutingPolicy.FRONTIER_LOCKED;
  public static final String CHEAP_ONLY = ManagedAiRoutingPolicy.CHEAP_ONLY;
  public static final String EXHAUSTED = ManagedAiRoutingPolicy.EXHAUSTED;

  private final ManagedAiProperties properties;
  private final ManagedAiUsageEventRepository eventRepository;
  private final ManagedAiCreditCalculator creditCalculator;
  private final ManagedAiModelClassifier modelClassifier;
  private final ManagedAiRoutingPolicy routingPolicy;
  private final ManagedAiUsageMapper usageMapper;
  private final ManagedAiUsagePeriodService periodService;
  private final ManagedAiTierResolver tierResolver;

  @Transactional
  public CallAuthorization authorizeCall(
      String runId,
      String provider,
      String model,
      String agentRole,
      RunKeyContext keys,
      RunKeyContext.Source source) {
    if (!properties.isEnabled() || !modelClassifier.managedAiEnabled(model)) {
      return CallAuthorization.allowed(provider, model, FULL);
    }
    ManagedAiUserContext user = periodService.resolveUser(runId, keys);
    if (user == null || !isPlatform(source)) {
      return CallAuthorization.allowed(provider, model, FULL);
    }
    ManagedAiProperties.TierPolicy policy = properties.policyFor(user.tier());
    if (!isManaged(policy)) {
      return CallAuthorization.allowed(provider, model, FULL);
    }
    ManagedAiUsagePeriod period = periodService.currentOrCreate(user, policy, Instant.now(), false);
    String state = routingPolicy.recomputeState(period);
    periodService.syncState(period, state);
    // Tier model lock (F2, founder decision 2026-07-18): a locked tier (FREE) is routed
    // exclusively to its locked provider/model (native DeepSeek Flash), overriding whatever
    // provider/model was requested, so a managed tier cannot spend platform credits on a premium
    // provider. The managed hard-stop still applies to the locked model.
    String lockedModel = policy.getLockedModel();
    if (lockedModel != null && !lockedModel.isBlank()) {
      String lockedProvider =
          (policy.getLockedProvider() != null && !policy.getLockedProvider().isBlank())
              ? policy.getLockedProvider()
              : provider;
      String lockedCostClass = modelClassifier.costClass(lockedModel);
      if (!routingPolicy.isAllowed(state, lockedCostClass, agentRole)) {
        throw routingPolicy.blocked(state, lockedCostClass);
      }
      if (!lockedModel.equals(model) || !lockedProvider.equalsIgnoreCase(provider)) {
        return new CallAuthorization(
            lockedProvider,
            lockedModel,
            state,
            true,
            "Managed AI tier lock: " + user.tier() + " routes to " + lockedModel);
      }
      return CallAuthorization.allowed(lockedProvider, lockedModel, state);
    }
    String costClass = modelClassifier.costClass(model);
    if (routingPolicy.shouldStepUp(state, costClass, agentRole, period)) {
      return modelClassifier
          .sameProviderStepUp(provider, model)
          .filter(
              target ->
                  routingPolicy.isAllowed(
                      state, modelClassifier.costClass(target.model()), agentRole))
          .map(
              target ->
                  new CallAuthorization(
                      target.provider(),
                      target.model(),
                      state,
                      true,
                      "Managed AI stepped up from " + model + " to " + target.model()))
          .orElseGet(() -> CallAuthorization.allowed(provider, model, state));
    }
    if (routingPolicy.isAllowed(state, costClass, agentRole)) {
      return CallAuthorization.allowed(provider, model, state);
    }
    return modelClassifier
        .sameProviderStepDown(provider, model)
        .filter(
            target ->
                routingPolicy.isAllowed(
                    state, modelClassifier.costClass(target.model()), agentRole))
        .map(
            target ->
                new CallAuthorization(
                    target.provider(),
                    target.model(),
                    state,
                    true,
                    "Managed AI stepped down from " + model + " to " + target.model()))
        .orElseThrow(() -> routingPolicy.blocked(state, costClass));
  }

  /**
   * Pre-authorize a fixed-model provider capability such as embeddings. Unlike chat routing this
   * method never substitutes a different model: doing so would corrupt a persisted vector corpus.
   * Platform-key calls still obey the same managed-credit hard stop; BYOK calls remain exempt.
   */
  @Transactional
  public void authorizeFixedCapabilityCall(
      String runId,
      String provider,
      String model,
      String agentRole,
      RunKeyContext keys,
      RunKeyContext.Source source) {
    if (!properties.isEnabled() || !modelClassifier.managedAiEnabled(model)) return;
    ManagedAiUserContext user = periodService.resolveUser(runId, keys);
    if (user == null || !isPlatform(source)) return;
    ManagedAiProperties.TierPolicy policy = properties.policyFor(user.tier());
    if (!isManaged(policy)) return;
    ManagedAiUsagePeriod period = periodService.currentOrCreate(user, policy, Instant.now(), false);
    String state = routingPolicy.recomputeState(period);
    periodService.syncState(period, state);
    String costClass = modelClassifier.costClass(model);
    if (!routingPolicy.isAllowed(state, costClass, agentRole)) {
      throw routingPolicy.blocked(state, costClass);
    }
  }

  @Transactional
  public void recordSuccessfulCall(
      String runId,
      String taskId,
      String agentRole,
      String stepId,
      String provider,
      String model,
      RunCostTracker.CallUsage usage,
      RunKeyContext keys,
      RunKeyContext.Source source) {
    recordSuccessfulCall(
        null, runId, taskId, agentRole, stepId, provider, model, usage, keys, source);
  }

  /**
   * Projects one immutable canonical provider-attempt fact into the managed-credit ledger. The
   * exact usage-event id makes reconciliation deterministic and prevents a retry/failover with the
   * same run/model/timestamp from being charged against the wrong attempt.
   */
  @Transactional
  public void recordSuccessfulCall(
      String usageEventId,
      String runId,
      String taskId,
      String agentRole,
      String stepId,
      String provider,
      String model,
      RunCostTracker.CallUsage usage,
      RunKeyContext keys,
      RunKeyContext.Source source) {
    if (!properties.isEnabled() || usage == null || !modelClassifier.managedAiEnabled(model)) {
      return;
    }
    ManagedAiUserContext user = periodService.resolveUser(runId, keys);
    if (user == null) return;
    ManagedAiProperties.TierPolicy policy = properties.policyFor(user.tier());
    if (!isManaged(policy)) return;

    ManagedAiUsagePeriod period = periodService.currentOrCreate(user, policy, Instant.now(), true);
    String before = routingPolicy.recomputeState(period);
    boolean platform = isPlatform(source);
    long credits =
        platform
            ? creditCalculator.creditsFor(
                usage.costUsd(), policy.getRetailMultiplier(), properties.getCreditsPerUsd())
            : 0;
    if (platform) {
      period.setRawCogsUsedUsd(period.getRawCogsUsedUsd() + usage.costUsd());
      period.setAiCreditsUsed(period.getAiCreditsUsed() + credits);
      if ("FRONTIER".equals(modelClassifier.costClass(model))) {
        period.setFrontierRawUsedUsd(period.getFrontierRawUsedUsd() + usage.costUsd());
      }
    }
    String after = routingPolicy.recomputeState(period);
    periodService.saveWithTransition(period, before, after);
    eventRepository.save(
        usageMapper.toEvent(
            user.userId(),
            user.orgId(),
            period,
            usageEventId,
            runId,
            taskId,
            agentRole,
            stepId,
            provider,
            model,
            modelClassifier.costClass(model),
            usage,
            source,
            credits,
            before,
            after,
            policy));
  }

  @Transactional
  public ManagedAiBalanceResponse balance(AppUserDetails userDetails) {
    ManagedAiUserContext user =
        new ManagedAiUserContext(
            userDetails.getUser().getId(),
            userDetails.getUser().getOrgId(),
            tierResolver.resolveUserTier(userDetails.getUser().getId()));
    ManagedAiProperties.TierPolicy policy = properties.policyFor(user.tier());
    boolean managed = properties.isEnabled() && isManaged(policy);
    ManagedAiUsagePeriod period = periodService.currentOrCreate(user, policy, Instant.now(), false);
    String state = managed ? routingPolicy.recomputeState(period) : FULL;
    periodService.syncState(period, state);
    return usageMapper.toBalanceResponse(period, policy, managed, state);
  }

  /**
   * Run-count retirement (2026-06-22): submission-time managed-AI spend gate. Replaces {@code
   * TierEnforcementService.assertAndIncrementRunCount} + {@code UsageTrackingService}'s "RUNS"
   * quota at run submission. Throws 402 only when the user's tier is managed (FREE/PRO), they are
   * not pure BYOK ({@code ownKeysMode} — own-key spend is cap-exempt and never accrues raw COGS),
   * and their routing state is {@link #EXHAUSTED}. Per-call {@link #authorizeCall} stays the
   * mid-run source of truth; this is the fail-fast pre-flight so a run does not start only to die
   * on its first managed call.
   */
  @Transactional
  public void assertCanStartRun(AppUserDetails userDetails, boolean ownKeysMode) {
    if (!properties.isEnabled() || ownKeysMode) return;
    String userId = userDetails.getUser().getId();
    ManagedAiUserContext user =
        new ManagedAiUserContext(
            userId, userDetails.getUser().getOrgId(), tierResolver.resolveUserTier(userId));
    ManagedAiProperties.TierPolicy policy = properties.policyFor(user.tier());
    if (!isManaged(policy)) return;
    ManagedAiUsagePeriod period = periodService.currentOrCreate(user, policy, Instant.now(), false);
    String state = routingPolicy.recomputeState(period);
    periodService.syncState(period, state);
    if (EXHAUSTED.equals(state)) {
      throw new ResponseStatusException(
          HttpStatus.PAYMENT_REQUIRED,
          "Your ES AI credits are exhausted for this billing period. Add your own provider keys"
              + " (BYOK) or upgrade your plan to keep running.");
    }
  }

  /**
   * Read-only ES-credit rollup for the v3 entitlements surface (no period creation). Returns {@code
   * managed=false} (ungated) for TEAM/ENTERPRISE/own-key tiers; for managed tiers it reflects the
   * live period when one exists, else the policy grant with zero usage.
   */
  @Transactional(readOnly = true)
  public CreditSummary creditSummary(String userId, String tier) {
    ManagedAiProperties.TierPolicy policy = properties.policyFor(tier);
    if (!properties.isEnabled() || !isManaged(policy)) {
      return new CreditSummary(false, FULL, -1, 0, -1);
    }
    return periodService
        .findCurrent(userId, Instant.now())
        .map(
            p -> {
              // Grant is policy-derived; the period snapshot may predate a policy bump. The write
              // path (currentOrCreate) refreshes the period's caps, but reads must still reflect
              // the
              // current grant immediately.
              long included = policy.getIncludedCredits();
              long purchased = p.getPurchasedAiCredits();
              long used = p.getAiCreditsUsed();
              return new CreditSummary(
                  true,
                  routingPolicy.recomputeState(p),
                  included + purchased,
                  used,
                  Math.max(0, included + purchased - used));
            })
        .orElseGet(
            () ->
                new CreditSummary(
                    true, FULL, policy.getIncludedCredits(), 0, policy.getIncludedCredits()));
  }

  public record CreditSummary(
      boolean managed,
      String routingState,
      long creditsIncluded,
      long creditsUsed,
      long creditsRemaining) {}

  @Transactional(readOnly = true)
  public List<ManagedAiUsageEventResponse> currentEvents(AppUserDetails userDetails, int limit) {
    Instant now = Instant.now();
    return periodService
        .findCurrent(userDetails.getUser().getId(), now)
        .map(
            p ->
                eventRepository
                    .findByPeriodIdOrderByCreatedAtDesc(
                        p.getId(), PageRequest.of(0, Math.max(1, Math.min(limit, 200))))
                    .stream()
                    .map(usageMapper::toEventResponse)
                    .toList())
        .orElse(List.of());
  }

  public ManagedAiPolicyResponse policy(AppUserDetails userDetails) {
    String tier = tierResolver.resolveUserTier(userDetails.getUser().getId());
    ManagedAiProperties.TierPolicy policy = properties.policyFor(tier);
    return new ManagedAiPolicyResponse(
        properties.isEnabled(),
        tier,
        policy.getIncludedCredits(),
        properties.isEnabled() && !isManaged(policy),
        policy.getRawCogsCapUsd(),
        policy.getFrontierRawCapUsd(),
        policy.getValueModeAfterRawUsd(),
        policy.getCheapOnlyAfterRawUsd(),
        policy.effectiveHardStopUsd(),
        policy.getRetailMultiplier(),
        policy.isConcurrencyEnforced(),
        policy.isRateLimitsEnabled(),
        policy.getNotificationThresholdsPct(),
        properties.creditDefinition()
            + "; successful platform-key LLM calls debit ceil(raw_cost_usd *"
            + " multiplier * "
            + properties.getCreditsPerUsd()
            + ").");
  }

  @Transactional
  public void grantPurchasedCredits(String userId, String orgId, long credits) {
    if (credits <= 0) {
      throw new IllegalArgumentException("credits must be positive");
    }
    String tier = tierResolver.resolveUserTier(userId);
    ManagedAiUserContext user = new ManagedAiUserContext(userId, orgId, tier);
    ManagedAiProperties.TierPolicy policy = properties.policyFor(tier);
    periodService.grantPurchasedCredits(user, policy, credits, Instant.now());
  }

  private static boolean isManaged(ManagedAiProperties.TierPolicy policy) {
    return policy != null
        && (policy.getIncludedCredits() > 0
            || policy.getRawCogsCapUsd() > 0
            || policy.effectiveHardStopUsd() > 0);
  }

  private static boolean isPlatform(RunKeyContext.Source source) {
    return source == null || source == RunKeyContext.Source.PLATFORM;
  }

  public record CallAuthorization(
      String provider, String model, String routingState, boolean modelChanged, String reason) {
    public static CallAuthorization allowed(String provider, String model, String state) {
      return new CallAuthorization(provider, model, state, false, null);
    }
  }
}

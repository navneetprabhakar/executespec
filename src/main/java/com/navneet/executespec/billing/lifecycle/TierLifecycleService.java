package com.navneet.executespec.billing.lifecycle;

import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.auth.service.ActivityAuditService;
import com.navneet.executespec.auth.entity.ActivityAudit;
import com.navneet.executespec.billing.entity.BillingPlan;
import com.navneet.executespec.billing.entity.BillingSubscription;
import com.navneet.executespec.billing.gateway.PaymentGateway;
import com.navneet.executespec.billing.gateway.PaymentGatewayRouter;
import com.navneet.executespec.billing.repository.BillingPlanRepository;
import com.navneet.executespec.billing.repository.BillingSubscriptionRepository;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * v2.6.0 — 3-state lifecycle machine for tiers and billing plans.
 *
 * <p>Allowed transitions:
 * <ul>
 *   <li>{@code LISTED ↔ HIDDEN} — instant, fully reversible.</li>
 *   <li>{@code HIDDEN → SUNSETTING} — requires 14-day observation window +
 *       typed tier-name confirmation (enforced in controller).</li>
 *   <li>{@code SUNSETTING → HIDDEN} — allowed but flagged "partially
 *       reversible" (un-cancelling is per-customer Stripe work done
 *       elsewhere).</li>
 *   <li>{@code LISTED → SUNSETTING} — rejected. Must pass through HIDDEN.</li>
 *   <li>{@code SUNSETTING → LISTED} — rejected. Un-sunset then re-list.</li>
 * </ul>
 *
 * <p>Gateway side-effects for {@code SUNSETTING} (queuing cancel-at-period-end
 * on existing subscriptions + firing notice emails) are out of scope for
 * Wave 1 Day 1; they land on Day 3 once {@code cancelAtPeriodEnd} gateway
 * methods exist. This Day 1 service just flips the flag + writes the audit.
 *
 * <p>Full design: {@code docs/plans/tier-lifecycle-plan.md}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TierLifecycleService {

    static final Duration MIN_HIDDEN_BEFORE_SUNSET = Duration.ofDays(14);

    /** Feature-flag key gating SUNSETTING gateway side-effects. See V124 migration. */
    static final String SUNSETTING_ENABLED_FLAG = "billing.lifecycle.sunsetting.enabled";

    private final TierConfigRepository tierConfigRepo;
    private final BillingPlanRepository billingPlanRepo;
    private final ActivityAuditService auditService;
    /**
     * Injected as ObjectProvider to avoid boot-time cycles and allow the
     * service to start even before Billing beans are fully wired (e.g. in
     * the Day-1 unit tests which construct the service with null repos).
     */
    private final ObjectProvider<BillingSubscriptionRepository> subscriptionRepoProvider;
    private final ObjectProvider<PaymentGatewayRouter> gatewayRouterProvider;
    private final ObjectProvider<FeatureFlagService> featureFlagProvider;

    /**
     * Transition a tier between lifecycle states. Validates the transition
     * against the allowed set, writes the new state + audit row. Does NOT
     * (yet) fire gateway side-effects for SUNSETTING — see class Javadoc.
     *
     * @throws ResponseStatusException 422 if the transition is illegal, 404
     *     if the tier doesn't exist, 409 if the 14-day observation window
     *     isn't satisfied for HIDDEN→SUNSETTING.
     */
    @Transactional
    public TierConfig transitionTier(String tier,
                                     LifecycleState target,
                                     String actorUserId,
                                     String reason) {
        TierConfig row = tierConfigRepo.findById(tier)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Unknown tier: " + tier));

        LifecycleState current = LifecycleState.fromDbValue(row.getLifecycleState());
        validateTransition(current, target, row.getLifecycleChangedAt());

        row.setLifecycleState(target.name());
        row.setLifecycleChangedAt(Instant.now());
        row.setLifecycleChangedBy(actorUserId);
        row.setLifecycleReason(reason);
        TierConfig saved = tierConfigRepo.save(row);

        auditService.record(ActivityAudit.builder()
                .eventType("TIER_LIFECYCLE_CHANGED")
                .entityType("TIER_CONFIG")
                .entityId(tier)
                .actorId(actorUserId)
                .oldValue(current.name())
                .newValue(target.name())
                .metadata(reason == null ? null : "{\"reason\":\"" + escape(reason) + "\"}")
                .build());

        log.info("Tier lifecycle: {} {} → {} (actor={}, reason={})",
                tier, current, target, actorUserId, reason);

        // Fire SUNSETTING side-effects only when transitioning INTO that state.
        if (target == LifecycleState.SUNSETTING) {
            executeSunsettingSideEffects(tier, actorUserId);
        }

        return saved;
    }

    /**
     * Transition a single billing plan row. Independent of its owning tier —
     * a LISTED tier can contain HIDDEN individual plans (e.g. hide the
     * Yearly INR cycle while keeping Monthly USD listed).
     */
    @Transactional
    public BillingPlan transitionPlan(UUID planId,
                                      LifecycleState target,
                                      String actorUserId,
                                      String reason) {
        BillingPlan row = billingPlanRepo.findById(planId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Unknown billing plan: " + planId));

        LifecycleState current = LifecycleState.fromDbValue(row.getLifecycleState());
        validateTransition(current, target, row.getLifecycleChangedAt());

        row.setLifecycleState(target.name());
        row.setLifecycleChangedAt(Instant.now());
        row.setLifecycleChangedBy(actorUserId);
        row.setLifecycleReason(reason);
        BillingPlan saved = billingPlanRepo.save(row);

        auditService.record(ActivityAudit.builder()
                .eventType("PLAN_LIFECYCLE_CHANGED")
                .entityType("BILLING_PLAN")
                .entityId(planId.toString())
                .actorId(actorUserId)
                .oldValue(current.name())
                .newValue(target.name())
                .metadata(reason == null ? null : "{\"reason\":\"" + escape(reason) + "\"}")
                .build());

        log.info("Plan lifecycle: {} ({}) {} → {} (actor={}, reason={})",
                row.getName(), planId, current, target, actorUserId, reason);
        return saved;
    }

    /**
     * Throws {@link ResponseStatusException} if a transition is illegal.
     * Package-private for direct unit testing.
     */
    void validateTransition(LifecycleState from,
                            LifecycleState to,
                            Instant lastChangedAt) {
        if (from == to) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Already in state " + to);
        }

        // Directly-allowed transitions (matrix).
        Map<LifecycleState, EnumSet<LifecycleState>> allowed = Map.of(
                LifecycleState.LISTED,      EnumSet.of(LifecycleState.HIDDEN),
                LifecycleState.HIDDEN,      EnumSet.of(LifecycleState.LISTED, LifecycleState.SUNSETTING),
                LifecycleState.SUNSETTING,  EnumSet.of(LifecycleState.HIDDEN)
        );

        if (!allowed.get(from).contains(to)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Illegal lifecycle transition: " + from + " → " + to
                            + ". See docs/plans/tier-lifecycle-plan.md for the state machine.");
        }

        // 14-day observation window for HIDDEN → SUNSETTING.
        if (from == LifecycleState.HIDDEN && to == LifecycleState.SUNSETTING) {
            if (lastChangedAt == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "HIDDEN → SUNSETTING requires a " + MIN_HIDDEN_BEFORE_SUNSET.toDays()
                                + "-day observation window. Lifecycle change timestamp is missing.");
            }
            Duration sinceHidden = Duration.between(lastChangedAt, Instant.now());
            if (sinceHidden.compareTo(MIN_HIDDEN_BEFORE_SUNSET) < 0) {
                long daysLeft = MIN_HIDDEN_BEFORE_SUNSET.minus(sinceHidden).toDays() + 1;
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "HIDDEN → SUNSETTING requires a " + MIN_HIDDEN_BEFORE_SUNSET.toDays()
                                + "-day observation window. " + daysLeft + " day(s) remaining.");
            }
        }
    }

    /**
     * v2.6.0 — per-tier lifecycle + MRR summary for the admin page. Reads
     * the static tier_config metadata, joins active subscriptions to compute
     * subscriber count and monthly revenue (USD). Billing plans are
     * aggregated by tier — TEAM row sums {@code totalSeats × perSeatUsd}
     * across all subscriptions on the tier; non-TEAM sums
     * {@code baseAmountUsd} per active sub.
     */
    @Transactional(readOnly = true)
    public List<TierLifecycleDto> listAll() {
        List<TierLifecycleDto> out = new ArrayList<>();
        BillingSubscriptionRepository subRepo = subscriptionRepoProvider != null
                ? subscriptionRepoProvider.getIfAvailable() : null;

        for (TierConfig t : tierConfigRepo.findAll()) {
            long subs = 0;
            long mrrCents = 0;
            if (subRepo != null) {
                List<BillingSubscription> list = subRepo.findActiveByTier(t.getTier());
                subs = list.size();
                for (BillingSubscription s : list) {
                    BillingPlan plan = s.getBillingPlanId() != null
                            ? billingPlanRepo.findById(s.getBillingPlanId()).orElse(null)
                            : null;
                    if (plan == null) continue;
                    if ("TEAM".equals(t.getTier())) {
                        mrrCents += (long) plan.getPerSeatUsd() * Math.max(s.getTotalSeats(), 1);
                    } else {
                        mrrCents += plan.getBaseAmountUsd();
                    }
                }
            }
            out.add(new TierLifecycleDto(
                    t.getTier(),
                    t.getDisplayName(),
                    LifecycleState.fromDbValue(t.getLifecycleState()),
                    t.getLifecycleChangedAt(),
                    t.getLifecycleChangedBy(),
                    t.getLifecycleReason(),
                    subs,
                    mrrCents
            ));
        }
        return out;
    }

    /** Admin-summary DTO for {@code GET /api/admin/tiers}. */
    public record TierLifecycleDto(
            String tier,
            String displayName,
            LifecycleState state,
            Instant changedAt,
            String changedBy,
            String reason,
            long activeSubscribers,
            long monthlyRecurringRevenueUsdCents
    ) {}

    /**
     * v2.6.0 — enumerate active subscriptions on the sunsetted tier and queue
     * cancel-at-period-end on each gateway. Gated behind the
     * {@code billing.lifecycle.sunsetting.enabled} feature flag — when the
     * flag is OFF (default), this is a no-op and only the audit row + DB
     * column reflect the state change.
     *
     * <p>Failures per-subscription are logged and recorded in audit (one row
     * per failure) but do not abort the rest of the cascade — we want to
     * queue as many as possible on the first pass; a retry job can pick up
     * stragglers. Returns a summary of processed / failed counts.
     *
     * <p>Email dispatch to affected subscribers is a Day-6 follow-up;
     * scaffolded here with a log line so the call site is visible.
     */
    public SunsettingSideEffectsResult executeSunsettingSideEffects(String tier, String actorUserId) {
        FeatureFlagService flags = featureFlagProvider != null ? featureFlagProvider.getIfAvailable() : null;
        if (flags == null || !flags.isEnabled(SUNSETTING_ENABLED_FLAG)) {
            log.info("SUNSETTING side-effects skipped for tier={} (flag off — flip {} to enable)",
                    tier, SUNSETTING_ENABLED_FLAG);
            return new SunsettingSideEffectsResult(0, 0, 0, List.of("flag_off"));
        }

        BillingSubscriptionRepository subRepo = subscriptionRepoProvider != null
                ? subscriptionRepoProvider.getIfAvailable() : null;
        PaymentGatewayRouter router = gatewayRouterProvider != null
                ? gatewayRouterProvider.getIfAvailable() : null;
        if (subRepo == null || router == null) {
            log.error("SUNSETTING side-effects skipped for tier={} — billing dependencies not available", tier);
            return new SunsettingSideEffectsResult(0, 0, 0, List.of("dependencies_unavailable"));
        }

        List<BillingSubscription> subs = subRepo.findActiveByTier(tier);
        log.info("SUNSETTING: enumerating {} active subscription(s) on tier={}", subs.size(), tier);

        int queued = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        for (BillingSubscription sub : subs) {
            try {
                String gatewayName = sub.getPaymentGateway() != null ? sub.getPaymentGateway() : sub.getGateway();
                if (gatewayName == null || sub.getGatewaySubscriptionId() == null) {
                    errors.add("no_gateway_or_sub_id:" + sub.getId());
                    failed++;
                    continue;
                }
                PaymentGateway gateway = router.resolveByName(gatewayName);
                gateway.cancelAtPeriodEnd(sub.getGatewaySubscriptionId());
                sub.setStatus("CANCELLING_AT_PERIOD_END");
                subRepo.save(sub);
                auditService.record(ActivityAudit.builder()
                        .eventType("TIER_SUNSET_QUEUED")
                        .entityType("BILLING_SUBSCRIPTION")
                        .entityId(sub.getId().toString())
                        .actorId(actorUserId)
                        .newValue("CANCELLING_AT_PERIOD_END")
                        .metadata("{\"tier\":\"" + tier + "\",\"gateway\":\"" + gatewayName + "\"}")
                        .build());
                queued++;
                // TODO Day-6: fire tier-sunsetting.html email to sub.userId.
                log.info("SUNSETTING queued: subId={} gateway={} tier={}",
                        sub.getGatewaySubscriptionId(), gatewayName, tier);
            } catch (Exception e) {
                log.error("SUNSETTING cancel-at-period-end failed: sub={}, tier={}, error={}",
                        sub.getGatewaySubscriptionId(), tier, e.getMessage(), e);
                errors.add(sub.getId() + ":" + e.getMessage());
                failed++;
            }
        }

        log.info("SUNSETTING complete: tier={} queued={} failed={} errors={}",
                tier, queued, failed, errors.size());
        return new SunsettingSideEffectsResult(subs.size(), queued, failed, errors);
    }

    /**
     * Result of a {@link #executeSunsettingSideEffects} run. Exposed so the
     * admin UI can show "N queued, M failed" after a SUNSETTING transition.
     */
    public record SunsettingSideEffectsResult(
            int totalConsidered,
            int queuedForCancellation,
            int failed,
            List<String> errors
    ) {}

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

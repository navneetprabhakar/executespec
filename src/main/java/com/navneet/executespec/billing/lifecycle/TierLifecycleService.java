package com.navneet.executespec.billing.lifecycle;

import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.auth.service.ActivityAuditService;
import com.navneet.executespec.auth.entity.ActivityAudit;
import com.navneet.executespec.billing.entity.BillingPlan;
import com.navneet.executespec.billing.repository.BillingPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
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

    private final TierConfigRepository tierConfigRepo;
    private final BillingPlanRepository billingPlanRepo;
    private final ActivityAuditService auditService;

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

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

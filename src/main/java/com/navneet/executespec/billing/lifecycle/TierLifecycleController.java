package com.navneet.executespec.billing.lifecycle;

import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.billing.entity.BillingPlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * v2.6.0 — admin-only routes for the tier lifecycle state machine. Powers the
 * {@code /admin/tiers} frontend page. All endpoints are SUPER_ADMIN gated via
 * class-level {@code @PreAuthorize}; the spring-security pattern under
 * {@code /api/admin/**} requires {@code hasRole('SUPER_ADMIN')}.
 *
 * <p>Full design: {@code docs/plans/tier-lifecycle-plan.md}.
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
@Slf4j
public class TierLifecycleController {

    private final TierLifecycleService lifecycleService;

    /** Request body for {@code PUT /api/admin/tiers/{tier}/lifecycle}. */
    public record LifecycleUpdateRequest(
            String state,
            String reason,
            /** Required when target state is SUNSETTING — must equal the tier name. */
            String confirmTierName
    ) {}

    /** Request body for {@code PUT /api/admin/billing-plans/{planId}/lifecycle}. */
    public record PlanLifecycleUpdateRequest(
            String state,
            String reason
    ) {}

    // ── Tier-level routes ────────────────────────────────────────────────────

    @GetMapping("/tiers")
    public ResponseEntity<List<TierLifecycleService.TierLifecycleDto>> listTiers() {
        return ResponseEntity.ok(lifecycleService.listAll());
    }

    @PutMapping("/tiers/{tier}/lifecycle")
    public ResponseEntity<TierConfig> updateTierLifecycle(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @PathVariable String tier,
            @RequestBody LifecycleUpdateRequest req) {

        LifecycleState target = parseState(req.state());
        String reason = requireReason(req.reason());

        // SUNSETTING requires typed confirmation — server-side check, not just UI.
        if (target == LifecycleState.SUNSETTING) {
            if (req.confirmTierName() == null || !req.confirmTierName().equals(tier)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "SUNSETTING transition requires confirmTierName to match the tier. " +
                        "Got: " + req.confirmTierName() + " expected: " + tier);
            }
        }

        TierConfig saved = lifecycleService.transitionTier(
                tier, target, userDetails.getUser().getId(), reason);
        log.info("Admin tier lifecycle update: tier={} state={} actor={}",
                tier, target, userDetails.getUser().getId());
        return ResponseEntity.ok(saved);
    }

    // ── Plan-level routes (per-cycle overrides) ─────────────────────────────

    @PutMapping("/billing-plans/{planId}/lifecycle")
    public ResponseEntity<BillingPlan> updatePlanLifecycle(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @PathVariable UUID planId,
            @RequestBody PlanLifecycleUpdateRequest req) {

        LifecycleState target = parseState(req.state());
        String reason = requireReason(req.reason());

        BillingPlan saved = lifecycleService.transitionPlan(
                planId, target, userDetails.getUser().getId(), reason);
        log.info("Admin plan lifecycle update: planId={} state={} actor={}",
                planId, target, userDetails.getUser().getId());
        return ResponseEntity.ok(saved);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static LifecycleState parseState(String raw) {
        if (raw == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "state is required (LISTED | HIDDEN | SUNSETTING)");
        }
        try {
            return LifecycleState.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid state: " + raw + " (expected LISTED | HIDDEN | SUNSETTING)");
        }
    }

    private static String requireReason(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "reason is required and cannot be blank — this is logged in audit");
        }
        return raw;
    }
}

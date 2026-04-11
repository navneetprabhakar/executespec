package com.navneet.executespec.api.controller;

import com.navneet.executespec.api.dto.BillingOverview;
import com.navneet.executespec.api.dto.MemberUsage;
import com.navneet.executespec.api.dto.UsageMetrics;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.OrgMember;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.billing.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Admin analytics endpoints.
 *
 * <p>RBAC:
 * <ul>
 *   <li>SUPER_ADMIN — any entity</li>
 *   <li>ORG_ADMIN — own org only</li>
 *   <li>BILLING_ADMIN — own org billing only</li>
 *   <li>Standalone user — own usage and billing only</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/analytics")
@RequiredArgsConstructor
@Slf4j
public class AdminAnalyticsController {

    private final AnalyticsService analyticsService;
    private final RunAccessGuard guard;

    /**
     * GET /api/admin/analytics/usage?entityType={USER|ORG}&entityId={id}&period=30
     */
    @GetMapping("/usage")
    public ResponseEntity<UsageMetrics> usage(
            @RequestParam String entityType,
            @RequestParam String entityId,
            @RequestParam(defaultValue = "30") int period,
            @AuthenticationPrincipal AppUserDetails userDetails) {

        assertAnalyticsAccess(entityType, entityId, userDetails, false);
        return ResponseEntity.ok(analyticsService.getUsageMetrics(entityType, entityId, period));
    }

    /**
     * GET /api/admin/analytics/members?orgId={}&period=30
     */
    @GetMapping("/members")
    public ResponseEntity<List<MemberUsage>> members(
            @RequestParam String orgId,
            @RequestParam(defaultValue = "30") int period,
            @AuthenticationPrincipal AppUserDetails userDetails) {

        assertAnalyticsAccess("ORG", orgId, userDetails, false);
        return ResponseEntity.ok(analyticsService.getMemberUsage(orgId, period));
    }

    /**
     * GET /api/admin/analytics/billing?entityType={USER|ORG}&entityId={id}
     */
    @GetMapping("/billing")
    public ResponseEntity<BillingOverview> billing(
            @RequestParam String entityType,
            @RequestParam String entityId,
            @AuthenticationPrincipal AppUserDetails userDetails) {

        assertAnalyticsAccess(entityType, entityId, userDetails, true);
        return ResponseEntity.ok(analyticsService.getBillingOverview(entityType, entityId));
    }

    // ── RBAC ────────────────────────────────────────────────────────────────────

    /**
     * Asserts the caller is allowed to view analytics for the given entity.
     *
     * @param billingOnly if true, BILLING_ADMIN is also allowed (for billing endpoints)
     */
    private void assertAnalyticsAccess(String entityType, String entityId,
                                        AppUserDetails userDetails, boolean billingOnly) {
        guard.requireAuthenticated(userDetails);
        AppUser user = userDetails.getUser();

        // SUPER_ADMIN — unrestricted
        if ("SUPER_ADMIN".equals(user.getGlobalRole())) return;

        OrgMember orgMember = userDetails.getOrgMember();

        if ("ORG".equals(entityType)) {
            // ORG_ADMIN or BILLING_ADMIN for own org
            if (orgMember != null && entityId.equals(user.getOrgId())) {
                if ("ORG_ADMIN".equals(orgMember.getOrgRole())) return;
                if (billingOnly && "BILLING_ADMIN".equals(orgMember.getOrgRole())) return;
            }
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        if ("USER".equals(entityType)) {
            // Standalone user can view own data
            if (entityId.equals(user.getId())) return;

            // ORG_ADMIN can view members in their org (handled by checking org membership)
            if (orgMember != null && "ORG_ADMIN".equals(orgMember.getOrgRole())) return;

            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Invalid entityType: " + entityType + ". Must be USER or ORG.");
    }
}

package com.navneet.executespec.auth.service;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.OrgMember;
import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgMemberRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Central permission service — computes feature-level boolean flags for a user
 * based on their tier, org membership, and org role.
 *
 * This is the single source of truth for "can user X do feature Y?".
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TierPermissionService {

    private final AppUserRepository userRepo;
    private final OrgRepository orgRepo;
    private final OrgMemberRepository orgMemberRepo;
    private final TierConfigRepository tierConfigRepo;
    private final TierEnforcementService tierEnforcementService;

    // ── Public record ───────────────────────────────────────────────────────

    public record TierPermissions(
            boolean canManageAiKeys,
            boolean canViewPricing,
            boolean canManageIntegrations,
            boolean canViewMetrics,
            boolean canViewCostBreakdown,
            boolean canViewTokenUsage,
            boolean canUseChat,
            boolean canCreateOrg,
            boolean canInviteMembers,
            boolean canViewOrgMembers,
            boolean canViewAudit,
            boolean canViewLiveLog,
            // Finance permissions
            boolean canViewInvoices,
            boolean canViewAccounting,
            boolean canManageCreditNotes,
            boolean canExportGstr,
            boolean canManageSellerConfig,
            boolean canManageBillingProfile,
            int monthlyRunLimit,
            int currentRunCount,
            int maxOrgMembers,
            int currentOrgMembers,
            int maxApplySuggestions,
            int maxPreviewStarts
    ) {}

    // ── Main entry point ────────────────────────────────────────────────────

    public TierPermissions getPermissions(String userId) {
        AppUser user = userRepo.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        // SUPER_ADMIN — unrestricted
        if ("SUPER_ADMIN".equals(user.getGlobalRole())) {
            return superAdminPermissions(user);
        }

        // FINANCE role — read-only access to invoices, accounting, audit
        if ("FINANCE".equals(user.getGlobalRole())) {
            return financePermissions(user);
        }

        // User with org membership
        if (user.getOrgId() != null) {
            return orgMemberPermissions(user);
        }

        // FREE solo user (no org)
        return freeUserPermissions(user);
    }

    // ── Permission builders ─────────────────────────────────────────────────

    private TierPermissions superAdminPermissions(AppUser user) {
        // SUPER_ADMIN always unlimited — never show quota constraints
        // regardless of which org they belong to
        return new TierPermissions(
                true,  // canManageAiKeys
                true,  // canViewPricing
                true,  // canManageIntegrations
                true,  // canViewMetrics
                true,  // canViewCostBreakdown
                true,  // canViewTokenUsage
                true,  // canUseChat
                true,  // canCreateOrg
                true,  // canInviteMembers
                true,  // canViewOrgMembers
                true,  // canViewAudit
                true,  // canViewLiveLog
                true,  // canViewInvoices
                true,  // canViewAccounting
                true,  // canManageCreditNotes
                true,  // canExportGstr
                true,  // canManageSellerConfig
                true,  // canManageBillingProfile
                -1,    // monthlyRunLimit = unlimited
                0,     // currentRunCount = irrelevant
                -1,    // maxMembers = unlimited
                0,     // currentMembers = irrelevant
                -1,    // maxApplySuggestions = unlimited
                -1     // maxPreviewStarts = unlimited
        );
    }

    private TierPermissions financePermissions(AppUser user) {
        return new TierPermissions(
                false, // canManageAiKeys
                false, // canViewPricing
                false, // canManageIntegrations
                false, // canViewMetrics
                true,  // canViewCostBreakdown
                false, // canViewTokenUsage
                false, // canUseChat
                false, // canCreateOrg
                false, // canInviteMembers
                false, // canViewOrgMembers
                true,  // canViewAudit
                false, // canViewLiveLog
                true,  // canViewInvoices
                true,  // canViewAccounting
                true,  // canManageCreditNotes
                true,  // canExportGstr
                false, // canManageSellerConfig (super admin only)
                true,  // canManageBillingProfile
                0,     // monthlyRunLimit
                0,     // currentRunCount
                -1,    // maxMembers
                0,     // currentMembers
                0,     // maxApplySuggestions
                0      // maxPreviewStarts
        );
    }

    private TierPermissions orgMemberPermissions(AppUser user) {
        Org org = orgRepo.findById(user.getOrgId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Org not found"));

        OrgMember membership = orgMemberRepo.findByOrgIdAndUserId(org.getId(), user.getId())
                .orElse(null);

        String orgRole = (membership != null) ? membership.getOrgRole() : "DEVELOPER";
        boolean isOrgAdmin = "ORG_ADMIN".equals(orgRole);

        int limit = tierEnforcementService.effectiveOrgLimit(org);
        int used = org.getMonthlyRunCount();
        int maxMembers = resolveMaxOrgMembers(org);
        int currentMembers = (int) orgMemberRepo.countByOrgId(org.getId());
        int maxApply = resolveMaxApplySuggestions(org.getTier());
        int maxPreviews = resolveMaxPreviewStarts(org.getTier());

        if (isOrgAdmin) {
            // ORG_ADMIN (PRO or ENTERPRISE) — full access except cost breakdown (super admin only)
            return new TierPermissions(
                    true,  // canManageAiKeys
                    true,  // canViewPricing
                    true,  // canManageIntegrations
                    true,  // canViewMetrics
                    false, // canViewCostBreakdown (super admin only)
                    true,  // canViewTokenUsage
                    true,  // canUseChat
                    true,  // canCreateOrg
                    true,  // canInviteMembers
                    true,  // canViewOrgMembers
                    true,  // canViewAudit
                    true,  // canViewLiveLog
                    true,  // canViewInvoices
                    false, // canViewAccounting (finance + super admin)
                    false, // canManageCreditNotes (finance + super admin)
                    false, // canExportGstr (finance + super admin)
                    false, // canManageSellerConfig
                    true,  // canManageBillingProfile
                    limit, used, maxMembers, currentMembers, maxApply, maxPreviews
            );
        }

        // DEVELOPER in org (PRO or ENTERPRISE) — limited: chat + token usage + view members
        return new TierPermissions(
                false, // canManageAiKeys
                false, // canViewPricing
                false, // canManageIntegrations
                false, // canViewMetrics
                false, // canViewCostBreakdown
                true,  // canViewTokenUsage
                true,  // canUseChat
                false, // canCreateOrg
                false, // canInviteMembers
                true,  // canViewOrgMembers (read-only)
                false, // canViewAudit
                false, // canViewLiveLog (org admin & super admin only)
                false, // canViewInvoices
                false, // canViewAccounting
                false, // canManageCreditNotes
                false, // canExportGstr
                false, // canManageSellerConfig
                false, // canManageBillingProfile
                limit, used, maxMembers, currentMembers, maxApply, maxPreviews
        );
    }

    private TierPermissions freeUserPermissions(AppUser user) {
        int limit = tierEnforcementService.effectiveUserLimit(user);
        int used = user.getPersonalRunCount();
        int maxApply = resolveMaxApplySuggestions("FREE");
        int maxPreviews = resolveMaxPreviewStarts("FREE");

        return new TierPermissions(
                false, // canManageAiKeys
                false, // canViewPricing
                false, // canManageIntegrations
                false, // canViewMetrics
                false, // canViewCostBreakdown
                false, // canViewTokenUsage
                false, // canUseChat
                false, // canCreateOrg
                false, // canInviteMembers
                false, // canViewOrgMembers
                false, // canViewAudit
                false, // canViewLiveLog
                true,  // canViewInvoices (own invoices)
                false, // canViewAccounting
                false, // canManageCreditNotes
                false, // canExportGstr
                false, // canManageSellerConfig
                true,  // canManageBillingProfile (own profile)
                limit, used, -1, 0, maxApply, maxPreviews
        );
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Resolve max org members: per-org override → tier_config default.
     */
    private int resolveMaxOrgMembers(Org org) {
        if (org.getMaxMembers() != null) return org.getMaxMembers();
        return tierConfigRepo.findById(org.getTier())
                .map(TierConfig::getMaxOrgMembers)
                .orElse(-1);
    }

    /**
     * Resolve max apply suggestions from tier_config for the given tier.
     */
    private int resolveMaxApplySuggestions(String tier) {
        if (tier == null) return 2;
        return tierConfigRepo.findById(tier.toUpperCase())
                .map(TierConfig::getMaxApplySuggestions)
                .orElse(2);
    }

    /**
     * Resolve max preview starts per run from tier_config for the given tier.
     */
    private int resolveMaxPreviewStarts(String tier) {
        if (tier == null) return 3;
        return tierConfigRepo.findById(tier.toUpperCase())
                .map(TierConfig::getMaxPreviewStarts)
                .orElse(3);
    }
}

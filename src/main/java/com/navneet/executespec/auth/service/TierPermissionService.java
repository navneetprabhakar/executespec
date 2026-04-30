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
            int maxPreviewStarts,
            // Deploy engine
            boolean canDeploy,
            int maxDeployments
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
                -1,    // maxPreviewStarts = unlimited
                true,  // canDeploy
                -1     // maxDeployments = unlimited
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
                0,     // maxPreviewStarts
                false, // canDeploy
                0      // maxDeployments
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
        boolean canDeploy = resolveCanDeploy(org.getTier());
        int maxDeploys = resolveMaxDeployments(org.getTier());

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
                    limit, used, maxMembers, currentMembers, maxApply, maxPreviews,
                    canDeploy, maxDeploys
            );
        }

        // DEVELOPER in org — run detail tabs unlocked for all tiers
        return new TierPermissions(
                false, // canManageAiKeys
                false, // canViewPricing
                false, // canManageIntegrations
                true,  // canViewMetrics — unlocked for all tiers
                false, // canViewCostBreakdown
                true,  // canViewTokenUsage
                true,  // canUseChat
                false, // canCreateOrg
                false, // canInviteMembers
                true,  // canViewOrgMembers (read-only)
                false, // canViewAudit
                true,  // canViewLiveLog — unlocked for all tiers
                false, // canViewInvoices
                false, // canViewAccounting
                false, // canManageCreditNotes
                false, // canExportGstr
                false, // canManageSellerConfig
                false, // canManageBillingProfile
                limit, used, maxMembers, currentMembers, maxApply, maxPreviews,
                canDeploy, maxDeploys
        );
    }

    private TierPermissions freeUserPermissions(AppUser user) {
        // [F-03] (audit Sprint 1 PR-3) — solo-user permissions now resolve
        // from tier_config instead of being hardcoded. Was: 5 fields
        // (canDeploy, maxDeployments, canViewCostBreakdown, canManageAiKeys
        // path, canManageBillingProfile) ignored their tier_config columns.
        // Live API probes against the v2.5.11 audit baseline confirmed:
        //   • ENTERPRISE solo got canDeploy=true / maxDeployments=3
        //     (should be unlimited per tier_config.ENTERPRISE)
        //   • ENTERPRISE solo got canViewCostBreakdown=false
        //     (audit's headline contradiction)
        //   • FREE solo got canDeploy=true (tier_config.FREE.can_deploy=false)
        //   • FREE solo got canManageBillingProfile=true
        //     (no org to attach a billing profile to — F-18)
        // After this fix the API matches tier_config + role intent for solo
        // users (no-org path); org-member users keep going through
        // orgMemberPermissions() above.
        String tier = user.getTier() != null ? user.getTier() : "FREE";
        int limit = tierEnforcementService.effectiveUserLimit(user);
        int used = user.getPersonalRunCount();
        int maxApply = resolveMaxApplySuggestions(tier);
        int maxPreviews = resolveMaxPreviewStarts(tier);
        boolean isMaxOrEnterprise = "MAX".equals(tier) || "ENTERPRISE".equals(tier);
        boolean isFree = "FREE".equals(tier);

        return new TierPermissions(
                isMaxOrEnterprise, // canManageAiKeys — MAX/ENTERPRISE need BYOK
                false, // canViewPricing — admin-only; consistent with admin-page gating
                false, // canManageIntegrations
                true,  // canViewMetrics — unlocked for all tiers
                isMaxOrEnterprise, // [F-03] canViewCostBreakdown — MAX/ENT solo can see their own cost breakdown (was: false hardcoded)
                true,  // canViewTokenUsage — unlocked for all tiers
                true,  // canUseChat — unlocked for all tiers
                false, // canCreateOrg
                false, // canInviteMembers
                false, // canViewOrgMembers
                false, // canViewAudit
                true,  // canViewLiveLog — unlocked for all tiers
                true,  // canViewInvoices (own invoices)
                false, // canViewAccounting
                false, // canManageCreditNotes
                false, // canExportGstr
                false, // canManageSellerConfig
                !isFree, // [F-18] canManageBillingProfile — paid tiers only; FREE has no billing context (was: true hardcoded for everyone)
                limit, used, -1, 0, maxApply, maxPreviews,
                resolveCanDeploy(tier),    // [F-03] from tier_config (was: true hardcoded)
                resolveMaxDeployments(tier) // [F-03] from tier_config (was: 3 hardcoded)
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

    /**
     * Resolve whether deploy is enabled for the given tier.
     */
    private boolean resolveCanDeploy(String tier) {
        if (tier == null) return false;
        return tierConfigRepo.findById(tier.toUpperCase())
                .map(TierConfig::isCanDeploy)
                .orElse(false);
    }

    /**
     * Resolve max active deployments from tier_config for the given tier.
     */
    private int resolveMaxDeployments(String tier) {
        if (tier == null) return 0;
        return tierConfigRepo.findById(tier.toUpperCase())
                .map(TierConfig::getMaxDeployments)
                .orElse(0);
    }
}

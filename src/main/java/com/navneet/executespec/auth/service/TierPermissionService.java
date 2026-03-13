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
            int monthlyRunLimit,
            int currentRunCount,
            int maxOrgMembers,
            int currentOrgMembers
    ) {}

    // ── Main entry point ────────────────────────────────────────────────────

    public TierPermissions getPermissions(String userId) {
        AppUser user = userRepo.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        // SUPER_ADMIN — unrestricted
        if ("SUPER_ADMIN".equals(user.getGlobalRole())) {
            return superAdminPermissions(user);
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
        int limit = -1; // unlimited
        int used = 0;
        int maxMembers = -1;
        int currentMembers = 0;

        if (user.getOrgId() != null) {
            Org org = orgRepo.findById(user.getOrgId()).orElse(null);
            if (org != null) {
                limit = tierEnforcementService.effectiveOrgLimit(org);
                used = org.getMonthlyRunCount();
                maxMembers = resolveMaxOrgMembers(org);
                currentMembers = (int) orgMemberRepo.countByOrgId(org.getId());
            }
        }

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
                limit, used, maxMembers, currentMembers
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
                    limit, used, maxMembers, currentMembers
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
                limit, used, maxMembers, currentMembers
        );
    }

    private TierPermissions freeUserPermissions(AppUser user) {
        int limit = tierEnforcementService.effectiveUserLimit(user);
        int used = user.getPersonalRunCount();

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
                limit, used, -1, 0
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
}

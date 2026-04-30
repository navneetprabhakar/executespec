package com.navneet.executespec.auth.service;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgMemberRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [F-03] regression test — pin solo-user permission map per tier so the
 * `freeUserPermissions` builder can't drift from `tier_config` again.
 *
 * Audit (v2.5.11) found 5 hardcoded values in `freeUserPermissions` that
 * ignored the per-tier overrides documented in `tier_config`:
 *   • canDeploy hardcoded true (FREE.can_deploy = false)
 *   • maxDeployments hardcoded 3 (ENT.max_deployments = -1)
 *   • canViewCostBreakdown hardcoded false (MAX/ENT solo should see own cost)
 *   • canManageBillingProfile hardcoded true (FREE has no billing context)
 *
 * These tests pin the 5 corrected fields per tier so the next refactor
 * pass can't regress them silently.
 */
class TierPermissionServiceSoloUserTest {

    private AppUserRepository userRepo;
    private TierConfigRepository tierConfigRepo;
    private TierEnforcementService tierEnforcementService;
    private TierPermissionService svc;

    @BeforeEach
    void setUp() {
        userRepo = mock(AppUserRepository.class);
        OrgRepository orgRepo = mock(OrgRepository.class);
        OrgMemberRepository orgMemberRepo = mock(OrgMemberRepository.class);
        tierConfigRepo = mock(TierConfigRepository.class);
        tierEnforcementService = mock(TierEnforcementService.class);
        svc = new TierPermissionService(
                userRepo, orgRepo, orgMemberRepo, tierConfigRepo, tierEnforcementService);
    }

    private AppUser solo(String tier) {
        AppUser u = new AppUser();
        u.setId("u-" + tier);
        u.setTier(tier);
        u.setGlobalRole("USER");
        u.setOrgId(null); // solo path — falls through to freeUserPermissions
        u.setPersonalRunCount(0);
        when(userRepo.findById("u-" + tier)).thenReturn(Optional.of(u));
        when(tierEnforcementService.effectiveUserLimit(u)).thenReturn(canonicalLimit(tier));
        return u;
    }

    private void seed(String tier, int monthly, boolean canDeploy, int maxDeploys,
                      int maxApply, int maxPreviews) {
        TierConfig cfg = new TierConfig();
        cfg.setTier(tier);
        cfg.setMonthlyRunLimit(monthly);
        cfg.setCanDeploy(canDeploy);
        cfg.setMaxDeployments(maxDeploys);
        cfg.setMaxApplySuggestions(maxApply);
        cfg.setMaxPreviewStarts(maxPreviews);
        when(tierConfigRepo.findById(tier)).thenReturn(Optional.of(cfg));
    }

    private int canonicalLimit(String tier) {
        return switch (tier) {
            case "FREE" -> 10;
            case "PRO" -> 20;
            case "TEAM" -> 50;
            default -> -1;
        };
    }

    @Test
    void freeSoloUser_canDeployFollowsTierConfigFalse() {
        solo("FREE");
        // tier_config seed: FREE has can_deploy=false, max_deployments=0
        seed("FREE", 10, false, 0, 2, 2);
        TierPermissionService.TierPermissions p = svc.getPermissions("u-FREE");

        assertThat(p.canDeploy()).as("FREE solo canDeploy reads tier_config (false)").isFalse();
        assertThat(p.maxDeployments()).as("FREE solo maxDeployments reads tier_config (0)").isEqualTo(0);
    }

    @Test
    void enterpriseSoloUser_canViewCostBreakdownTrueAndUnlimitedDeploys() {
        solo("ENTERPRISE");
        seed("ENTERPRISE", -1, true, -1, -1, -1);
        TierPermissionService.TierPermissions p = svc.getPermissions("u-ENTERPRISE");

        assertThat(p.canViewCostBreakdown()).as("ENT solo can see their own cost breakdown").isTrue();
        assertThat(p.maxDeployments()).as("ENT solo maxDeployments unlimited from tier_config").isEqualTo(-1);
        assertThat(p.canManageAiKeys()).as("ENT solo BYOK enabled").isTrue();
    }

    @Test
    void maxSoloUser_canManageAiKeysAndCostBreakdown() {
        solo("MAX");
        seed("MAX", -1, true, -1, -1, -1);
        TierPermissionService.TierPermissions p = svc.getPermissions("u-MAX");

        assertThat(p.canManageAiKeys()).isTrue();
        assertThat(p.canViewCostBreakdown()).as("MAX solo can see their own cost breakdown").isTrue();
        assertThat(p.canDeploy()).isTrue();
    }

    @Test
    void proSoloUser_canDeployButCantBYOK() {
        solo("PRO");
        seed("PRO", 20, true, 3, 10, 5);
        TierPermissionService.TierPermissions p = svc.getPermissions("u-PRO");

        assertThat(p.canDeploy()).as("PRO solo can deploy per tier_config").isTrue();
        assertThat(p.maxDeployments()).isEqualTo(3);
        assertThat(p.canManageAiKeys()).as("PRO solo cannot BYOK (only MAX/ENT)").isFalse();
        assertThat(p.canViewCostBreakdown()).as("PRO solo cannot see cost breakdown (admin/MAX/ENT only)").isFalse();
    }

    @Test
    void freeSoloUser_canManageBillingProfileFalse() {
        // [F-18] FREE has no billing context — should not show "Manage billing profile" UI
        solo("FREE");
        seed("FREE", 10, false, 0, 2, 2);
        TierPermissionService.TierPermissions p = svc.getPermissions("u-FREE");

        assertThat(p.canManageBillingProfile()).as("FREE solo has no billing profile to manage").isFalse();
    }

    @Test
    void proSoloUser_canManageBillingProfile() {
        // PRO solo IS a paying user — billing profile is meaningful
        solo("PRO");
        seed("PRO", 20, true, 3, 10, 5);
        TierPermissionService.TierPermissions p = svc.getPermissions("u-PRO");

        assertThat(p.canManageBillingProfile()).isTrue();
    }

    @Test
    void enterpriseSoloUser_canManageBillingProfile() {
        solo("ENTERPRISE");
        seed("ENTERPRISE", -1, true, -1, -1, -1);
        TierPermissionService.TierPermissions p = svc.getPermissions("u-ENTERPRISE");

        assertThat(p.canManageBillingProfile()).isTrue();
    }

    @Test
    void unlimitedRunQuota_correctlyReportedFromEnforcementService() {
        // Sanity: monthlyRunLimit comes from TierEnforcementService.effectiveUserLimit, not the hardcoded -1
        solo("ENTERPRISE");
        seed("ENTERPRISE", -1, true, -1, -1, -1);
        TierPermissionService.TierPermissions p = svc.getPermissions("u-ENTERPRISE");
        assertThat(p.monthlyRunLimit()).isEqualTo(-1);
    }
}

package com.navneet.executespec.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgMemberRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * [F-03] regression test — pin solo-user permission map per tier so the `freeUserPermissions`
 * builder can't drift from `tier_config` again.
 *
 * <p>Audit (v2.5.11) found 5 hardcoded values in `freeUserPermissions` that ignored the per-tier
 * overrides documented in `tier_config`: • canDeploy hardcoded true (FREE.can_deploy = false) •
 * maxDeployments hardcoded 3 (ENT.max_deployments = -1) • canViewCostBreakdown hardcoded false
 * (MAX/ENT solo should see own cost) • canManageBillingProfile hardcoded true (FREE has no billing
 * context)
 *
 * <p>These tests pin the 5 corrected fields per tier so the next refactor pass can't regress them
 * silently.
 */
class TierPermissionServiceSoloUserTest {

  private AppUserRepository userRepo;
  private OrgRepository orgRepo;
  private TierConfigRepository tierConfigRepo;
  private TierEnforcementService tierEnforcementService;
  private TierPermissionService svc;

  @BeforeEach
  void setUp() {
    userRepo = mock(AppUserRepository.class);
    orgRepo = mock(OrgRepository.class);
    OrgMemberRepository orgMemberRepo = mock(OrgMemberRepository.class);
    tierConfigRepo = mock(TierConfigRepository.class);
    tierEnforcementService = mock(TierEnforcementService.class);
    svc =
        new TierPermissionService(
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

  private void seed(
      String tier, int monthly, boolean canDeploy, int maxDeploys, int maxApply, int maxPreviews) {
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
    assertThat(p.maxDeployments())
        .as("FREE solo maxDeployments reads tier_config (0)")
        .isEqualTo(0);
  }

  @Test
  void enterpriseSoloUser_canViewCostBreakdownTrueAndUnlimitedDeploys() {
    solo("ENTERPRISE");
    seed("ENTERPRISE", -1, true, -1, -1, -1);
    TierPermissionService.TierPermissions p = svc.getPermissions("u-ENTERPRISE");

    assertThat(p.canViewCostBreakdown()).as("ENT solo can see their own cost breakdown").isTrue();
    assertThat(p.maxDeployments())
        .as("ENT solo maxDeployments unlimited from tier_config")
        .isEqualTo(-1);
    assertThat(p.canManageAiKeys()).as("ENT solo BYOK enabled").isTrue();
  }

  @Test
  void teamSoloUser_canManageAiKeysAndCostBreakdown() {
    // V165 — MAX dropped; TEAM is now the BYOK-enabled paid tier.
    solo("TEAM");
    seed("TEAM", -1, true, -1, -1, -1);
    TierPermissionService.TierPermissions p = svc.getPermissions("u-TEAM");

    assertThat(p.canManageAiKeys()).isTrue();
    assertThat(p.canViewCostBreakdown()).as("TEAM solo can see their own cost breakdown").isTrue();
    assertThat(p.canDeploy()).isTrue();
  }

  @Test
  void proSoloUser_canDeployButCantBYOK() {
    solo("PRO");
    seed("PRO", 20, true, 3, 10, 5);
    TierPermissionService.TierPermissions p = svc.getPermissions("u-PRO");

    assertThat(p.canDeploy()).as("PRO solo can deploy per tier_config").isTrue();
    assertThat(p.maxDeployments()).isEqualTo(3);
    assertThat(p.canManageAiKeys()).as("PRO solo cannot BYOK (only TEAM/ENT)").isFalse();
    assertThat(p.canViewCostBreakdown())
        .as("PRO solo cannot see cost breakdown (admin/TEAM/ENT only)")
        .isFalse();
  }

  @Test
  void freeSoloUser_canManageBillingProfileFalse() {
    // [F-18] FREE has no billing context — should not show "Manage billing profile" UI
    solo("FREE");
    seed("FREE", 10, false, 0, 2, 2);
    TierPermissionService.TierPermissions p = svc.getPermissions("u-FREE");

    assertThat(p.canManageBillingProfile())
        .as("FREE solo has no billing profile to manage")
        .isFalse();
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
    // Sanity: monthlyRunLimit comes from TierEnforcementService.effectiveUserLimit, not the
    // hardcoded -1
    solo("ENTERPRISE");
    seed("ENTERPRISE", -1, true, -1, -1, -1);
    TierPermissionService.TierPermissions p = svc.getPermissions("u-ENTERPRISE");
    assertThat(p.monthlyRunLimit()).isEqualTo(-1);
  }

  // ── [J-01] (coverage Sprint 3 follow-up to F-03) ───────────────────────
  // Personal-org users (V162 bootstrap) must resolve through the
  // tier-driven solo path, not orgMemberPermissions, otherwise
  // the F-03 fix is dead code post-V162.

  private AppUser personalOrgUser(String tier, String orgId) {
    AppUser u = new AppUser();
    u.setId("p-" + tier);
    u.setTier(tier);
    u.setGlobalRole("USER");
    u.setOrgId(orgId);
    u.setPersonalRunCount(0);
    when(userRepo.findById("p-" + tier)).thenReturn(Optional.of(u));
    when(tierEnforcementService.effectiveUserLimit(u)).thenReturn(canonicalLimit(tier));
    Org org = new Org();
    org.setId(orgId);
    org.setTier(tier);
    org.setPersonal(true); // V162 personal-org marker (Lombok setter name)
    when(orgRepo.findById(orgId)).thenReturn(Optional.of(org));
    return u;
  }

  @Test
  void personalOrgEnterpriseUser_resolvesThroughSoloPath() {
    // Was: every fresh ENT user post-V162 went through orgMemberPermissions
    // (org_id != null branch) which hardcodes booleans tier-blind, so the
    // F-03 canViewCostBreakdown=isMaxOrEnterprise fix never fired.
    // Now: personal-org guard (J-01) routes them through freeUserPermissions
    // exactly like a no-org ENT user would.
    personalOrgUser("ENTERPRISE", "personal-org-ent");
    seed("ENTERPRISE", -1, true, -1, -1, -1);
    TierPermissionService.TierPermissions p = svc.getPermissions("p-ENTERPRISE");

    assertThat(p.canViewCostBreakdown()).as("personal-org ENT should see own cost").isTrue();
    assertThat(p.canManageAiKeys()).as("personal-org ENT BYOK enabled").isTrue();
    assertThat(p.maxDeployments()).as("personal-org ENT maxDeployments unlimited").isEqualTo(-1);
  }

  @Test
  void personalOrgFreeUser_canDeployFollowsTierConfigFalse() {
    // Personal-org FREE user must still respect tier_config.FREE.can_deploy=false.
    // Pre-J-01: V162 bootstrap → ORG_ADMIN → orgMemberPermissions → canDeploy=true (wrong).
    // Post-J-01: routes through freeUserPermissions → resolveCanDeploy("FREE")=false (correct).
    personalOrgUser("FREE", "personal-org-free");
    seed("FREE", 10, false, 0, 2, 2);
    TierPermissionService.TierPermissions p = svc.getPermissions("p-FREE");

    assertThat(p.canDeploy())
        .as("personal-org FREE canDeploy follows tier_config (false)")
        .isFalse();
    assertThat(p.maxDeployments()).isEqualTo(0);
    assertThat(p.canManageAiKeys()).as("personal-org FREE BYOK still locked").isFalse();
    assertThat(p.canManageBillingProfile())
        .as("personal-org FREE has no billing profile")
        .isFalse();
  }

  @Test
  void personalOrgProUser_canDeployButCantBYOK() {
    // Same matrix as solo PRO but reached through the personal-org route.
    personalOrgUser("PRO", "personal-org-pro");
    seed("PRO", 20, true, 3, 10, 5);
    TierPermissionService.TierPermissions p = svc.getPermissions("p-PRO");

    assertThat(p.canDeploy()).isTrue();
    assertThat(p.maxDeployments()).isEqualTo(3);
    assertThat(p.canManageAiKeys()).as("personal-org PRO cannot BYOK").isFalse();
    assertThat(p.canManageBillingProfile()).as("personal-org PRO has billing profile").isTrue();
  }

  // ── R8 (2026-05-01 follow-up Task 9 + R8) ─────────────────────────────
  // Personal-org users on TEAM/ENTERPRISE need org-management
  // permissions — they're paying for an Enterprise plan and procurement
  // expects to see audit logs + member management. Pre-fix the personal-org
  // route hardcoded these to false regardless of tier.

  @Test
  void personalOrgEnterpriseUser_canViewAuditAndManageOrg() {
    personalOrgUser("ENTERPRISE", "personal-org-ent");
    seed("ENTERPRISE", -1, true, -1, -1, -1);
    TierPermissionService.TierPermissions p = svc.getPermissions("p-ENTERPRISE");

    assertThat(p.canViewAudit()).as("ENT personal-org user can view audit log").isTrue();
    assertThat(p.canManageIntegrations())
        .as("ENT personal-org user can connect integrations")
        .isTrue();
    assertThat(p.canInviteMembers()).as("ENT personal-org user can invite teammates").isTrue();
    assertThat(p.canViewOrgMembers()).as("ENT personal-org user can see org members").isTrue();
    assertThat(p.canViewPricing()).as("ENT personal-org user can view pricing").isTrue();
  }

  @Test
  void personalOrgTeamUser_canViewAuditAndManageOrg() {
    personalOrgUser("TEAM", "personal-org-team");
    seed("TEAM", -1, true, -1, -1, -1);
    TierPermissionService.TierPermissions p = svc.getPermissions("p-TEAM");

    assertThat(p.canViewAudit()).as("TEAM personal-org user can view audit log").isTrue();
    assertThat(p.canManageIntegrations())
        .as("TEAM personal-org user can connect integrations")
        .isTrue();
    assertThat(p.canInviteMembers()).as("TEAM personal-org user can invite teammates").isTrue();
    assertThat(p.canViewOrgMembers()).as("TEAM personal-org user can see org members").isTrue();
  }

  @Test
  void personalOrgFreeUser_cannotViewAuditOrManageOrg() {
    // FREE / PRO must remain locked out of audit + org-management to
    // prevent regression on the gating story. Procurement parity is
    // a TEAM/ENT-only promise.
    personalOrgUser("FREE", "personal-org-free");
    seed("FREE", 10, false, 0, 2, 2);
    TierPermissionService.TierPermissions p = svc.getPermissions("p-FREE");

    assertThat(p.canViewAudit()).as("FREE personal-org user blocked from audit").isFalse();
    assertThat(p.canManageIntegrations())
        .as("FREE personal-org user blocked from integrations")
        .isFalse();
    assertThat(p.canInviteMembers()).as("FREE personal-org user blocked from invites").isFalse();
  }

  @Test
  void personalOrgProUser_cannotViewAuditOrManageOrg() {
    personalOrgUser("PRO", "personal-org-pro-r8");
    seed("PRO", 20, true, 3, 10, 5);
    TierPermissionService.TierPermissions p = svc.getPermissions("p-PRO");

    assertThat(p.canViewAudit()).as("PRO personal-org user blocked from audit").isFalse();
    assertThat(p.canManageIntegrations())
        .as("PRO personal-org user blocked from integrations")
        .isFalse();
  }
}

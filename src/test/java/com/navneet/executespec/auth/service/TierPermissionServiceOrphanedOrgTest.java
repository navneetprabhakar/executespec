package com.navneet.executespec.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.OrgMember;
import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgMemberRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.billing.config.ManagedAiProperties;
import com.navneet.executespec.config.OperationalReleasePolicy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * RR-210 — an `app_user.org_id` pointer with no matching `org_member` row must not yield
 * org-derived capabilities.
 *
 * <p>The previous code defaulted the missing role to DEVELOPER, so a user whose membership had been
 * removed (or never committed) kept receiving the org's developer permission set and the org's run
 * quota.
 */
class TierPermissionServiceOrphanedOrgTest {

  private AppUserRepository userRepo;
  private OrgRepository orgRepo;
  private OrgMemberRepository orgMemberRepo;
  private TierConfigRepository tierConfigRepo;
  private TierEnforcementService tierEnforcementService;
  private TierPermissionService svc;

  @BeforeEach
  void setUp() {
    userRepo = mock(AppUserRepository.class);
    orgRepo = mock(OrgRepository.class);
    orgMemberRepo = mock(OrgMemberRepository.class);
    tierConfigRepo = mock(TierConfigRepository.class);
    tierEnforcementService = mock(TierEnforcementService.class);
    OperationalReleasePolicy releasePolicy = mock(OperationalReleasePolicy.class);
    when(releasePolicy.deploymentCreationEnabled()).thenReturn(true);

    ManagedAiProperties managedAi = new ManagedAiProperties();
    managedAi.setEnabled(true);
    ManagedAiProperties.TierPolicy policy = new ManagedAiProperties.TierPolicy();
    policy.setIncludedCredits(0);
    managedAi.setTiers(Map.of("ENTERPRISE", policy, "FREE", policy));

    svc =
        new TierPermissionService(
            userRepo,
            orgRepo,
            orgMemberRepo,
            tierConfigRepo,
            tierEnforcementService,
            managedAi,
            releasePolicy);

    TierConfig cfg = new TierConfig();
    cfg.setTier("FREE");
    cfg.setMonthlyRunLimit(10);
    cfg.setCanDeploy(false);
    cfg.setMaxDeployments(0);
    cfg.setMaxApplySuggestions(2);
    cfg.setMaxPreviewStarts(2);
    when(tierConfigRepo.findById("FREE")).thenReturn(Optional.of(cfg));
  }

  private AppUser userPointingAtTeamOrg() {
    AppUser u = new AppUser();
    u.setId("u-1");
    u.setGlobalRole("USER");
    u.setTier("FREE");
    u.setOrgId("org-1");
    u.setPersonalRunCount(3);
    when(userRepo.findById("u-1")).thenReturn(Optional.of(u));
    when(tierEnforcementService.effectiveUserLimit(u)).thenReturn(10);

    Org org = new Org();
    org.setId("org-1");
    org.setTier("ENTERPRISE");
    org.setPersonal(false);
    org.setMonthlyRunCount(42);
    when(orgRepo.findById("org-1")).thenReturn(Optional.of(org));
    when(tierEnforcementService.effectiveOrgLimit(org)).thenReturn(-1);
    return u;
  }

  @Test
  void orphanedMembershipDoesNotReceiveOrgDerivedCapabilities() {
    userPointingAtTeamOrg();
    when(orgMemberRepo.findByOrgIdAndUserId("org-1", "u-1")).thenReturn(Optional.empty());

    TierPermissionService.TierPermissions p = svc.getPermissions("u-1");

    assertThat(p.monthlyRunLimit())
        .as("quota comes from the user, not the org they only point at")
        .isEqualTo(10);
    assertThat(p.currentRunCount())
        .as("the org's consumption is not attributed to a non-member")
        .isEqualTo(3);
    assertThat(p.canViewOrgMembers())
        .as("no org-derived member/admin surface without a membership row")
        .isFalse();
    assertThat(p.canInviteMembers()).isFalse();
    assertThat(p.canViewAudit()).isFalse();
  }

  @Test
  void activeDeveloperMembershipStillReceivesOrgCapabilities() {
    userPointingAtTeamOrg();
    when(orgMemberRepo.findByOrgIdAndUserId("org-1", "u-1"))
        .thenReturn(
            Optional.of(
                OrgMember.builder().orgId("org-1").userId("u-1").orgRole("DEVELOPER").build()));
    when(orgMemberRepo.countByOrgId("org-1")).thenReturn(3L);

    TierPermissionService.TierPermissions p = svc.getPermissions("u-1");

    assertThat(p.monthlyRunLimit()).as("real members are scoped by the org limit").isEqualTo(-1);
    assertThat(p.currentRunCount()).isEqualTo(42);
    assertThat(p.canViewLiveLog()).isTrue();
  }
}

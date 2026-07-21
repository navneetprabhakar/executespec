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
 * RR-211 — {@code BILLING_ADMIN} is a canonical org role, but {@code orgMemberPermissions} only
 * recognised {@code ORG_ADMIN}, so the dedicated billing role fell into the DEVELOPER branch with
 * every finance flag false and could not perform its core duty.
 */
class TierPermissionServiceBillingAdminTest {

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
    managedAi.setTiers(Map.of("ENTERPRISE", policy));

    svc =
        new TierPermissionService(
            userRepo,
            orgRepo,
            orgMemberRepo,
            tierConfigRepo,
            tierEnforcementService,
            managedAi,
            releasePolicy);
  }

  private AppUser memberOfRealOrg(String orgRole) {
    AppUser u = new AppUser();
    u.setId("u-1");
    u.setGlobalRole("USER");
    u.setTier("ENTERPRISE");
    u.setOrgId("org-1");
    when(userRepo.findById("u-1")).thenReturn(Optional.of(u));

    Org org = new Org();
    org.setId("org-1");
    org.setTier("ENTERPRISE");
    org.setPersonal(false);
    when(orgRepo.findById("org-1")).thenReturn(Optional.of(org));
    when(tierEnforcementService.effectiveOrgLimit(org)).thenReturn(-1);
    when(orgMemberRepo.countByOrgId("org-1")).thenReturn(3L);
    when(orgMemberRepo.findByOrgIdAndUserId("org-1", "u-1"))
        .thenReturn(
            Optional.of(OrgMember.builder().orgId("org-1").userId("u-1").orgRole(orgRole).build()));

    TierConfig cfg = new TierConfig();
    cfg.setTier("ENTERPRISE");
    when(tierConfigRepo.findById("ENTERPRISE")).thenReturn(Optional.of(cfg));
    return u;
  }

  @Test
  void billingAdminReceivesBillingCapabilities() {
    memberOfRealOrg("BILLING_ADMIN");

    TierPermissionService.TierPermissions p = svc.getPermissions("u-1");

    assertThat(p.canViewInvoices()).as("the whole point of the role").isTrue();
    assertThat(p.canViewAccounting()).isTrue();
    assertThat(p.canManageCreditNotes()).isTrue();
    assertThat(p.canExportGstr()).isTrue();
    assertThat(p.canManageBillingProfile()).isTrue();
    // Not an org admin: org/engineering surfaces stay locked.
    assertThat(p.canInviteMembers()).isFalse();
    assertThat(p.canManageAiKeys()).isFalse();
  }

  @Test
  void plainDeveloperStillHasNoBillingCapabilities() {
    memberOfRealOrg("DEVELOPER");

    TierPermissionService.TierPermissions p = svc.getPermissions("u-1");

    assertThat(p.canViewInvoices()).isFalse();
    assertThat(p.canManageBillingProfile()).isFalse();
    assertThat(p.canManageCreditNotes()).isFalse();
  }
}

package com.navneet.executespec.entitlements;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.router.ModelRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Read side of T26 — proves the governance policy reflects only what is actually enforced. */
class GovernancePolicyResolverTest {

  private AppUserRepository userRepo;
  private OrgRepository orgRepo;
  private EntitlementResolver entitlementResolver;
  private ModelRegistry modelRegistry;
  private GovernancePolicyResolver resolver;

  @BeforeEach
  void setUp() {
    userRepo = mock(AppUserRepository.class);
    orgRepo = mock(OrgRepository.class);
    entitlementResolver = mock(EntitlementResolver.class);
    modelRegistry = mock(ModelRegistry.class);
    resolver = new GovernancePolicyResolver(userRepo, orgRepo, entitlementResolver, modelRegistry);
  }

  @Test
  void free_tier_is_model_locked_with_provider_and_models() {
    AppUser u = AppUser.builder().id("u1").email("u@e").firstName("F").tier("FREE").build();
    when(userRepo.findById("u1")).thenReturn(Optional.of(u));
    when(entitlementResolver.resolveTier(u)).thenReturn("FREE");
    when(modelRegistry.freeTierProvider()).thenReturn("deepseek");
    when(modelRegistry.freeTierModels()).thenReturn(List.of("deepseek-v4-flash-native"));

    GovernancePolicyResponse r = resolver.resolve("u1");

    assertEquals("FREE", r.tier());
    assertTrue(r.modelPolicy().freeTierLocked());
    assertEquals("deepseek", r.modelPolicy().freeTierProvider());
    assertEquals(List.of("deepseek-v4-flash-native"), r.modelPolicy().freeModels());
    assertTrue(r.modelPolicy().note().contains("deepseek"));
    // Tool gating does not exist yet — surface that honestly, never fabricate a policy.
    assertFalse(r.toolPolicy().enforced());
    assertNull(r.costApprovalThresholdUsd());
    assertTrue(r.allowedGithubOwners().isEmpty());
    assertFalse(r.superAdminBypass());
  }

  @Test
  void paid_tier_is_unlocked_and_surfaces_org_governance() {
    AppUser u =
        AppUser.builder().id("u2").email("a@e").firstName("A").tier("TEAM").orgId("org1").build();
    when(userRepo.findById("u2")).thenReturn(Optional.of(u));
    when(entitlementResolver.resolveTier(u)).thenReturn("TEAM");
    Org org = new Org();
    org.setId("org1");
    org.setRequireAdminApprovalAboveUsd(new BigDecimal("25.00"));
    org.setAllowedGithubOwners("acme, acme-labs ,");
    when(orgRepo.findById("org1")).thenReturn(Optional.of(org));

    GovernancePolicyResponse r = resolver.resolve("u2");

    assertEquals("TEAM", r.tier());
    assertFalse(r.modelPolicy().freeTierLocked());
    assertNull(r.modelPolicy().freeTierProvider());
    assertEquals(Double.valueOf(25.0), r.costApprovalThresholdUsd());
    // CSV parse trims and drops the trailing empty token.
    assertEquals(List.of("acme", "acme-labs"), r.allowedGithubOwners());
    // Paid tiers never consult the model registry (no free-tier lock to describe).
    verifyNoInteractions(modelRegistry);
  }

  @Test
  void super_admin_bypass_is_surfaced() {
    AppUser u =
        AppUser.builder()
            .id("u3")
            .email("s@e")
            .firstName("S")
            .tier("PRO")
            .globalRole("SUPER_ADMIN")
            .build();
    when(userRepo.findById("u3")).thenReturn(Optional.of(u));
    when(entitlementResolver.resolveTier(u)).thenReturn("PRO");

    GovernancePolicyResponse r = resolver.resolve("u3");
    assertTrue(r.superAdminBypass());
  }
}

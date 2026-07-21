package com.navneet.executespec.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ManagedAiTierResolverTest {

  private AppUserRepository userRepository;
  private OrgRepository orgRepository;
  private ManagedAiTierResolver resolver;

  @BeforeEach
  void setUp() {
    userRepository = mock(AppUserRepository.class);
    orgRepository = mock(OrgRepository.class);
    resolver = new ManagedAiTierResolver(userRepository, orgRepository);
  }

  @Test
  void missingUserDefaultsToFree() {
    assertThat(resolver.resolveUserTier("missing")).isEqualTo("FREE");
  }

  @Test
  void superAdminResolvesToEnterprise() {
    when(userRepository.findById("admin"))
        .thenReturn(Optional.of(AppUser.builder().id("admin").globalRole("SUPER_ADMIN").build()));

    assertThat(resolver.resolveUserTier("admin")).isEqualTo("ENTERPRISE");
  }

  @Test
  void orgTierCanLiftUserTier() {
    when(userRepository.findById("u1"))
        .thenReturn(Optional.of(AppUser.builder().id("u1").tier("PRO").orgId("o1").build()));
    when(orgRepository.findById("o1"))
        .thenReturn(Optional.of(Org.builder().id("o1").tier("TEAM").build()));

    assertThat(resolver.resolveUserTier("u1")).isEqualTo("TEAM");
  }

  @Test
  void maxTierNormalizesToTeam() {
    when(userRepository.findById("u1"))
        .thenReturn(Optional.of(AppUser.builder().id("u1").tier("MAX").build()));

    assertThat(resolver.resolveUserTier("u1")).isEqualTo("TEAM");
  }

  /**
   * RR-226 (2026-07-20) — inverted deliberately. An external host project's tier must NOT raise the
   * user's managed-AI billing tier: TEAM/ENTERPRISE policies carry zero managed caps and are
   * treated as unmanaged, so the old behaviour gave a FREE collaborator uncapped platform-key spend
   * in their OWN org and projects for as long as they held one external membership. Host-project
   * feature entitlement still flows through EntitlementResolver/TierEnforcementService.
   */
  @Test
  void externalHostTierDoesNotLiftManagedAiBillingTier() {
    when(userRepository.findById("u1"))
        .thenReturn(Optional.of(AppUser.builder().id("u1").tier("FREE").build()));

    assertThat(resolver.resolveUserTier("u1")).isEqualTo("FREE");
  }
}

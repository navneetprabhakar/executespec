package com.navneet.executespec.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ManagedAiTierResolverTest {

  private AppUserRepository userRepository;
  private OrgRepository orgRepository;
  private ProjectMemberRepository projectMemberRepository;
  private ManagedAiTierResolver resolver;

  @BeforeEach
  void setUp() {
    userRepository = mock(AppUserRepository.class);
    orgRepository = mock(OrgRepository.class);
    projectMemberRepository = mock(ProjectMemberRepository.class);
    resolver = new ManagedAiTierResolver(userRepository, orgRepository, projectMemberRepository);
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

  @Test
  void externalHostTierCanLiftEffectiveTier() {
    when(userRepository.findById("u1"))
        .thenReturn(Optional.of(AppUser.builder().id("u1").tier("FREE").build()));
    when(projectMemberRepository.findActiveExternalHostOrgTiersByUserId("u1"))
        .thenReturn(List.of("PRO", "ENTERPRISE"));

    assertThat(resolver.resolveUserTier("u1")).isEqualTo("ENTERPRISE");
  }
}

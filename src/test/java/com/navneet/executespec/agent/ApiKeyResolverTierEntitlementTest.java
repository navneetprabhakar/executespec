package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgRepository;

import org.junit.jupiter.api.Test;

class ApiKeyResolverTierEntitlementTest {

  private final AppUserRepository users = mock(AppUserRepository.class);
  private final OrgRepository orgs = mock(OrgRepository.class);
  private final ApiKeyResolver resolver = new ApiKeyResolver(users, orgs);

  @Test
  void freeDowngradeForcesSystemResolutionDespiteRetainedOwnKeysState() {
    AppUser user =
        AppUser.builder().id("user-1").orgId("org-1").tier("FREE").openAiApiKey("user-key").build();
    Org org =
        Org.builder()
            .id("org-1")
            .tier("FREE")
            .keySourceMode("OWN_KEYS")
            .openAiApiKey("org-key")
            .build();
    when(users.findById("user-1")).thenReturn(Optional.of(user));
    when(orgs.findById("org-1")).thenReturn(Optional.of(org));

    ResolvedKeys keys = resolver.resolve("user-1");

    assertThat(keys.keySourceMode()).isEqualTo("SYSTEM");
    assertThat(keys.openAiKey()).isNull();
    assertThat(resolver.isOwnKeysMode("user-1")).isFalse();
  }

  @Test
  void byokTierStillResolvesCustomerKeys() {
    AppUser user =
        AppUser.builder().id("user-1").orgId("org-1").tier("BYOK").openAiApiKey("user-key").build();
    Org org = Org.builder().id("org-1").tier("BYOK").keySourceMode("OWN_KEYS").build();
    when(users.findById("user-1")).thenReturn(Optional.of(user));
    when(orgs.findById("org-1")).thenReturn(Optional.of(org));

    ResolvedKeys keys = resolver.resolve("user-1");

    assertThat(keys.keySourceMode()).isEqualTo("OWN_KEYS");
    assertThat(keys.openAiKey()).isEqualTo("user-key");
    assertThat(resolver.isOwnKeysMode("user-1")).isTrue();
  }

  @Test
  void managedUserWithoutOrgCannotUseRetainedPersonalKey() {
    AppUser user = AppUser.builder().id("user-1").tier("FREE").openAiApiKey("user-key").build();
    when(users.findById("user-1")).thenReturn(Optional.of(user));

    ResolvedKeys keys = resolver.resolve("user-1");

    assertThat(keys.keySourceMode()).isEqualTo("SYSTEM");
    assertThat(keys.openAiKey()).isNull();
  }
}

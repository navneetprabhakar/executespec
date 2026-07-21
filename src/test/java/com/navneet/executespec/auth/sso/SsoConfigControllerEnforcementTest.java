package com.navneet.executespec.auth.sso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.security.AppUserDetails;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * RR-252 — "SSO enforced" was a database flag with no enforcement behind it.
 *
 * <p>Repository-wide, `ssoEnforced` / `ssoProvider` / `ssoDomain` are read by this controller and
 * nothing else: login, JWT/OAuth mapping and request authorization never consult them. Returning
 * success for `enforced=true` therefore sold an Enterprise admin a security control that does not
 * exist — password login, refresh, PAT and every route stayed fully usable.
 */
class SsoConfigControllerEnforcementTest {

  private OrgRepository orgRepo;
  private SsoConfigController controller;
  private Org org;

  @BeforeEach
  void setUp() {
    orgRepo = mock(OrgRepository.class);
    controller = new SsoConfigController(orgRepo);
    org = new Org();
    org.setId("org-1");
    when(orgRepo.findById("org-1")).thenReturn(Optional.of(org));
  }

  private AppUserDetails admin() {
    AppUser u = new AppUser();
    u.setId("admin-1");
    u.setOrgId("org-1");
    u.setEmail("admin@acme.test");
    u.setGlobalRole("ORG_ADMIN");
    u.setOauthProvider("GOOGLE");
    return new AppUserDetails(u, null);
  }

  @Test
  void enforcementCannotBeTurnedOn() {
    assertThatThrownBy(
            () ->
                controller.putConfig(
                    admin(),
                    new SsoConfigController.SsoConfigUpdate("GOOGLE_OIDC", true, null, null)))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.NOT_IMPLEMENTED);

    verify(orgRepo, never()).save(any(Org.class));
  }

  @Test
  void configurationWithoutEnforcementStillSaves() {
    SsoConfigController.SsoConfigDto dto =
        controller.putConfig(
            admin(),
            new SsoConfigController.SsoConfigUpdate(
                "GOOGLE_OIDC", null, "https://idp.example/metadata", "acme.test"));

    verify(orgRepo).save(org);
    assertThat(dto.provider()).isEqualTo("GOOGLE_OIDC");
    assertThat(dto.configured()).isTrue();
  }

  @Test
  void theApiTellsClientsEnforcementIsNotSupported() {
    org.setSsoProvider("GOOGLE_OIDC");
    org.setSsoEnforced(true); // a historical row that predates this gate

    SsoConfigController.SsoConfigDto dto = controller.getConfig(admin());

    assertThat(dto.enforcementSupported())
        .as("clients must not present stored intent as an active security control")
        .isFalse();
  }
}

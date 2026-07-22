package com.navneet.executespec.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.navneet.executespec.auth.entity.AppUser;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * RR-1191 — GLOBAL analytics scope requires explicit SUPER_ADMIN authority; a tenant caller must
 * have a non-null org, and a null-org ordinary account is refused rather than silently widened to
 * cross-tenant data.
 */
class RunAccessGuardAnalyticsScopeTest {

  private final RunAccessGuard guard =
      new RunAccessGuard(mock(com.navneet.executespec.state.StateStore.class), null);

  private static AppUserDetails principal(String globalRole, String orgId) {
    AppUser user =
        AppUser.builder()
            .id("user-1")
            .email("u@example.com")
            .orgId(orgId)
            .globalRole(globalRole)
            .status("ACTIVE")
            .build();
    return new AppUserDetails(user, null);
  }

  @Test
  void superAdminResolvesToGlobalScope() {
    var scope = guard.resolveAnalyticsScope(principal("SUPER_ADMIN", null));
    assertThat(scope.global()).isTrue();
    assertThat(scope.orgId()).isNull();
  }

  @Test
  void ordinaryUserResolvesToTheirOrg() {
    var scope = guard.resolveAnalyticsScope(principal("USER", "org-a"));
    assertThat(scope.global()).isFalse();
    assertThat(scope.orgId()).isEqualTo("org-a");
  }

  @Test
  void nullOrgOrdinaryUserIsRefusedNotWidenedToGlobal() {
    assertThatThrownBy(() -> guard.resolveAnalyticsScope(principal("USER", null)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("403");
  }

  @Test
  void blankOrgOrdinaryUserIsRefused() {
    assertThatThrownBy(() -> guard.resolveAnalyticsScope(principal("USER", "  ")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("403");
  }
}

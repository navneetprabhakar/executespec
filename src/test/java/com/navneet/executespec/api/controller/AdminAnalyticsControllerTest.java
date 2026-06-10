package com.navneet.executespec.api.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.OrgMember;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.billing.service.AnalyticsService;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AdminAnalyticsControllerTest {

  private final AnalyticsService analyticsService = mock(AnalyticsService.class);
  private final AdminAnalyticsController controller =
      new AdminAnalyticsController(analyticsService, mock(RunAccessGuard.class));

  @Test
  void usageNormalizesLowercaseOrgEntityType() {
    AppUserDetails principal = principal("user-1", "org-a", "USER", "ORG_ADMIN");

    controller.usage("org", "org-a", 30, principal);

    verify(analyticsService).getUsageMetrics("ORG", "org-a", 30);
  }

  @Test
  void orgAdminCannotReadArbitraryUserAnalytics() {
    AppUserDetails principal = principal("user-1", "org-a", "USER", "ORG_ADMIN");

    assertThatThrownBy(() -> controller.usage("USER", "user-2", 30, principal))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("403");
  }

  @Test
  void invalidEntityTypeFailsBeforeServiceCall() {
    AppUserDetails principal = principal("user-1", "org-a", "USER", "ORG_ADMIN");

    assertThatThrownBy(() -> controller.usage("workspace", "org-a", 30, principal))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("400");
  }

  private static AppUserDetails principal(
      String userId, String orgId, String globalRole, String orgRole) {
    AppUser user =
        AppUser.builder()
            .id(userId)
            .email(userId + "@example.com")
            .firstName("Test")
            .orgId(orgId)
            .globalRole(globalRole)
            .status("ACTIVE")
            .build();
    OrgMember member =
        OrgMember.builder()
            .id("member-" + userId)
            .userId(userId)
            .orgId(orgId)
            .orgRole(orgRole)
            .build();
    return new AppUserDetails(user, member);
  }
}

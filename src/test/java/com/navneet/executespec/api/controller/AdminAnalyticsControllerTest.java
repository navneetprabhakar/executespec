package com.navneet.executespec.api.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.OrgMember;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.billing.service.AnalyticsService;
import com.navneet.executespec.cost.LlmUsageAuditQuery;
import com.navneet.executespec.cost.LlmUsageAuditService;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ResponseStatusException;

class AdminAnalyticsControllerTest {

  private final AnalyticsService analyticsService = mock(AnalyticsService.class);
  private final LlmUsageAuditService llmUsageAuditService = mock(LlmUsageAuditService.class);
  private final AdminAnalyticsController controller =
      new AdminAnalyticsController(
          analyticsService, llmUsageAuditService, mock(RunAccessGuard.class));

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

  @Test
  void orgAdminCanReadCanonicalLlmUsageForOwnOrg() {
    AppUserDetails principal = principal("user-1", "org-a", "USER", "ORG_ADMIN");

    controller.llmUsageAudit(
        "org-a",
        LocalDate.of(2026, 7, 1),
        LocalDate.of(2026, 7, 31),
        "Asia/Kolkata",
        0,
        100,
        principal);

    verify(llmUsageAuditService).report(any(LlmUsageAuditQuery.class));
  }

  @Test
  void orgAdminCannotReadAnotherOrgsLlmUsage() {
    AppUserDetails principal = principal("user-1", "org-a", "USER", "ORG_ADMIN");

    assertThatThrownBy(
            () ->
                controller.llmUsageAudit(
                    "org-b",
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 31),
                    "UTC",
                    0,
                    100,
                    principal))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("403");
  }

  @Test
  void billingAdminCannotReadPhysicalAttemptFacts() {
    AppUserDetails principal = principal("user-1", "org-a", "USER", "BILLING_ADMIN");

    assertThatThrownBy(
            () ->
                controller.llmUsageAudit(
                    "org-a",
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 31),
                    "UTC",
                    0,
                    100,
                    principal))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("403");
  }

  @Test
  void developerCannotReadPhysicalAttemptFacts() {
    AppUserDetails principal = principal("user-1", "org-a", "USER", "DEVELOPER");

    assertThatThrownBy(
            () ->
                controller.llmUsageAudit(
                    "org-a",
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 31),
                    "UTC",
                    0,
                    100,
                    principal))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("403");
  }

  @Test
  void superAdminCanDownloadAnyOrgJsonWithNoStoreHeaders() {
    AppUserDetails principal = principal("root", "operator-org", "SUPER_ADMIN", "DEVELOPER");

    var response =
        controller.llmUsageAuditJson(
            "customer-org",
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 31),
            "Asia/Kolkata",
            0,
            500,
            principal);

    verify(llmUsageAuditService).report(any(LlmUsageAuditQuery.class));
    org.assertj.core.api.Assertions.assertThat(
            response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
        .contains("llm-usage-customer-org-2026-07-01-to-2026-07-31.json");
    org.assertj.core.api.Assertions.assertThat(response.getHeaders().getFirst("Cache-Control"))
        .isEqualTo("no-store");
  }

  @Test
  void invalidTimezoneIsBadRequest() {
    AppUserDetails principal = principal("user-1", "org-a", "USER", "ORG_ADMIN");

    assertThatThrownBy(
            () ->
                controller.llmUsageAudit(
                    "org-a",
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 31),
                    "Mars/Olympus",
                    0,
                    100,
                    principal))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("400");
  }

  @Test
  void csvIsBoundedAndAdvertisesTruncation() {
    AppUserDetails principal = principal("user-1", "org-a", "USER", "ORG_ADMIN");
    when(llmUsageAuditService.csv(any()))
        .thenReturn(
            new LlmUsageAuditService.CsvExport(
                new byte[] {1, 2}, LlmUsageAuditQuery.CSV_ROW_LIMIT, true, "usage.csv"));

    var response =
        controller.llmUsageAuditCsv(
            "org-a", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), "UTC", principal);

    org.assertj.core.api.Assertions.assertThat(response.getHeaders().getFirst("X-Export-Truncated"))
        .isEqualTo("true");
    org.assertj.core.api.Assertions.assertThat(response.getHeaders().getFirst("X-Export-Row-Limit"))
        .isEqualTo("10000");
    org.assertj.core.api.Assertions.assertThat(response.getHeaders().getFirst("Cache-Control"))
        .isEqualTo("no-store");
    org.assertj.core.api.Assertions.assertThat(
            response.getHeaders().getFirst("X-Content-Type-Options"))
        .isEqualTo("nosniff");
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

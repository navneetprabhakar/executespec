package com.navneet.executespec.billing.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.service.PaymentAuditService;
import com.navneet.executespec.billing.repository.WebhookEventLogRepository;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PaymentAuditControllerTest {

  private final PaymentAuditService auditService = mock(PaymentAuditService.class);
  private final PaymentAuditController controller =
      new PaymentAuditController(auditService, mock(WebhookEventLogRepository.class));

  @Test
  void paymentHistoryRejectsDifferentOrgForOrgAdmin() {
    AppUserDetails principal = principal("user-1", "org-a", "USER");

    assertThatThrownBy(() -> controller.paymentHistory("org-b", 0, 50, principal))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("403");

    verify(auditService, never())
        .findByOrg(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void paymentHistoryAllowsOwnOrg() {
    AppUserDetails principal = principal("user-1", "org-a", "USER");

    controller.paymentHistory("org-a", 0, 50, principal);

    verify(auditService)
        .findByOrg(org.mockito.ArgumentMatchers.eq("org-a"), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void paymentHistoryAllowsSuperAdminForAnyOrg() {
    AppUserDetails principal = principal("admin-1", "admin-org", "SUPER_ADMIN");

    controller.paymentHistory("org-b", 0, 50, principal);

    verify(auditService)
        .findByOrg(org.mockito.ArgumentMatchers.eq("org-b"), org.mockito.ArgumentMatchers.any());
  }

  private static AppUserDetails principal(String userId, String orgId, String globalRole) {
    AppUser user =
        AppUser.builder()
            .id(userId)
            .email(userId + "@example.com")
            .firstName("Test")
            .orgId(orgId)
            .globalRole(globalRole)
            .status("ACTIVE")
            .build();
    return new AppUserDetails(user, null);
  }
}

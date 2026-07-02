package com.navneet.executespec.billing.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.OrgMember;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.billing.dto.TopupOrderInitResult;
import com.navneet.executespec.billing.dto.TopupPurchaseRequest;
import com.navneet.executespec.billing.dto.TopupVerifyRequest;
import com.navneet.executespec.billing.service.BillingService;
import com.navneet.executespec.billing.service.FounderPricingService;
import com.navneet.executespec.billing.service.RunTopupService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class BillingControllerTest {

  @Mock private BillingService billingService;
  @Mock private RunTopupService runTopupService;
  @Mock private FounderPricingService founderPricingService;

  private BillingController controller;

  @BeforeEach
  void setUp() {
    controller = new BillingController(billingService, runTopupService, founderPricingService);
  }

  @Test
  void orgDeveloperCannotPurchaseTopupsForOrgBillingEntity() {
    AppUserDetails developer = userDetails("u-dev", "org-1", "DEVELOPER");

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () ->
                controller.purchaseTopup(
                    new TopupPurchaseRequest(null, 500L, null, null, null), developer));

    assertEquals(403, ex.getStatusCode().value());
    verifyNoInteractions(runTopupService);
  }

  @Test
  void orgBillingAdminCanPurchaseTopups() {
    AppUserDetails billingAdmin = userDetails("u-bill", "org-1", "BILLING_ADMIN");
    when(runTopupService.initiatePurchase(eq("u-bill"), any(TopupPurchaseRequest.class)))
        .thenReturn(new TopupOrderInitResult("order-1", "cust-1", "key", 50000L));

    var response =
        controller.purchaseTopup(
            new TopupPurchaseRequest(null, 500L, null, null, null), billingAdmin);

    assertNotNull(response.getBody());
    assertEquals("order-1", response.getBody().orderId());
  }

  @Test
  void soloUserCanPurchasePersonalTopups() {
    AppUserDetails solo = userDetails("u-solo", null, null);
    when(runTopupService.initiatePurchase(eq("u-solo"), any(TopupPurchaseRequest.class)))
        .thenReturn(new TopupOrderInitResult("order-1", "cust-1", "key", 50000L));

    var response =
        controller.purchaseTopup(new TopupPurchaseRequest(null, 500L, null, null, null), solo);

    assertNotNull(response.getBody());
    assertEquals("order-1", response.getBody().orderId());
  }

  @Test
  void orgDeveloperCannotVerifyTopups() {
    AppUserDetails developer = userDetails("u-dev", "org-1", "DEVELOPER");

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> controller.verifyTopup(new TopupVerifyRequest("o", "p", "s"), developer));

    assertEquals(403, ex.getStatusCode().value());
    verifyNoInteractions(runTopupService, billingService);
  }

  private static AppUserDetails userDetails(String userId, String orgId, String orgRole) {
    AppUser user =
        AppUser.builder()
            .id(userId)
            .email(userId + "@example.com")
            .firstName("Test")
            .globalRole("USER")
            .tier(orgId == null ? "FREE" : "TEAM")
            .status("ACTIVE")
            .orgId(orgId)
            .build();
    OrgMember orgMember =
        orgId == null
            ? null
            : OrgMember.builder()
                .id("om-" + userId)
                .orgId(orgId)
                .userId(userId)
                .orgRole(orgRole)
                .build();
    return new AppUserDetails(user, orgMember);
  }
}

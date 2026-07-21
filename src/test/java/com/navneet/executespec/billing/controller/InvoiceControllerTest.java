package com.navneet.executespec.billing.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.service.ActivityAuditService;
import com.navneet.executespec.auth.service.TierPermissionService;
import com.navneet.executespec.billing.entity.Invoice;
import com.navneet.executespec.billing.repository.BillingProfileRepository;
import com.navneet.executespec.billing.repository.SellerConfigRepository;
import com.navneet.executespec.billing.service.InvoiceEmailService;
import com.navneet.executespec.billing.service.InvoicePdfService;
import com.navneet.executespec.billing.service.InvoiceService;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class InvoiceControllerTest {

  private final InvoiceService invoiceService = mock(InvoiceService.class);
  private final InvoiceEmailService emailService = mock(InvoiceEmailService.class);
  private final TierPermissionService permissionService = mock(TierPermissionService.class);
  private final AppUserRepository userRepo = mock(AppUserRepository.class);
  private final BillingProfileRepository profileRepo = mock(BillingProfileRepository.class);
  private final InvoiceController controller =
      new InvoiceController(
          invoiceService,
          mock(InvoicePdfService.class),
          emailService,
          profileRepo,
          mock(SellerConfigRepository.class),
          permissionService,
          mock(ActivityAuditService.class),
          userRepo);

  @Test
  void resendInvoiceSendsToInvoiceOwnerNotCallingAdmin() {
    UUID invoiceId = UUID.randomUUID();
    Invoice invoice = Invoice.builder().id(invoiceId).userId("owner-1").build();
    AppUser admin =
        AppUser.builder()
            .id("admin-1")
            .email("admin@example.com")
            .firstName("Admin")
            .globalRole("FINANCE")
            .status("ACTIVE")
            .build();
    AppUser owner =
        AppUser.builder()
            .id("owner-1")
            .email("owner@example.com")
            .firstName("Owner")
            .status("ACTIVE")
            .build();

    when(permissionService.getPermissions("admin-1")).thenReturn(financePermissions());
    when(invoiceService.getInvoice(invoiceId)).thenReturn(Optional.of(invoice));
    when(userRepo.findById("owner-1")).thenReturn(Optional.of(owner));

    controller.resendInvoice(new AppUserDetails(admin, null), invoiceId);

    verify(emailService).sendInvoice("owner@example.com", "Owner", invoice);
  }

  @Test
  void issueCreditNoteRejectsNegativeAmount() {
    UUID invoiceId = UUID.randomUUID();
    AppUser admin = financeAdmin();
    when(permissionService.getPermissions("admin-1")).thenReturn(creditNotePermissions());

    assertThatThrownBy(
            () ->
                controller.issueCreditNote(
                    new AppUserDetails(admin, null),
                    invoiceId,
                    new InvoiceController.CreditNoteRequest(-1, "bad")))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);

    verify(invoiceService, never()).generateCreditNote(invoiceId, -1, "bad");
  }

  @Test
  void issueCreditNoteRejectsAmountGreaterThanInvoiceTotal() {
    UUID invoiceId = UUID.randomUUID();
    Invoice invoice = Invoice.builder().id(invoiceId).userId("owner-1").totalAmount(1000).build();
    AppUser admin = financeAdmin();
    when(permissionService.getPermissions("admin-1")).thenReturn(creditNotePermissions());
    when(invoiceService.getInvoice(invoiceId)).thenReturn(Optional.of(invoice));

    assertThatThrownBy(
            () ->
                controller.issueCreditNote(
                    new AppUserDetails(admin, null),
                    invoiceId,
                    new InvoiceController.CreditNoteRequest(1001, "too much")))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);

    verify(invoiceService, never()).generateCreditNote(invoiceId, 1001, "too much");
  }

  // ---- RR-183: billing profile + invoice reads require a billing capability ----

  private AppUser orgMember() {
    return AppUser.builder()
        .id("dev-1")
        .email("dev@example.com")
        .firstName("Dev")
        .globalRole("USER")
        .orgId("org-1")
        .status("ACTIVE")
        .build();
  }

  /** Permission fixture with all flags false except the two named billing capabilities. */
  private TierPermissionService.TierPermissions billingPerms(
      boolean canViewInvoices, boolean canManageBillingProfile) {
    return new TierPermissionService.TierPermissions(
        false, // canManageAiKeys
        false, // canViewPricing
        false, // canManageIntegrations
        false, // canViewMetrics
        false, // canViewCostBreakdown
        false, // canViewTokenUsage
        false, // canUseChat
        false, // canCreateOrg
        false, // canInviteMembers
        false, // canViewOrgMembers
        false, // canViewAudit
        false, // canViewLiveLog
        canViewInvoices, // canViewInvoices
        false, // canViewAccounting
        false, // canManageCreditNotes
        false, // canExportGstr
        false, // canManageSellerConfig
        canManageBillingProfile, // canManageBillingProfile
        0,
        0,
        0,
        0,
        0,
        0,
        false,
        0,
        "NONE",
        0,
        false,
        "fixture");
  }

  @Test
  void getProfileDeniedForOrgMemberWithoutBillingCapability() {
    AppUser dev = orgMember();
    when(permissionService.getPermissions("dev-1")).thenReturn(billingPerms(false, false));

    assertThatThrownBy(() -> controller.getProfile(new AppUserDetails(dev, null)))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);

    verify(profileRepo, never()).findByOrgId("org-1");
  }

  @Test
  void updateProfileDeniedWithoutManageCapabilityEvenWithViewCapability() {
    AppUser dev = orgMember();
    // Can view (read) but not manage (write): the PUT must still be refused.
    when(permissionService.getPermissions("dev-1")).thenReturn(billingPerms(true, false));

    assertThatThrownBy(
            () ->
                controller.updateProfile(
                    new AppUserDetails(dev, null),
                    com.navneet.executespec.billing.entity.BillingProfile.builder()
                        .gstin("HACKED")
                        .build()))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);

    verify(profileRepo, never()).save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void myInvoicesDeniedForOrgMemberWithoutBillingCapability() {
    AppUser dev = orgMember();
    when(permissionService.getPermissions("dev-1")).thenReturn(billingPerms(false, false));

    assertThatThrownBy(
            () ->
                controller.myInvoices(
                    new AppUserDetails(dev, null),
                    org.springframework.data.domain.Pageable.unpaged()))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);

    verify(invoiceService, never())
        .listByOrg(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void billingReaderCanReadProfile() {
    AppUser dev = orgMember();
    when(permissionService.getPermissions("dev-1")).thenReturn(billingPerms(true, false));
    when(profileRepo.findByOrgId("org-1")).thenReturn(Optional.empty());

    // No exception — a billing-capable member reads the org profile (new default profile returned).
    controller.getProfile(new AppUserDetails(dev, null));

    verify(profileRepo).findByOrgId("org-1");
  }

  private AppUser financeAdmin() {
    return AppUser.builder()
        .id("admin-1")
        .email("admin@example.com")
        .firstName("Admin")
        .globalRole("FINANCE")
        .status("ACTIVE")
        .build();
  }

  private TierPermissionService.TierPermissions financePermissions() {
    return new TierPermissionService.TierPermissions(
        false,
        true,
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        true,
        true,
        false,
        false,
        false,
        false,
        0,
        0,
        0,
        0,
        0,
        0,
        false,
        0,
        "NONE",
        0,
        false,
        "Finance fixture has no run quota.");
  }

  private TierPermissionService.TierPermissions creditNotePermissions() {
    return new TierPermissionService.TierPermissions(
        false,
        true,
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        true,
        true,
        true,
        false,
        false,
        false,
        0,
        0,
        0,
        0,
        0,
        0,
        false,
        0,
        "NONE",
        0,
        false,
        "Finance fixture has no run quota.");
  }
}

package com.navneet.executespec.billing.controller;

import java.util.List;
import java.util.UUID;

import com.navneet.executespec.auth.entity.ActivityAudit;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.service.ActivityAuditService;
import com.navneet.executespec.auth.service.TierPermissionService;
import com.navneet.executespec.billing.entity.BillingProfile;
import com.navneet.executespec.billing.entity.Invoice;
import com.navneet.executespec.billing.entity.InvoiceLineItem;
import com.navneet.executespec.billing.entity.SellerConfig;
import com.navneet.executespec.billing.repository.BillingProfileRepository;
import com.navneet.executespec.billing.repository.SellerConfigRepository;
import com.navneet.executespec.billing.service.InvoiceEmailService;
import com.navneet.executespec.billing.service.InvoicePdfService;
import com.navneet.executespec.billing.service.InvoiceService;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class InvoiceController {

  private final InvoiceService invoiceService;
  private final InvoicePdfService pdfService;
  private final InvoiceEmailService emailService;
  private final BillingProfileRepository profileRepo;
  private final SellerConfigRepository sellerRepo;
  private final TierPermissionService permissionService;
  private final ActivityAuditService auditService;

  // ── Own billing profile ──────────────────────────────────────────────────

  @GetMapping("/api/billing/profile")
  public BillingProfile getProfile(@AuthenticationPrincipal AppUserDetails principal) {
    String userId = principal.getUser().getId();
    String orgId = principal.getUser().getOrgId();

    if (orgId != null) {
      return profileRepo.findByOrgId(orgId).orElse(newProfile(userId, orgId));
    }
    return profileRepo.findByUserId(userId).orElse(newProfile(userId, null));
  }

  @PutMapping("/api/billing/profile")
  public BillingProfile updateProfile(
      @AuthenticationPrincipal AppUserDetails principal, @RequestBody BillingProfile body) {
    String userId = principal.getUser().getId();
    String orgId = principal.getUser().getOrgId();

    BillingProfile existing;
    if (orgId != null) {
      existing = profileRepo.findByOrgId(orgId).orElse(newProfile(userId, orgId));
    } else {
      existing = profileRepo.findByUserId(userId).orElse(newProfile(userId, null));
    }

    existing.setLegalName(body.getLegalName());
    existing.setGstin(body.getGstin());
    existing.setPan(body.getPan());
    existing.setAddressLine1(body.getAddressLine1());
    existing.setAddressLine2(body.getAddressLine2());
    existing.setCity(body.getCity());
    existing.setStateName(body.getStateName());
    existing.setStateCode(body.getStateCode());
    existing.setPostalCode(body.getPostalCode());
    existing.setCountry(body.getCountry());
    existing.setPreferredCurrency(body.getPreferredCurrency());

    var saved = profileRepo.save(existing);

    auditService.record(
        ActivityAudit.builder()
            .eventType("BILLING_PROFILE_UPDATED")
            .entityType("BILLING")
            .entityId(saved.getId().toString())
            .actorId(userId)
            .build());

    return saved;
  }

  // ── Own invoices ─────────────────────────────────────────────────────────

  @GetMapping("/api/billing/invoices")
  public Page<Invoice> myInvoices(
      @AuthenticationPrincipal AppUserDetails principal, Pageable pageable) {
    String userId = principal.getUser().getId();
    String orgId = principal.getUser().getOrgId();
    if (orgId != null) {
      return invoiceService.listByOrg(orgId, pageable);
    }
    return invoiceService.listByUser(userId, pageable);
  }

  @GetMapping("/api/billing/invoices/{id}")
  public InvoiceDetail myInvoiceDetail(
      @AuthenticationPrincipal AppUserDetails principal, @PathVariable UUID id) {
    Invoice invoice =
        invoiceService
            .getInvoice(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    requireOwnership(principal, invoice);
    List<InvoiceLineItem> items = invoiceService.getLineItems(id);
    return new InvoiceDetail(invoice, items);
  }

  @GetMapping("/api/billing/invoices/{id}/pdf")
  public ResponseEntity<?> downloadPdf(
      @AuthenticationPrincipal AppUserDetails principal, @PathVariable UUID id) {
    Invoice invoice =
        invoiceService
            .getInvoice(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    requireOwnership(principal, invoice);

    // For Stripe invoices with a gateway PDF URL, redirect to Stripe-hosted PDF
    if ("stripe".equalsIgnoreCase(invoice.getGateway())
        && invoice.getGatewayPdfUrl() != null
        && !invoice.getGatewayPdfUrl().isBlank()) {
      return ResponseEntity.status(HttpStatus.FOUND)
          .header(HttpHeaders.LOCATION, invoice.getGatewayPdfUrl())
          .build();
    }

    // For Razorpay and other gateways, generate PDF server-side
    return pdfResponse(invoice);
  }

  // ── Admin endpoints ──────────────────────────────────────────────────────

  @GetMapping("/api/admin/invoices")
  public Page<Invoice> allInvoices(
      @AuthenticationPrincipal AppUserDetails principal, Pageable pageable) {
    requireInvoiceAdmin(principal);
    return invoiceService.listAll(pageable);
  }

  @GetMapping("/api/admin/invoices/{id}/pdf")
  public ResponseEntity<byte[]> adminDownloadPdf(
      @AuthenticationPrincipal AppUserDetails principal, @PathVariable UUID id) {
    requireInvoiceAdmin(principal);
    Invoice invoice =
        invoiceService
            .getInvoice(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    return pdfResponse(invoice);
  }

  @PostMapping("/api/admin/invoices/{id}/credit-note")
  public Invoice issueCreditNote(
      @AuthenticationPrincipal AppUserDetails principal,
      @PathVariable UUID id,
      @RequestBody CreditNoteRequest req) {
    requireCreditNoteAccess(principal);
    Invoice cn = invoiceService.generateCreditNote(id, req.amountPaise(), req.reason());

    auditService.record(
        ActivityAudit.builder()
            .eventType("CREDIT_NOTE_ISSUED")
            .entityType("BILLING")
            .entityId(cn.getId().toString())
            .actorId(principal.getUser().getId())
            .newValue("{\"originalInvoiceId\":\"" + id + "\",\"amount\":" + req.amountPaise() + "}")
            .build());

    return cn;
  }

  @PostMapping("/api/admin/invoices/{id}/void")
  public Invoice voidInvoice(
      @AuthenticationPrincipal AppUserDetails principal, @PathVariable UUID id) {
    requireSuperAdmin(principal);
    Invoice invoice = invoiceService.voidInvoice(id);

    auditService.record(
        ActivityAudit.builder()
            .eventType("INVOICE_VOIDED")
            .entityType("BILLING")
            .entityId(id.toString())
            .actorId(principal.getUser().getId())
            .build());

    return invoice;
  }

  @PostMapping("/api/admin/invoices/{id}/send")
  public void resendInvoice(
      @AuthenticationPrincipal AppUserDetails principal, @PathVariable UUID id) {
    requireInvoiceAdmin(principal);
    Invoice invoice =
        invoiceService
            .getInvoice(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    emailService.sendInvoice(
        principal.getUser().getEmail(), principal.getUser().getFirstName(), invoice);

    auditService.record(
        ActivityAudit.builder()
            .eventType("INVOICE_SENT")
            .entityType("BILLING")
            .entityId(id.toString())
            .actorId(principal.getUser().getId())
            .build());
  }

  // ── Seller config ────────────────────────────────────────────────────────

  @GetMapping("/api/admin/seller-config")
  public SellerConfig getSellerConfig(@AuthenticationPrincipal AppUserDetails principal) {
    requireSuperAdmin(principal);
    return sellerRepo.getDefault();
  }

  @PutMapping("/api/admin/seller-config")
  public SellerConfig updateSellerConfig(
      @AuthenticationPrincipal AppUserDetails principal, @RequestBody SellerConfig body) {
    requireSuperAdmin(principal);
    SellerConfig existing = sellerRepo.getDefault();
    existing.setGstin(body.getGstin());
    existing.setLegalName(body.getLegalName());
    existing.setAddressLine1(body.getAddressLine1());
    existing.setAddressLine2(body.getAddressLine2());
    existing.setCity(body.getCity());
    existing.setStateName(body.getStateName());
    existing.setStateCode(body.getStateCode());
    existing.setPostalCode(body.getPostalCode());
    existing.setPan(body.getPan());

    var saved = sellerRepo.save(existing);

    auditService.record(
        ActivityAudit.builder()
            .eventType("SELLER_CONFIG_UPDATED")
            .entityType("BILLING")
            .entityId("DEFAULT")
            .actorId(principal.getUser().getId())
            .build());

    return saved;
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private ResponseEntity<byte[]> pdfResponse(Invoice invoice) {
    byte[] pdf = pdfService.generatePdf(invoice);
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + invoice.getInvoiceNumber() + ".pdf\"")
        .contentType(MediaType.APPLICATION_PDF)
        .body(pdf);
  }

  private void requireOwnership(AppUserDetails principal, Invoice invoice) {
    String userId = principal.getUser().getId();
    String globalRole = principal.getUser().getGlobalRole();
    if ("SUPER_ADMIN".equals(globalRole) || "FINANCE".equals(globalRole)) return;

    String orgId = principal.getUser().getOrgId();
    boolean owns =
        userId.equals(invoice.getUserId()) || (orgId != null && orgId.equals(invoice.getOrgId()));
    if (!owns) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your invoice");
    }
  }

  private void requireInvoiceAdmin(AppUserDetails principal) {
    var perms = permissionService.getPermissions(principal.getUser().getId());
    if (!perms.canViewInvoices()
        || (!perms.canViewAccounting()
            && !"SUPER_ADMIN".equals(principal.getUser().getGlobalRole()))) {
      // Allow if canViewInvoices + either FINANCE or SUPER_ADMIN
      String role = principal.getUser().getGlobalRole();
      if (!"SUPER_ADMIN".equals(role) && !"FINANCE".equals(role)) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invoice admin access required");
      }
    }
  }

  private void requireCreditNoteAccess(AppUserDetails principal) {
    var perms = permissionService.getPermissions(principal.getUser().getId());
    if (!perms.canManageCreditNotes()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Credit note access required");
    }
  }

  private void requireSuperAdmin(AppUserDetails principal) {
    if (!"SUPER_ADMIN".equals(principal.getUser().getGlobalRole())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Super admin access required");
    }
  }

  private BillingProfile newProfile(String userId, String orgId) {
    return BillingProfile.builder()
        .userId(userId)
        .orgId(orgId)
        .country("IN")
        .preferredCurrency("INR")
        .build();
  }

  // ── DTOs ─────────────────────────────────────────────────────────────────

  public record InvoiceDetail(Invoice invoice, List<InvoiceLineItem> lineItems) {}

  public record CreditNoteRequest(long amountPaise, String reason) {}
}

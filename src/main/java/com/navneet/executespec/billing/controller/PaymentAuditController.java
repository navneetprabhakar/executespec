package com.navneet.executespec.billing.controller;

import com.navneet.executespec.auth.entity.PaymentAudit;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.service.PaymentAuditService;
import com.navneet.executespec.billing.entity.WebhookEventLog;
import com.navneet.executespec.billing.repository.WebhookEventLogRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class PaymentAuditController {

  private final PaymentAuditService auditService;
  private final WebhookEventLogRepository webhookRepo;

  /** List payment audit entries (SUPER_ADMIN only). */
  @GetMapping("/api/admin/payment-audit")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  public ResponseEntity<Page<PaymentAudit>> listAudit(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @RequestParam(required = false) String orgId,
      @RequestParam(required = false) String userId) {
    PageRequest pr = PageRequest.of(page, Math.min(size, 200));
    Page<PaymentAudit> result;
    if (orgId != null) {
      result = auditService.findByOrg(orgId, pr);
    } else if (userId != null) {
      result = auditService.findByUser(userId, pr);
    } else {
      result = auditService.findAll(pr);
    }
    return ResponseEntity.ok(result);
  }

  /** Export payment audit as CSV (SUPER_ADMIN only). */
  @GetMapping("/api/admin/payment-audit/export")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  public ResponseEntity<byte[]> exportAuditCsv() {
    var all = auditService.findAll(PageRequest.of(0, 10_000));
    String csv = auditService.exportCsv(all.getContent());
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=payment-audit.csv")
        .contentType(MediaType.parseMediaType("text/csv"))
        .body(csv.getBytes());
  }

  /** List webhook events (SUPER_ADMIN only). */
  @GetMapping("/api/admin/webhook-events")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  public ResponseEntity<Page<WebhookEventLog>> listWebhookEvents(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @RequestParam(required = false) String gateway) {
    PageRequest pr = PageRequest.of(page, Math.min(size, 200));
    Page<WebhookEventLog> result;
    if (gateway != null) {
      result = webhookRepo.findByGatewayOrderByCreatedAtDesc(gateway, pr);
    } else {
      result = webhookRepo.findAllByOrderByCreatedAtDesc(pr);
    }
    return ResponseEntity.ok(result);
  }

  /** Payment history for ORG_ADMIN / BILLING_ADMIN (scoped to their org). */
  @GetMapping("/api/billing/payment-history")
  @PreAuthorize("hasAnyRole('ORG_ADMIN', 'BILLING_ADMIN', 'SUPER_ADMIN')")
  public ResponseEntity<Page<PaymentAudit>> paymentHistory(
      @RequestParam String orgId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @AuthenticationPrincipal AppUserDetails principal) {
    assertCanReadOrgHistory(principal, orgId);
    PageRequest pr = PageRequest.of(page, Math.min(size, 200));
    return ResponseEntity.ok(auditService.findByOrg(orgId, pr));
  }

  private static void assertCanReadOrgHistory(AppUserDetails principal, String orgId) {
    if (principal == null || principal.getUser() == null) {
      throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED);
    }
    if ("SUPER_ADMIN".equals(principal.getUser().getGlobalRole())) {
      return;
    }
    String callerOrgId = principal.getUser().getOrgId();
    if (callerOrgId == null || !callerOrgId.equals(orgId)) {
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.FORBIDDEN, "Cannot read another org's payments");
    }
  }
}

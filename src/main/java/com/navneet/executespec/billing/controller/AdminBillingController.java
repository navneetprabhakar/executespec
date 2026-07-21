package com.navneet.executespec.billing.controller;

import java.util.List;

import com.navneet.executespec.billing.entity.BillingGatewayObligation;
import com.navneet.executespec.billing.service.BillingGatewayObligationService;
import com.navneet.executespec.billing.service.PlanSeederService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/admin/billing")
@RequiredArgsConstructor
public class AdminBillingController {

  private final PlanSeederService planSeederService;
  private final BillingGatewayObligationService obligationService;

  /**
   * RR-400: report the real outcome. Seeding creates chargeable Razorpay plans, so a partial sweep
   * must not be reported as "seeded successfully" — an operator who believes that will retry and
   * duplicate the plans that did succeed.
   */
  @PostMapping("/seed-plans")
  public ResponseEntity<PlanSeederService.SeedResult> seedPlans() {
    PlanSeederService.SeedResult result = planSeederService.seedAllPlans();
    if (result.failed() > 0) {
      return ResponseEntity.status(HttpStatus.MULTI_STATUS).body(result);
    }
    return ResponseEntity.ok(result);
  }

  /**
   * RR-049 / RR-352 / RR-1681 — the visibility half of the obligation drainer. Unresolved gateway
   * obligations (failed/interrupted refunds and cancellations, plus dead-lettered and operator-only
   * ones) rolled up by kind, so a stuck obligation is visible to an operator rather than silent.
   * SUPER_ADMIN only.
   */
  @GetMapping("/gateway-obligations")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  public BillingGatewayObligationService.UnresolvedSummary gatewayObligations() {
    return obligationService.unresolvedSummary();
  }

  /**
   * The individual unresolved obligation rows, optionally filtered by {@code kind}, for an operator
   * drilling into what the summary reports. SUPER_ADMIN only.
   */
  @GetMapping("/gateway-obligations/list")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  public List<BillingGatewayObligation> gatewayObligationList(
      @RequestParam(name = "kind", required = false) String kind) {
    return (kind == null || kind.isBlank())
        ? obligationService.unresolved()
        : obligationService.unresolvedByKind(kind);
  }
}

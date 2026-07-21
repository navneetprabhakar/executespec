package com.navneet.executespec.billing.controller;

import com.navneet.executespec.billing.service.PlanSeederService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/admin/billing")
@RequiredArgsConstructor
public class AdminBillingController {

  private final PlanSeederService planSeederService;

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
}

package com.navneet.executespec.billing.controller;

import java.util.List;
import java.util.Map;

import com.navneet.executespec.billing.service.BillingNotificationService;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class BillingNotificationAdminController {

  private static final List<String> ROUTING_EMAIL_STATES =
      List.of("FRONTIER_LOCKED", "CHEAP_ONLY", "EXHAUSTED");

  private final BillingNotificationService billingNotificationService;

  /**
   * Operator smoke for deployed Gmail OAuth validation. Dispatches through the same billing
   * notification path used by managed-AI routing transitions without creating usage ledger or
   * dedupe rows.
   */
  @PostMapping("/test-managed-ai-email")
  public ResponseEntity<Map<String, String>> sendManagedAiTestEmail(
      @RequestBody Map<String, String> body) {
    String to = body.get("to");
    if (to == null || to.isBlank()) {
      return ResponseEntity.badRequest().body(Map.of("error", "Field 'to' is required"));
    }
    String state = body.getOrDefault("routingState", "CHEAP_ONLY");
    if (!ROUTING_EMAIL_STATES.contains(state)) {
      return ResponseEntity.badRequest()
          .body(Map.of("error", "routingState must be FRONTIER_LOCKED, CHEAP_ONLY, or EXHAUSTED"));
    }
    billingNotificationService.sendManagedAiRoutingStateChanged(
        to,
        state,
        "Managed AI routing email smoke. This validates the billing notification Gmail path.");
    return ResponseEntity.ok(
        Map.of("message", "Managed AI test email dispatched to " + to, "routingState", state));
  }
}

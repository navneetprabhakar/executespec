package com.navneet.executespec.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.navneet.executespec.billing.entity.WebhookEventLog;
import com.navneet.executespec.billing.gateway.PaymentGateway;
import com.navneet.executespec.billing.gateway.PaymentGatewayRouter;
import com.navneet.executespec.billing.gateway.WebhookResult;
import com.navneet.executespec.billing.repository.WebhookEventLogRepository;
import com.navneet.executespec.billing.service.BillingService;

import org.junit.jupiter.api.Test;

/**
 * RR-052 (2026-07-22, P1) — the audit status must reflect the real business-processing outcome, not
 * be stamped PROCESSED before {@code processStripeWebhook} runs.
 */
class BillingWebhookControllerTest {

  private final BillingService billingService = mock(BillingService.class);
  private final PaymentGatewayRouter gatewayRouter = mock(PaymentGatewayRouter.class);
  private final WebhookEventLogRepository repo = mock(WebhookEventLogRepository.class);
  private final PaymentGateway stripe = mock(PaymentGateway.class);

  private final BillingWebhookController controller =
      new BillingWebhookController(billingService, gatewayRouter, repo);

  @Test
  void successfulProcessingRecordsProcessingThenProcessed() {
    List<String> statuses = arrangeStripe();

    controller.handleStripeWebhook("{}", "sig");

    // First persisted status is PROCESSING (before business handling), then PROCESSED after
    // success.
    assertThat(statuses).containsExactly("PROCESSING", "PROCESSED");
  }

  @Test
  void failedProcessingRecordsProcessingThenFailed_notProcessed() {
    List<String> statuses = arrangeStripe();
    doThrow(new RuntimeException("boom"))
        .when(billingService)
        .processStripeWebhook(anyString(), anyString(), anyString());

    // Controller still returns 200 (prevents Stripe retries) but the audit row must say FAILED.
    controller.handleStripeWebhook("{}", "sig");

    assertThat(statuses).containsExactly("PROCESSING", "FAILED");
    assertThat(statuses).doesNotContain("PROCESSED");
  }

  private List<String> arrangeStripe() {
    when(gatewayRouter.isStripeEnabled()).thenReturn(true);
    when(gatewayRouter.stripe()).thenReturn(stripe);
    when(stripe.handleWebhook(anyString(), anyString()))
        .thenReturn(new WebhookResult(true, "invoice.paid", "Event validated: evt_1"));
    when(repo.findByGatewayAndEventId(anyString(), anyString())).thenReturn(Optional.empty());

    // Capture every persisted status in call order.
    List<String> statuses = new ArrayList<>();
    when(repo.save(any(WebhookEventLog.class)))
        .thenAnswer(
            inv -> {
              WebhookEventLog e = inv.getArgument(0);
              statuses.add(e.getProcessingStatus());
              return e;
            });
    return statuses;
  }
}

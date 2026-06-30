package com.navneet.executespec.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.auth.service.MailService;
import com.navneet.executespec.auth.service.PaymentAuditService;
import com.navneet.executespec.auth.service.TierEnforcementService;
import com.navneet.executespec.billing.dto.VerifyPaymentRequest;
import com.navneet.executespec.billing.entity.BillingPlan;
import com.navneet.executespec.billing.entity.BillingSubscription;
import com.navneet.executespec.billing.entity.RunTopup;
import com.navneet.executespec.billing.exception.BillingException;
import com.navneet.executespec.billing.gateway.BillingEntity;
import com.navneet.executespec.billing.gateway.BillingEntityResolver;
import com.navneet.executespec.billing.gateway.BillingGateway;
import com.navneet.executespec.billing.gateway.CheckoutResult;
import com.navneet.executespec.billing.gateway.PaymentGateway;
import com.navneet.executespec.billing.gateway.PaymentGatewayRouter;
import com.navneet.executespec.billing.repository.BillingPlanLimitRepository;
import com.navneet.executespec.billing.repository.BillingPlanRepository;
import com.navneet.executespec.billing.repository.BillingSubscriptionRepository;
import com.navneet.executespec.billing.repository.BillingWebhookEventRepository;
import com.navneet.executespec.billing.repository.RunTopupRepository;
import com.navneet.executespec.billing.security.WebhookSignatureVerifier;

import org.junit.jupiter.api.Test;

class BillingServiceStripeTest {

  private final BillingGateway razorpayGateway = mock(BillingGateway.class);
  private final BillingPlanRepository planRepo = mock(BillingPlanRepository.class);
  private final BillingSubscriptionRepository subRepo = mock(BillingSubscriptionRepository.class);
  private final BillingWebhookEventRepository webhookRepo =
      mock(BillingWebhookEventRepository.class);
  private final AppUserRepository userRepo = mock(AppUserRepository.class);
  private final PaymentGatewayRouter gatewayRouter = mock(PaymentGatewayRouter.class);
  private final BillingEntityResolver billingEntityResolver = mock(BillingEntityResolver.class);
  private final PaymentAuditService paymentAuditService = mock(PaymentAuditService.class);
  private final BillingNotificationService notificationService =
      mock(BillingNotificationService.class);
  private final UsageTrackingService usageService = mock(UsageTrackingService.class);
  private final WebhookSignatureVerifier signatureVerifier = mock(WebhookSignatureVerifier.class);
  private final RunTopupRepository runTopupRepo = mock(RunTopupRepository.class);
  private final InvoiceService invoiceService = mock(InvoiceService.class);
  private final TierConfigRepository tierConfigRepository = mock(TierConfigRepository.class);
  private final BillingService service =
      new BillingService(
          razorpayGateway,
          planRepo,
          mock(BillingPlanLimitRepository.class),
          subRepo,
          usageService,
          signatureVerifier,
          new ObjectMapper(),
          userRepo,
          mock(OrgRepository.class),
          notificationService,
          runTopupRepo,
          invoiceService,
          mock(InvoiceEmailService.class),
          mock(MailService.class),
          gatewayRouter,
          billingEntityResolver,
          paymentAuditService,
          mock(TierEnforcementService.class),
          new BillingSubscriptionPolicy(tierConfigRepository),
          mock(BillingSpendService.class),
          mock(BillingStatusUsageService.class),
          new BillingPeriodService(),
          new BillingTierBridgeService(
              userRepo, mock(OrgRepository.class), planRepo, subRepo, notificationService),
          new BillingSubscriptionWriter(subRepo),
          new BillingWebhookEventRecorder(webhookRepo),
          mock(RunTopupService.class));

  @Test
  void stripeCheckoutCompletedRewritesCheckoutSessionIdToSubscriptionId() {
    BillingSubscription sub =
        BillingSubscription.builder()
            .id(UUID.randomUUID())
            .userId("user-1")
            .orgId("org-1")
            .billingPlanId(UUID.randomUUID())
            .paymentGateway("STRIPE")
            .gatewaySubscriptionId("cs_test_123")
            .status("CREATED")
            .totalSeats(1)
            .extraSeats(0)
            .build();
    AppUser user =
        AppUser.builder()
            .id("user-1")
            .email("user@example.com")
            .firstName("Test")
            .status("ACTIVE")
            .build();
    when(webhookRepo.existsByGatewayAndEventId("STRIPE", "evt_1")).thenReturn(false);
    when(subRepo.findByGatewaySubscriptionId("sub_test_123")).thenReturn(Optional.empty());
    when(subRepo.findByGatewaySubscriptionId("cs_test_123")).thenReturn(Optional.of(sub));
    when(userRepo.findById("user-1")).thenReturn(Optional.of(user));

    service.processStripeWebhook(
        """
        {
          "id": "evt_1",
          "data": {
            "object": {
              "id": "cs_test_123",
              "subscription": "sub_test_123",
              "customer": "cus_test_123",
              "metadata": {
                "billing_entity_type": "USER",
                "billing_entity_id": "user-1",
                "plan_id": "plan-1"
              }
            }
          }
        }
        """,
        "sig",
        "checkout.session.completed");

    assertThat(sub.getGatewaySubscriptionId()).isEqualTo("sub_test_123");
    assertThat(sub.getGatewayCustomerId()).isEqualTo("cus_test_123");
    assertThat(sub.getStatus()).isEqualTo("ACTIVE");
    assertThat(sub.getTotalSeats()).isEqualTo(1);
    verify(subRepo).save(sub);
  }

  @Test
  void subscribeUpgradeDoesNotCancelExistingSubscriptionBeforeCheckoutIsPaid() {
    UUID oldPlanId = UUID.randomUUID();
    UUID newPlanId = UUID.randomUUID();
    BillingSubscription existing =
        BillingSubscription.builder()
            .id(UUID.randomUUID())
            .userId("user-1")
            .orgId("org-1")
            .billingPlanId(oldPlanId)
            .paymentGateway("STRIPE")
            .gatewaySubscriptionId("sub_old")
            .status("ACTIVE")
            .totalSeats(1)
            .extraSeats(0)
            .build();
    BillingPlan oldPlan =
        BillingPlan.builder()
            .id(oldPlanId)
            .name("Pro")
            .tier("PRO")
            .lifecycleState("LISTED")
            .build();
    BillingPlan newPlan =
        BillingPlan.builder()
            .id(newPlanId)
            .name("Team")
            .tier("TEAM")
            .period("MONTHLY")
            .lifecycleState("LISTED")
            .active(true)
            .includedSeats(1)
            .build();
    AppUser user =
        AppUser.builder()
            .id("user-1")
            .orgId("org-1")
            .email("user@example.com")
            .firstName("Test")
            .status("ACTIVE")
            .build();
    BillingEntity billingEntity = mock(BillingEntity.class);
    PaymentGateway stripeGateway = mock(PaymentGateway.class);

    when(userRepo.findById("user-1")).thenReturn(Optional.of(user));
    when(planRepo.findById(newPlanId)).thenReturn(Optional.of(newPlan));
    when(planRepo.findById(oldPlanId)).thenReturn(Optional.of(oldPlan));
    when(subRepo.findActiveByUserId("user-1")).thenReturn(Optional.of(existing));
    when(billingEntityResolver.resolve(user)).thenReturn(billingEntity);
    when(gatewayRouter.resolveByName("stripe")).thenReturn(stripeGateway);
    when(stripeGateway.gatewayType()).thenReturn("STRIPE");
    when(stripeGateway.createCheckoutSession(
            billingEntity, newPlanId.toString(), 2, "/ok", "/cancel"))
        .thenReturn(new CheckoutResult("https://checkout.example/session", "cs_upgrade"));

    service.subscribe("user-1", newPlanId, "stripe", "/ok", "/cancel", 2);

    verify(stripeGateway, never()).cancelSubscription("sub_old");
    verify(razorpayGateway, never()).cancelImmediately("sub_old");
    assertThat(existing.getStatus()).isEqualTo("ACTIVE");
  }

  @Test
  void verifyAndActivateUpgradeRetiresExistingSubscriptionAfterPaymentSucceeds() {
    UUID oldPlanId = UUID.randomUUID();
    UUID newPlanId = UUID.randomUUID();
    BillingSubscription existing =
        BillingSubscription.builder()
            .id(UUID.randomUUID())
            .userId("user-1")
            .orgId("org-1")
            .billingPlanId(oldPlanId)
            .paymentGateway("STRIPE")
            .gatewaySubscriptionId("sub_old")
            .status("ACTIVE")
            .totalSeats(1)
            .extraSeats(0)
            .build();
    BillingSubscription activating =
        BillingSubscription.builder()
            .id(UUID.randomUUID())
            .userId("user-1")
            .orgId("org-1")
            .billingPlanId(newPlanId)
            .paymentGateway("RAZORPAY")
            .gatewaySubscriptionId("rzp_new")
            .status("CREATED")
            .totalSeats(1)
            .extraSeats(0)
            .build();
    BillingPlan oldPlan =
        BillingPlan.builder()
            .id(oldPlanId)
            .name("Pro")
            .tier("PRO")
            .runLimit(20)
            .lifecycleState("LISTED")
            .build();
    BillingPlan newPlan =
        BillingPlan.builder()
            .id(newPlanId)
            .name("Team")
            .tier("TEAM")
            .period("MONTHLY")
            .lifecycleState("LISTED")
            .active(true)
            .includedSeats(1)
            .build();
    AppUser user =
        AppUser.builder()
            .id("user-1")
            .orgId("org-1")
            .email("user@example.com")
            .firstName("Test")
            .status("ACTIVE")
            .build();
    PaymentGateway stripeGateway = mock(PaymentGateway.class);
    VerifyPaymentRequest request = new VerifyPaymentRequest("rzp_new", "pay_1", "sig_1");

    when(razorpayGateway.verifyPayment(request)).thenReturn(true);
    when(subRepo.findByGatewaySubscriptionId("rzp_new")).thenReturn(Optional.of(activating));
    when(planRepo.findById(newPlanId)).thenReturn(Optional.of(newPlan));
    when(planRepo.findById(oldPlanId)).thenReturn(Optional.of(oldPlan));
    when(userRepo.findById("user-1")).thenReturn(Optional.of(user));
    when(subRepo.findActiveByUserId("user-1")).thenReturn(Optional.of(existing));
    when(gatewayRouter.stripe()).thenReturn(stripeGateway);
    when(usageService.getCurrentUsage("user-1")).thenReturn(List.of());
    when(invoiceService.findLatestBySubscriptionId(existing.getId())).thenReturn(Optional.empty());

    service.verifyAndActivate("user-1", request);

    verify(stripeGateway).cancelSubscription("sub_old");
    assertThat(existing.getStatus()).isEqualTo("UPGRADED");
    assertThat(activating.getStatus()).isEqualTo("ACTIVE");
    verify(subRepo).save(existing);
    verify(subRepo).save(activating);
  }

  @Test
  void updateSeatsFailsClosedInsteadOfMutatingLocalSubscriptionOnly() {
    assertThatThrownBy(() -> service.updateSeats("user-1", 5))
        .isInstanceOf(BillingException.class)
        .hasMessageContaining("payment gateway and local subscription stay in sync");

    verifyNoInteractions(subRepo);
  }

  @Test
  void razorpayTopupWebhookSkipsSideEffectsWhenAtomicActivationLosesRace() {
    RunTopup pending =
        RunTopup.builder()
            .id(UUID.randomUUID())
            .userId("user-1")
            .quantity(10)
            .remaining(0)
            .status("PENDING")
            .gatewayOrderId("order_1")
            .build();
    String payload =
        """
        {
          "event": "payment.captured",
          "created_at": 1,
          "payload": {
            "order": {"entity": {"id": "order_1"}},
            "payment": {"entity": {"id": "pay_1", "order_id": "order_1"}}
          }
        }
        """;

    when(signatureVerifier.verifyWebhookSignature(payload, "sig")).thenReturn(true);
    when(webhookRepo.existsByGatewayAndEventId("RAZORPAY", "payment.captured_order_1_1"))
        .thenReturn(false);
    when(runTopupRepo.findByGatewayOrderId("order_1")).thenReturn(Optional.of(pending));
    when(runTopupRepo.activatePendingPurchase(eq("order_1"), eq("pay_1"), any(Instant.class)))
        .thenReturn(0);

    service.processRazorpayWebhook(payload, "sig");

    verify(runTopupRepo).activatePendingPurchase(eq("order_1"), eq("pay_1"), any(Instant.class));
    verify(invoiceService, never()).generateTopupInvoice(any(), any(), any());
    verify(notificationService, never()).sendTopupCredited(any(), any(Integer.class));
  }

  @Test
  void stripeCancelUsesPeriodEndCancellation() {
    UUID planId = UUID.randomUUID();
    BillingSubscription sub =
        BillingSubscription.builder()
            .id(UUID.randomUUID())
            .userId("user-1")
            .billingPlanId(planId)
            .paymentGateway("STRIPE")
            .gatewaySubscriptionId("sub_test_123")
            .status("ACTIVE")
            .currentPeriodEnd(Instant.parse("2026-07-10T00:00:00Z"))
            .totalSeats(1)
            .extraSeats(0)
            .build();
    BillingPlan plan = BillingPlan.builder().id(planId).name("Team").tier("TEAM").build();
    AppUser user =
        AppUser.builder()
            .id("user-1")
            .email("user@example.com")
            .firstName("Test")
            .status("ACTIVE")
            .build();
    PaymentGateway stripeGateway = mock(PaymentGateway.class);
    when(subRepo.findActiveByUserId("user-1")).thenReturn(Optional.of(sub));
    when(gatewayRouter.stripe()).thenReturn(stripeGateway);
    when(planRepo.findById(planId)).thenReturn(Optional.of(plan));
    when(userRepo.findById("user-1")).thenReturn(Optional.of(user));

    service.cancel("user-1");

    verify(stripeGateway).cancelAtPeriodEnd("sub_test_123");
    verify(stripeGateway, never()).cancelSubscription("sub_test_123");
    assertThat(sub.isCancelAtPeriodEnd()).isTrue();
    verify(subRepo).save(sub);
  }
}

package com.navneet.executespec.billing.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.navneet.executespec.billing.entity.BillingGatewayObligation;
import com.navneet.executespec.billing.gateway.PaymentGateway;
import com.navneet.executespec.billing.gateway.PaymentGatewayRouter;
import com.navneet.executespec.billing.gateway.RazorpayGateway;
import com.navneet.executespec.billing.service.BillingGatewayObligationService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * RR-049 / RR-352 / RR-1681 — the drainer must retry unresolved gateway obligations idempotently
 * (never a double refund or double cancel), defer rows that are too fresh or whose gateway is
 * unavailable, and mark operator-only outcomes UNSUPPORTED rather than looping forever.
 */
class BillingObligationDrainerTest {

  private final BillingGatewayObligationService obligations =
      Mockito.mock(BillingGatewayObligationService.class);
  private final PaymentGatewayRouter gatewayRouter = Mockito.mock(PaymentGatewayRouter.class);
  private final RazorpayGateway razorpayGateway = Mockito.mock(RazorpayGateway.class);

  private BillingObligationDrainer drainer;

  @BeforeEach
  void setUp() {
    drainer = new BillingObligationDrainer(obligations, gatewayRouter, razorpayGateway);
    ReflectionTestUtils.setField(drainer, "enabled", true);
    when(gatewayRouter.isRazorpayEnabled()).thenReturn(true);
  }

  private BillingGatewayObligation.BillingGatewayObligationBuilder base(String kind) {
    // Old enough to clear the per-row backoff (attempts=1 → ~20 min grace).
    return BillingGatewayObligation.builder()
        .id(UUID.randomUUID())
        .kind(kind)
        .status(BillingGatewayObligation.STATUS_FAILED)
        .attempts(1)
        .createdAt(Instant.now().minus(2, ChronoUnit.DAYS))
        .updatedAt(Instant.now().minus(2, ChronoUnit.DAYS));
  }

  @Test
  void disabledDrainerDoesNothing() {
    ReflectionTestUtils.setField(drainer, "enabled", false);
    drainer.drain();
    verifyNoInteractions(obligations);
  }

  @Test
  void cancellationAlreadyCancelledAtGatewayIsRecordedWithoutASecondCancel() {
    BillingGatewayObligation o =
        base(BillingGatewayObligation.KIND_CANCEL_OLD_SUBSCRIPTION)
            .gateway("RAZORPAY")
            .gatewayRef("sub_1")
            .build();
    when(obligations.retryable()).thenReturn(List.of(o));
    PaymentGateway gw = razorpayLikeGateway();
    when(gatewayRouter.resolveByName("RAZORPAY")).thenReturn(gw);
    when(gw.subscriptionStatus("sub_1")).thenReturn("cancelled");

    drainer.drain();

    verify(gw, never()).cancelSubscription(anyString());
    verify(gw, never()).cancelAtPeriodEnd(anyString());
    verify(obligations).markSucceeded(o.getId(), "sub_1");
  }

  @Test
  void cancellationRetryCancelsImmediatelyAndRecordsSuccess() {
    BillingGatewayObligation o =
        base(BillingGatewayObligation.KIND_CANCEL_OLD_SUBSCRIPTION)
            .gateway("RAZORPAY")
            .gatewayRef("sub_2")
            .build();
    when(obligations.retryable()).thenReturn(List.of(o));
    PaymentGateway gw = razorpayLikeGateway();
    when(gatewayRouter.resolveByName("RAZORPAY")).thenReturn(gw);
    when(gw.subscriptionStatus("sub_2")).thenReturn("active");

    drainer.drain();

    verify(gw).cancelSubscription("sub_2");
    verify(gw, never()).cancelAtPeriodEnd(anyString());
    verify(obligations).markSucceeded(o.getId(), "sub_2");
  }

  @Test
  void tierSunsetRetryUsesCancelAtPeriodEnd() {
    BillingGatewayObligation o =
        base(BillingGatewayObligation.KIND_TIER_SUNSET_CANCEL)
            .gateway("STRIPE")
            .gatewayRef("sub_3")
            .build();
    when(obligations.retryable()).thenReturn(List.of(o));
    PaymentGateway gw = Mockito.mock(PaymentGateway.class);
    when(gw.gatewayType()).thenReturn("STRIPE");
    when(gatewayRouter.resolveByName("STRIPE")).thenReturn(gw);
    when(gw.subscriptionStatus("sub_3")).thenReturn("active");

    drainer.drain();

    verify(gw).cancelAtPeriodEnd("sub_3");
    verify(gw, never()).cancelSubscription(anyString());
    verify(obligations).markSucceeded(o.getId(), "sub_3");
  }

  @Test
  void cancellationDefersWhenTheObligationsGatewayIsNotEnabled() {
    BillingGatewayObligation o =
        base(BillingGatewayObligation.KIND_CANCEL_OLD_SUBSCRIPTION)
            .gateway("RAZORPAY")
            .gatewayRef("sub_4")
            .build();
    when(obligations.retryable()).thenReturn(List.of(o));
    // Router fell back to a different (enabled) gateway — acting would hit the wrong provider.
    PaymentGateway wrong = Mockito.mock(PaymentGateway.class);
    when(wrong.gatewayType()).thenReturn("STRIPE");
    when(gatewayRouter.resolveByName("RAZORPAY")).thenReturn(wrong);

    drainer.drain();

    verify(wrong, never()).cancelSubscription(anyString());
    verify(obligations, never()).markSucceeded(any(), anyString());
    verify(obligations, never()).markFailed(any(), anyString());
    verify(obligations, never()).markUnsupported(any(), anyString());
  }

  @Test
  void refundWithAnExistingGatewayRefundIsRecordedWithoutRefundingAgain() {
    BillingGatewayObligation o =
        base(BillingGatewayObligation.KIND_PRORATED_REFUND)
            .gateway("RAZORPAY")
            .gatewayRef("pay_1")
            .amountMinor(500L)
            .build();
    when(obligations.retryable()).thenReturn(List.of(o));
    when(razorpayGateway.findExistingRefundId("pay_1", 500L)).thenReturn("rfnd_existing");

    drainer.drain();

    verify(razorpayGateway, never())
        .refundPartial(anyString(), anyLong(), anyString(), anyString());
    verify(obligations).markSucceeded(o.getId(), "rfnd_existing");
  }

  @Test
  void refundWithNoPriorRefundReissuesWithTheObligationIdAsIdempotencyKey() {
    BillingGatewayObligation o =
        base(BillingGatewayObligation.KIND_PRORATED_REFUND)
            .gateway("RAZORPAY")
            .gatewayRef("pay_2")
            .amountMinor(750L)
            .detail("Prorated refund")
            .build();
    when(obligations.retryable()).thenReturn(List.of(o));
    when(razorpayGateway.findExistingRefundId("pay_2", 750L)).thenReturn(null);
    when(razorpayGateway.refundPartial(
            eq("pay_2"), eq(750L), anyString(), eq(o.getId().toString())))
        .thenReturn("rfnd_new");

    drainer.drain();

    verify(razorpayGateway)
        .refundPartial(eq("pay_2"), eq(750L), anyString(), eq(o.getId().toString()));
    verify(obligations).markSucceeded(o.getId(), "rfnd_new");
  }

  @Test
  void refundOnAGatewayWithNoAutomatedPathIsMarkedUnsupported() {
    BillingGatewayObligation o =
        base(BillingGatewayObligation.KIND_PRORATED_REFUND)
            .gateway("STRIPE")
            .gatewayRef("pay_3")
            .amountMinor(200L)
            .build();
    when(obligations.retryable()).thenReturn(List.of(o));

    drainer.drain();

    verify(razorpayGateway, never())
        .refundPartial(anyString(), anyLong(), anyString(), anyString());
    verify(obligations).markUnsupported(eq(o.getId()), anyString());
  }

  @Test
  void refundThatCannotProveGatewayStateFailsRatherThanReissuingBlind() {
    BillingGatewayObligation o =
        base(BillingGatewayObligation.KIND_PRORATED_REFUND)
            .gateway("RAZORPAY")
            .gatewayRef("pay_4")
            .amountMinor(400L)
            .build();
    when(obligations.retryable()).thenReturn(List.of(o));
    when(razorpayGateway.findExistingRefundId("pay_4", 400L))
        .thenThrow(new RuntimeException("gateway 503"));

    drainer.drain();

    verify(razorpayGateway, never())
        .refundPartial(anyString(), anyLong(), anyString(), anyString());
    verify(obligations).markFailed(eq(o.getId()), anyString());
  }

  @Test
  void aFreshlyRecordedObligationIsDeferredByBackoff() {
    BillingGatewayObligation o =
        BillingGatewayObligation.builder()
            .id(UUID.randomUUID())
            .kind(BillingGatewayObligation.KIND_CANCEL_OLD_SUBSCRIPTION)
            .status(BillingGatewayObligation.STATUS_PENDING)
            .attempts(0)
            .gateway("RAZORPAY")
            .gatewayRef("sub_fresh")
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
    when(obligations.retryable()).thenReturn(List.of(o));

    drainer.drain();

    verify(obligations, never()).markSucceeded(any(), anyString());
    verify(obligations, never()).markFailed(any(), anyString());
    verifyNoInteractions(gatewayRouter);
  }

  @Test
  void cancellationFailureIsRecordedForRetry() {
    BillingGatewayObligation o =
        base(BillingGatewayObligation.KIND_CANCEL_OLD_SUBSCRIPTION)
            .gateway("RAZORPAY")
            .gatewayRef("sub_5")
            .build();
    when(obligations.retryable()).thenReturn(List.of(o));
    PaymentGateway gw = razorpayLikeGateway();
    when(gatewayRouter.resolveByName("RAZORPAY")).thenReturn(gw);
    when(gw.subscriptionStatus("sub_5")).thenReturn("active");
    Mockito.doThrow(new RuntimeException("gateway rejected")).when(gw).cancelSubscription("sub_5");

    drainer.drain();

    verify(obligations).markFailed(eq(o.getId()), anyString());
    verify(obligations, never()).markSucceeded(any(), anyString());
  }

  private PaymentGateway razorpayLikeGateway() {
    PaymentGateway gw = Mockito.mock(PaymentGateway.class);
    when(gw.gatewayType()).thenReturn("RAZORPAY");
    return gw;
  }
}

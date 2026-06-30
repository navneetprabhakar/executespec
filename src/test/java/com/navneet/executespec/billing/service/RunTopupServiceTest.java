package com.navneet.executespec.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.service.ActivityAuditService;
import com.navneet.executespec.billing.dto.TopupOrderInitResult;
import com.navneet.executespec.billing.entity.RunTopup;
import com.navneet.executespec.billing.entity.TopupPackage;
import com.navneet.executespec.billing.gateway.BillingEntityResolver;
import com.navneet.executespec.billing.gateway.BillingGateway;
import com.navneet.executespec.billing.gateway.PaymentGatewayRouter;
import com.navneet.executespec.billing.repository.BillingSubscriptionRepository;
import com.navneet.executespec.billing.repository.RunTopupRepository;
import com.navneet.executespec.billing.repository.TopupPackageRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RunTopupServiceTest {

  private final RunTopupRepository topupRepo = mock(RunTopupRepository.class);
  private final TopupPackageRepository packageRepo = mock(TopupPackageRepository.class);
  private final BillingSubscriptionRepository subscriptionRepo =
      mock(BillingSubscriptionRepository.class);
  private final BillingGateway razorpayGateway = mock(BillingGateway.class);
  private final InvoiceService invoiceService = mock(InvoiceService.class);
  private final ManagedAiUsageService managedAiUsageService = mock(ManagedAiUsageService.class);
  private final RunTopupService service =
      new RunTopupService(
          topupRepo,
          packageRepo,
          razorpayGateway,
          subscriptionRepo,
          mock(AppUserRepository.class),
          mock(ActivityAuditService.class),
          mock(BillingNotificationService.class),
          invoiceService,
          managedAiUsageService,
          mock(BillingEntityResolver.class),
          mock(PaymentGatewayRouter.class));

  @Test
  void initiatePurchaseStoresPackageReferenceForInvoiceResolution() {
    UUID packageId = UUID.randomUUID();
    TopupPackage pkg =
        TopupPackage.builder()
            .id(packageId)
            .name("Starter top-up")
            .runCount(10)
            .amountInr(49900)
            .active(true)
            .build();
    when(packageRepo.findById(packageId)).thenReturn(Optional.of(pkg));
    when(subscriptionRepo.findLatestCustomerIdByUserId("user-1")).thenReturn(Optional.empty());
    when(razorpayGateway.createOrder(eq(49900L), eq("INR"), any(), anyMap()))
        .thenReturn(new TopupOrderInitResult("order_1", null, "rzp_key", 49900));

    service.initiatePurchase("user-1", packageId);

    ArgumentCaptor<RunTopup> saved = ArgumentCaptor.forClass(RunTopup.class);
    verify(topupRepo).save(saved.capture());
    assertThat(saved.getValue().getGrantReason()).isEqualTo("pkg:" + packageId);
  }

  @Test
  void verifyAndCreditRejectsOrderOwnedByAnotherUserBeforeMutation() {
    RunTopup topup =
        RunTopup.builder()
            .id(UUID.randomUUID())
            .userId("owner-1")
            .quantity(10)
            .remaining(0)
            .status("PENDING")
            .gatewayOrderId("order_1")
            .build();

    when(razorpayGateway.verifyOrderPayment("order_1", "pay_1", "sig_1")).thenReturn(true);
    when(topupRepo.findByGatewayOrderId("order_1")).thenReturn(Optional.of(topup));

    assertThatThrownBy(() -> service.verifyAndCredit("attacker-1", "order_1", "pay_1", "sig_1"))
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("does not belong");

    verify(topupRepo, never()).save(topup);
    verify(topupRepo, never()).markPendingPurchaseCredited(any(), any(), any());
  }

  @Test
  void verifyAndCreditUsesAtomicActivationBeforeSideEffects() {
    RunTopup pending =
        RunTopup.builder()
            .id(UUID.randomUUID())
            .userId("user-1")
            .quantity(10)
            .remaining(0)
            .status("PENDING")
            .gatewayOrderId("order_1")
            .build();
    RunTopup active =
        RunTopup.builder()
            .id(pending.getId())
            .userId("user-1")
            .quantity(10)
            .remaining(0)
            .status("CREDITED")
            .gatewayOrderId("order_1")
            .gatewayPaymentId("pay_1")
            .build();

    when(razorpayGateway.verifyOrderPayment("order_1", "pay_1", "sig_1")).thenReturn(true);
    when(topupRepo.findByGatewayOrderId("order_1"))
        .thenReturn(Optional.of(pending))
        .thenReturn(Optional.of(active));
    when(topupRepo.markPendingPurchaseCredited(
            org.mockito.ArgumentMatchers.eq("order_1"),
            org.mockito.ArgumentMatchers.eq("pay_1"),
            any(Instant.class)))
        .thenReturn(1);

    RunTopup credited = service.verifyAndCredit("user-1", "order_1", "pay_1", "sig_1");

    assertThat(credited.getStatus()).isEqualTo("CREDITED");
    assertThat(credited.getRemaining()).isZero();
    verify(topupRepo)
        .markPendingPurchaseCredited(
            org.mockito.ArgumentMatchers.eq("order_1"),
            org.mockito.ArgumentMatchers.eq("pay_1"),
            any(Instant.class));
    verify(managedAiUsageService).grantPurchasedCredits("user-1", null, 10);
    verify(invoiceService).generateTopupInvoice(active, null, "pay_1");
    verify(topupRepo, never()).save(pending);
  }

  @Test
  void verifyAndCreditSkipsSideEffectsWhenAtomicActivationLosesRace() {
    RunTopup pending =
        RunTopup.builder()
            .id(UUID.randomUUID())
            .userId("user-1")
            .quantity(10)
            .remaining(0)
            .status("PENDING")
            .gatewayOrderId("order_1")
            .build();
    RunTopup active =
        RunTopup.builder()
            .id(pending.getId())
            .userId("user-1")
            .quantity(10)
            .remaining(0)
            .status("CREDITED")
            .gatewayOrderId("order_1")
            .gatewayPaymentId("pay_1")
            .build();

    when(razorpayGateway.verifyOrderPayment("order_1", "pay_1", "sig_1")).thenReturn(true);
    when(topupRepo.findByGatewayOrderId("order_1"))
        .thenReturn(Optional.of(pending))
        .thenReturn(Optional.of(active));
    when(topupRepo.markPendingPurchaseCredited(
            org.mockito.ArgumentMatchers.eq("order_1"),
            org.mockito.ArgumentMatchers.eq("pay_1"),
            any(Instant.class)))
        .thenReturn(0);

    RunTopup credited = service.verifyAndCredit("user-1", "order_1", "pay_1", "sig_1");

    assertThat(credited.getStatus()).isEqualTo("CREDITED");
    verify(invoiceService, never()).generateTopupInvoice(any(), any(), any());
    verify(managedAiUsageService, never())
        .grantPurchasedCredits(any(), any(), org.mockito.ArgumentMatchers.anyLong());
    verify(topupRepo, never()).save(pending);
  }
}

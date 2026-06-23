package com.navneet.executespec.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import com.navneet.executespec.billing.entity.BillingPlan;
import com.navneet.executespec.billing.entity.BillingSubscription;
import com.navneet.executespec.billing.repository.BillingSubscriptionRepository;

import org.junit.jupiter.api.Test;

class BillingSubscriptionWriterTest {

  private final BillingSubscriptionRepository subRepo = mock(BillingSubscriptionRepository.class);
  private final BillingSubscriptionWriter writer = writerWithTermsVersion();

  @Test
  void createPendingBuildsCreatedSubscriptionWithPlanDefaultsAndTermsVersion() {
    UUID planId = UUID.randomUUID();
    BillingPlan plan =
        BillingPlan.builder().id(planId).includedSeats(3).name("Team").tier("TEAM").build();

    when(subRepo.save(any(BillingSubscription.class))).thenAnswer(inv -> inv.getArgument(0));

    BillingSubscription sub =
        writer.createPending("u1", "org1", plan, "STRIPE", "cs_123", "cus_123", true);

    assertThat(sub.getUserId()).isEqualTo("u1");
    assertThat(sub.getOrgId()).isEqualTo("org1");
    assertThat(sub.getBillingPlanId()).isEqualTo(planId);
    assertThat(sub.getPaymentGateway()).isEqualTo("STRIPE");
    assertThat(sub.getGatewaySubscriptionId()).isEqualTo("cs_123");
    assertThat(sub.getGatewayCustomerId()).isEqualTo("cus_123");
    assertThat(sub.getStatus()).isEqualTo("CREATED");
    assertThat(sub.getTotalSeats()).isEqualTo(3);
    assertThat(sub.getExtraSeats()).isZero();
    assertThat(sub.isCancelAtPeriodEnd()).isFalse();
    assertThat(sub.getTermsAcceptedAt()).isNotNull();
    assertThat(sub.getTermsVersion()).isEqualTo("terms-test");
    assertThat(sub.isFounderPricing()).isTrue();
    verify(subRepo).save(sub);
  }

  private BillingSubscriptionWriter writerWithTermsVersion() {
    BillingSubscriptionWriter writer = new BillingSubscriptionWriter(subRepo);
    writer.setCurrentTermsVersionForTest("terms-test");
    return writer;
  }
}

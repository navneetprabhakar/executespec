package com.navneet.executespec.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.billing.client.RazorpayRestClient;
import com.navneet.executespec.billing.config.RazorpayConfig;
import com.navneet.executespec.billing.entity.BillingPlan;
import com.navneet.executespec.billing.repository.BillingPlanRepository;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * RR-400 — plan seeding must be safe to retry. The remaining hole after the earlier fix was a crash
 * between {@code createPlan} returning and the local id being committed, which duplicated the
 * chargeable Razorpay plan on retry. The fix threads a deterministic idempotency key derived from
 * the plan's local UUID into {@code createPlan}, so a retry reuses the same key and the gateway
 * dedupes.
 */
class PlanSeederServiceTest {

  private final RazorpayRestClient client = mock(RazorpayRestClient.class);
  private final RazorpayConfig config = mock(RazorpayConfig.class);
  private final BillingPlanRepository planRepo = mock(BillingPlanRepository.class);
  private final TransactionTemplate txTemplate = mock(TransactionTemplate.class);
  private final PlanSeederService service =
      new PlanSeederService(client, config, planRepo, txTemplate);

  @Test
  void seedingKeyIsDeterministicPerPlanAndDistinctAcrossPlans() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    assertThat(PlanSeederService.seedingKey(a)).isEqualTo(PlanSeederService.seedingKey(a));
    assertThat(PlanSeederService.seedingKey(a)).isNotEqualTo(PlanSeederService.seedingKey(b));
    assertThat(PlanSeederService.seedingKey(a)).isEqualTo(a.toString());
  }

  @Test
  void seedPassesTheDeterministicIdempotencyKeyToTheGateway() throws Exception {
    UUID planId = UUID.randomUUID();
    BillingPlan plan =
        BillingPlan.builder()
            .id(planId)
            .name("Pro Monthly")
            .period("MONTHLY")
            .baseAmountInr(50000)
            .baseAmountUsd(0)
            .active(true)
            .razorpayPlanId(null)
            .build();

    when(config.getKeyId()).thenReturn("rzp_test_abc123");
    when(config.getWebhookUrl()).thenReturn(null);
    when(planRepo.findByActiveTrue()).thenReturn(List.of(plan));
    JsonNode created = new ObjectMapper().readTree("{\"id\":\"plan_rzp_1\"}");
    when(client.createPlan(
            eq("Pro Monthly"), anyInt(), eq("monthly"), eq(1), eq(planId.toString())))
        .thenReturn(created);

    PlanSeederService.SeedResult result = service.seedAllPlans();

    verify(client)
        .createPlan(eq("Pro Monthly"), anyInt(), eq("monthly"), eq(1), eq(planId.toString()));
    assertThat(result.seeded()).isEqualTo(1);
    assertThat(result.failed()).isZero();
  }
}

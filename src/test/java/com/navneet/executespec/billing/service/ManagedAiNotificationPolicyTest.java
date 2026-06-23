package com.navneet.executespec.billing.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import com.navneet.executespec.billing.entity.ManagedAiStateNotification;
import com.navneet.executespec.billing.entity.ManagedAiUsagePeriod;

import org.junit.jupiter.api.Test;

class ManagedAiNotificationPolicyTest {

  private final ManagedAiNotificationPolicy policy = new ManagedAiNotificationPolicy();

  @Test
  void identifiesEmailStatesAndNoopTransitions() {
    assertThat(
            policy.shouldNotify(
                period(), ManagedAiRoutingPolicy.VALUE_MODE, ManagedAiRoutingPolicy.VALUE_MODE))
        .isFalse();
    assertThat(
            policy.shouldNotify(
                null, ManagedAiRoutingPolicy.VALUE_MODE, ManagedAiRoutingPolicy.CHEAP_ONLY))
        .isFalse();
    assertThat(
            policy.shouldNotify(
                period(), ManagedAiRoutingPolicy.VALUE_MODE, ManagedAiRoutingPolicy.CHEAP_ONLY))
        .isTrue();

    assertThat(policy.shouldEmail(ManagedAiRoutingPolicy.VALUE_MODE)).isFalse();
    assertThat(policy.shouldEmail(ManagedAiRoutingPolicy.FRONTIER_LOCKED)).isTrue();
    assertThat(policy.shouldEmail(ManagedAiRoutingPolicy.CHEAP_ONLY)).isTrue();
    assertThat(policy.shouldEmail(ManagedAiRoutingPolicy.EXHAUSTED)).isTrue();
  }

  @Test
  void buildsNotificationRecordAndStateSpecificCopy() {
    ManagedAiStateNotification record =
        policy.record(
            period(),
            ManagedAiRoutingPolicy.VALUE_MODE,
            ManagedAiRoutingPolicy.CHEAP_ONLY,
            ManagedAiNotificationPolicy.CHANNEL_EMAIL);

    assertThat(record.getId()).isNotBlank();
    assertThat(record.getPeriodId()).isEqualTo("period-1");
    assertThat(record.getUserId()).isEqualTo("u1");
    assertThat(record.getOrgId()).isEqualTo("o1");
    assertThat(record.getFromState()).isEqualTo(ManagedAiRoutingPolicy.VALUE_MODE);
    assertThat(record.getToState()).isEqualTo(ManagedAiRoutingPolicy.CHEAP_ONLY);
    assertThat(record.getChannel()).isEqualTo(ManagedAiNotificationPolicy.CHANNEL_EMAIL);
    assertThat(record.getSentAt()).isNotNull();
    assertThat(policy.copyFor(ManagedAiRoutingPolicy.CHEAP_ONLY)).contains("Efficient model mode");
    assertThat(policy.copyFor("UNKNOWN")).isEqualTo("Managed AI routing state changed.");
  }

  private static ManagedAiUsagePeriod period() {
    Instant now = Instant.parse("2026-06-01T00:00:00Z");
    return ManagedAiUsagePeriod.builder()
        .id("period-1")
        .userId("u1")
        .orgId("o1")
        .tier("PRO")
        .periodStart(now)
        .periodEnd(now.plusSeconds(31L * 24 * 3600))
        .routingState(ManagedAiRoutingPolicy.VALUE_MODE)
        .createdAt(now)
        .updatedAt(now)
        .build();
  }
}

package com.navneet.executespec.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.billing.entity.BillingPlan;
import com.navneet.executespec.billing.exception.BillingException;

import org.junit.jupiter.api.Test;

class BillingSubscriptionPolicyTest {

  private final TierConfigRepository tierConfigRepository = mock(TierConfigRepository.class);
  private final BillingSubscriptionPolicy policy =
      new BillingSubscriptionPolicy(tierConfigRepository);

  @Test
  void teamSeatsMustStayInSelfServeRange() {
    BillingPlan team = plan("TEAM");

    assertThat(policy.effectiveSeats(team, 1)).isEqualTo(1);
    assertThat(policy.effectiveSeats(team, 20)).isEqualTo(20);
    assertThatThrownBy(() -> policy.effectiveSeats(team, 0))
        .isInstanceOf(BillingException.class)
        .hasMessageContaining("TEAM tier requires 1-20 seats");
    assertThatThrownBy(() -> policy.effectiveSeats(team, 21))
        .isInstanceOf(BillingException.class)
        .hasMessageContaining("TEAM tier requires 1-20 seats");
  }

  @Test
  void nonTeamPlansForceOneSeat() {
    assertThat(policy.effectiveSeats(plan("PRO"), 3)).isEqualTo(1);
  }

  @Test
  void planMustBeActiveListedAndNonFreeForCheckout() {
    BillingPlan inactive = plan("PRO");
    inactive.setActive(false);
    assertThatThrownBy(() -> policy.requireNewSubscriptionAvailable(inactive))
        .isInstanceOf(BillingException.class)
        .hasMessageContaining("no longer available");

    BillingPlan hidden = plan("PRO");
    hidden.setLifecycleState("HIDDEN");
    assertThatThrownBy(() -> policy.requireNewSubscriptionAvailable(hidden))
        .isInstanceOf(BillingException.class)
        .hasMessageContaining("not available");

    BillingPlan free = plan("FREE");
    assertThatThrownBy(() -> policy.requireNewSubscriptionAvailable(free))
        .isInstanceOf(BillingException.class)
        .hasMessageContaining("free plan");
  }

  @Test
  void hiddenTierBlocksListedPlan() {
    TierConfig tier = new TierConfig();
    tier.setTier("PRO");
    tier.setLifecycleState("HIDDEN");
    when(tierConfigRepository.findById("PRO")).thenReturn(Optional.of(tier));

    assertThat(policy.tierIsListed("PRO")).isFalse();
    assertThatThrownBy(() -> policy.requireNewSubscriptionAvailable(plan("PRO")))
        .isInstanceOf(BillingException.class)
        .hasMessageContaining("not available");
  }

  @Test
  void missingTierConfigDefaultsToListed() {
    when(tierConfigRepository.findById("PRO")).thenReturn(Optional.empty());

    assertThat(policy.tierIsListed("PRO")).isTrue();
  }

  @Test
  void onlyStrictTierUpgradesAreAllowed() {
    assertThatThrownBy(() -> policy.requireUpgrade("PRO", "PRO"))
        .isInstanceOf(BillingException.class)
        .hasMessageContaining("active PRO subscription");
    assertThatThrownBy(() -> policy.requireUpgrade("PRO", "TEAM"))
        .isInstanceOf(BillingException.class)
        .hasMessageContaining("active TEAM subscription");

    assertThat(policy.isStrictUpgrade("PRO", "TEAM")).isFalse();
    assertThat(policy.isStrictUpgrade("TEAM", "TEAM")).isFalse();
    assertThat(policy.isStrictUpgrade("TEAM", "PRO")).isTrue();
    policy.requireUpgrade("TEAM", "PRO");
  }

  @Test
  void founderPricingUsesSlotCap() {
    BillingPlan plan = plan("PRO");
    plan.setFounderPricing(true);

    assertThat(policy.founderPricingAvailable(plan, 99, 100)).isTrue();
    assertThatThrownBy(() -> policy.founderPricingAvailable(plan, 100, 100))
        .isInstanceOf(BillingException.class)
        .hasMessageContaining("Founder pricing is no longer available");
  }

  private static BillingPlan plan(String tier) {
    return BillingPlan.builder()
        .id(UUID.randomUUID())
        .name(tier)
        .tier(tier)
        .period("MONTHLY")
        .active(true)
        .lifecycleState("LISTED")
        .build();
  }
}

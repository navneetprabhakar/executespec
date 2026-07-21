package com.navneet.executespec.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.navneet.executespec.billing.exception.BillingException;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Regression pins for the 2026-07-20 billing-integrity audit findings that are pure decisions.
 * Behaviour that needs a database (credit-note balance, obligation dedupe) is covered by the
 * service-level tests and the V310 constraints instead.
 */
class BillingIntegrityHardeningTest {

  @Nested
  class Rr410PaidTierTranslation {

    @Test
    void everySeededPaidTierMapsToItselfRatherThanFree() {
      // These were the two the old switch had no case for; both fell through to FREE, so a
      // customer who paid for Pro+ or BYOK immediately received FREE entitlements.
      assertThat(BillingTierBridgeService.authTierFor("PRO_PLUS")).isEqualTo("PRO_PLUS");
      assertThat(BillingTierBridgeService.authTierFor("BYOK")).isEqualTo("BYOK");

      assertThat(BillingTierBridgeService.authTierFor("PRO")).isEqualTo("PRO");
      assertThat(BillingTierBridgeService.authTierFor("TEAM")).isEqualTo("TEAM");
      assertThat(BillingTierBridgeService.authTierFor("ENTERPRISE")).isEqualTo("ENTERPRISE");
    }

    @Test
    void legacyTierSpellingsStillResolve() {
      assertThat(BillingTierBridgeService.authTierFor("STARTER")).isEqualTo("FREE");
      assertThat(BillingTierBridgeService.authTierFor("MAX")).isEqualTo("TEAM");
      assertThat(BillingTierBridgeService.authTierFor("free")).isEqualTo("FREE");
    }

    @Test
    void unknownTierFailsLoudlyInsteadOfSilentlyGrantingFree() {
      assertThatThrownBy(() -> BillingTierBridgeService.authTierFor("PLATINUM"))
          .isInstanceOf(BillingException.class)
          .hasMessageContaining("PLATINUM");
      assertThatThrownBy(() -> BillingTierBridgeService.authTierFor(null))
          .isInstanceOf(BillingException.class);
    }

    @Test
    void byokKeyModeStillMapsToOwnKeys() {
      // The contradictory tier=FREE + keySourceMode=OWN_KEYS org came from the tier side, not
      // this one; key-mode translation is unchanged and pinned here so it stays that way.
      assertThat(BillingTierBridgeService.keySourceModeFor("BYOK")).isEqualTo("OWN_KEYS");
      assertThat(BillingTierBridgeService.keySourceModeFor("MANAGED")).isEqualTo("SYSTEM");
      assertThat(BillingTierBridgeService.keySourceModeFor("PLATFORM")).isEqualTo("SYSTEM");
      assertThat(BillingTierBridgeService.keySourceModeFor(null)).isEqualTo("HYBRID");
    }
  }

  @Nested
  class Rr418AnnualPeriod {

    @Test
    void migrationSeededYearlyCountsAsAnnualEverywhere() {
      // Every annual migration wrote 'YEARLY'; the runtime only recognised 'ANNUAL' and fell
      // through to monthly semantics for the entitlement window AND the Razorpay plan.
      assertThat(BillingPeriodService.isAnnual("YEARLY")).isTrue();
      assertThat(BillingPeriodService.isAnnual("ANNUAL")).isTrue();
      assertThat(BillingPeriodService.isAnnual("annual")).isTrue();
      assertThat(PlanSeederService.annualPeriod("YEARLY")).isTrue();
    }

    @Test
    void monthlyAndUnknownPeriodsAreNotAnnual() {
      assertThat(BillingPeriodService.isAnnual("MONTHLY")).isFalse();
      assertThat(BillingPeriodService.isAnnual(null)).isFalse();
      assertThat(BillingPeriodService.isAnnual("")).isFalse();
      assertThat(PlanSeederService.annualPeriod("MONTHLY")).isFalse();
    }
  }

  @Nested
  class Rr048ObligationKeys {

    @Test
    void keysAreDeterministicPerActionSoRetriesDoNotDuplicateGatewayCalls() {
      String first = BillingGatewayObligationService.key("PRORATED_REFUND", "sub-1", "pay-1", 500L);
      String second =
          BillingGatewayObligationService.key("PRORATED_REFUND", "sub-1", "pay-1", 500L);
      assertThat(first).isEqualTo(second);
    }

    @Test
    void differentActionsAndAmountsGetDistinctKeys() {
      assertThat(BillingGatewayObligationService.key("PRORATED_REFUND", "sub-1", "pay-1", 500L))
          .isNotEqualTo(
              BillingGatewayObligationService.key("PRORATED_REFUND", "sub-1", "pay-1", 501L));
      assertThat(BillingGatewayObligationService.key("PRORATED_REFUND", "sub-1"))
          .isNotEqualTo(BillingGatewayObligationService.key("CANCEL_OLD_SUBSCRIPTION", "sub-1"));
    }

    @Test
    void nullPartsAreDistinguishableAndKeysStayWithinTheColumnWidth() {
      assertThat(BillingGatewayObligationService.key("PRORATED_REFUND", (Object) null))
          .isEqualTo("PRORATED_REFUND:-");
      assertThat(BillingGatewayObligationService.key("PRORATED_REFUND", "x".repeat(400)).length())
          .isLessThanOrEqualTo(255);
    }
  }
}

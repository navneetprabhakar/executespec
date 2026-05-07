package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * P3.2 (GA-readiness, 2026-05-06) — pins the hard-cap clamp helper used by {@link
 * RunOrchestrator#persistEstimate} to honour the customer-supplied {@code userCostCap} override.
 *
 * <p>Contract:
 *
 * <ul>
 *   <li>{@code userCostCap = null} → platform cap unchanged (legacy runs + omitted override).
 *   <li>{@code userCostCap > platformCap} → platform cap unchanged (customer can't raise the cap
 *       past what credits authorised; raising would imply an unbilled charge).
 *   <li>{@code userCostCap < platformCap} → use the user cap (customer voluntarily lowering).
 *   <li>{@code userCostCap == platformCap} → either equally valid; result is the value.
 *   <li>{@code userCostCap <= 0} → treated as "not set" defensively even though DTO validation
 *       rejects this; protects against transitive bad state from older clients or admin edits.
 * </ul>
 */
class RunOrchestratorClampHardCapTest {

  @Test
  void nullUserCap_returnsPlatformCap() {
    assertThat(RunOrchestrator.clampHardCap(7.50, null)).isEqualTo(7.50);
  }

  @Test
  void userCapHigherThanPlatform_returnsPlatform() {
    // Customer asks for $20 cap on a $7.50 credit-derived ceiling. Cap stays at $7.50 — credits
    // are the source of truth for the upper bound.
    assertThat(RunOrchestrator.clampHardCap(7.50, 20.0)).isEqualTo(7.50);
  }

  @Test
  void userCapLowerThanPlatform_returnsUser() {
    // Customer voluntarily restricts to $2.00 on a $7.50 platform cap. Worker stops at $2.
    assertThat(RunOrchestrator.clampHardCap(7.50, 2.0)).isEqualTo(2.0);
  }

  @Test
  void userCapEqualsPlatform_returnsValue() {
    assertThat(RunOrchestrator.clampHardCap(5.0, 5.0)).isEqualTo(5.0, within(0.0001));
  }

  @Test
  void zeroUserCap_treatedAsUnset() {
    // 0 means "no cap requested" defensively. DTO validation rejects this but defensive code
    // prevents a stale-state bug from accidentally creating a cap-of-zero (which would
    // immediately terminate every run on iter 1).
    assertThat(RunOrchestrator.clampHardCap(7.50, 0.0)).isEqualTo(7.50);
  }

  @Test
  void negativeUserCap_treatedAsUnset() {
    assertThat(RunOrchestrator.clampHardCap(7.50, -1.0)).isEqualTo(7.50);
  }

  @Test
  void zeroPlatform_andUserCapHigher_returnsPlatformZero() {
    // Edge: when the platform cap is 0 (legacy/admin override), nothing the user supplies
    // should escalate above it. Even a positive userCap can't increase a 0 platform.
    // min(0, 5.0) = 0 — worker terminates on iter 1.
    assertThat(RunOrchestrator.clampHardCap(0.0, 5.0)).isEqualTo(0.0);
  }
}

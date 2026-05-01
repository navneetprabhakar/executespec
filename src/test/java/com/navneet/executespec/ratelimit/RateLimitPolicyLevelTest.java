package com.navneet.executespec.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.navneet.executespec.security.guardrail.GuardrailLevel;

import org.junit.jupiter.api.Test;

/** Guardrail-level multiplier tests for {@link RateLimitPolicy} (Plan 1 P3). */
class RateLimitPolicyLevelTest {

  private final RateLimitPolicy policy = new RateLimitPolicy();

  @Test
  void lowKeepsBaseLimits() {
    assertThat(policy.getLimit("WRITE", "PRO", GuardrailLevel.LOW)).isEqualTo(200);
    assertThat(policy.getLimit("READ", "FREE", GuardrailLevel.LOW)).isEqualTo(300);
    assertThat(policy.getLimit("DEPLOY", "ENTERPRISE", GuardrailLevel.LOW)).isEqualTo(60);
    assertThat(policy.getLimit("AUTH", "login", GuardrailLevel.LOW)).isEqualTo(10);
  }

  @Test
  void mediumHalvesWriteDeployPreviewOnly() {
    assertThat(policy.getLimit("WRITE", "PRO", GuardrailLevel.MEDIUM)).isEqualTo(100);
    assertThat(policy.getLimit("DEPLOY", "ENTERPRISE", GuardrailLevel.MEDIUM)).isEqualTo(30);
    assertThat(policy.getLimit("PREVIEW", "PRO", GuardrailLevel.MEDIUM)).isEqualTo(30);
    // READ / AUTH / RUNS / WEBHOOK untouched at MEDIUM
    assertThat(policy.getLimit("READ", "FREE", GuardrailLevel.MEDIUM)).isEqualTo(300);
    assertThat(policy.getLimit("AUTH", "login", GuardrailLevel.MEDIUM)).isEqualTo(10);
    assertThat(policy.getLimit("RUNS", "PRO", GuardrailLevel.MEDIUM)).isEqualTo(20);
    assertThat(policy.getLimit("WEBHOOK", "DEFAULT", GuardrailLevel.MEDIUM)).isEqualTo(100);
  }

  @Test
  void highQuartersEveryGroup() {
    assertThat(policy.getLimit("WRITE", "PRO", GuardrailLevel.HIGH)).isEqualTo(50);
    assertThat(policy.getLimit("READ", "FREE", GuardrailLevel.HIGH)).isEqualTo(75);
    assertThat(policy.getLimit("DEPLOY", "ENTERPRISE", GuardrailLevel.HIGH)).isEqualTo(15);
    assertThat(policy.getLimit("PREVIEW", "FREE", GuardrailLevel.HIGH)).isEqualTo(7);
    assertThat(policy.getLimit("AUTH", "login", GuardrailLevel.HIGH)).isEqualTo(2);
    assertThat(policy.getLimit("RUNS", "PRO", GuardrailLevel.HIGH)).isEqualTo(5);
    assertThat(policy.getLimit("WEBHOOK", "DEFAULT", GuardrailLevel.HIGH)).isEqualTo(25);
  }

  @Test
  void highFloorIsOne() {
    // forgot-password is already 3 — /4 would round to 0, but floor enforces 1
    assertThat(policy.getLimit("AUTH", "forgot", GuardrailLevel.HIGH)).isEqualTo(1);
  }

  @Test
  void nullLevelTreatedAsLow() {
    assertThat(policy.getLimit("WRITE", "PRO", null)).isEqualTo(200);
  }

  @Test
  void noArgGetLimitMatchesLowWhenResolverAbsent() {
    // plain constructor — no resolver wired — falls back to LOW behaviour
    assertThat(policy.getLimit("WRITE", "PRO")).isEqualTo(200);
    assertThat(policy.getLimit("RUNS", "FREE")).isEqualTo(5);
  }
}

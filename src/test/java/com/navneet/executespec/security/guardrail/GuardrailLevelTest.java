package com.navneet.executespec.security.guardrail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class GuardrailLevelTest {

  @Test
  void parseNullFallsBackToLow() {
    assertThat(GuardrailLevel.parse(null)).isEqualTo(GuardrailLevel.LOW);
  }

  @ParameterizedTest
  @ValueSource(strings = {"INVALID", "garbage", "", "   "})
  void parseUnknownFallsBackToLow(String input) {
    assertThat(GuardrailLevel.parse(input)).isEqualTo(GuardrailLevel.LOW);
  }

  @ParameterizedTest
  @CsvSource({
    "LOW, LOW",
    "low, LOW",
    "Low, LOW",
    "MEDIUM, MEDIUM",
    "medium, MEDIUM",
    "HIGH, HIGH",
    "high, HIGH",
    "  high  , HIGH"
  })
  void parseCasesAndWhitespace(String input, String expected) {
    assertThat(GuardrailLevel.parse(input)).isEqualTo(GuardrailLevel.valueOf(expected));
  }

  @Test
  void atLeastMediumSemantics() {
    assertThat(GuardrailLevel.LOW.atLeastMedium()).isFalse();
    assertThat(GuardrailLevel.MEDIUM.atLeastMedium()).isTrue();
    assertThat(GuardrailLevel.HIGH.atLeastMedium()).isTrue();
  }

  @Test
  void atLeastHighSemantics() {
    assertThat(GuardrailLevel.LOW.atLeastHigh()).isFalse();
    assertThat(GuardrailLevel.MEDIUM.atLeastHigh()).isFalse();
    assertThat(GuardrailLevel.HIGH.atLeastHigh()).isTrue();
  }
}

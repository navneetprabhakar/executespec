package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The V348 integrity trigger recomputes the receipt's money fields in PostgreSQL as {@code
 * to_char(round(value::numeric, 6), …)}, where {@code float8::numeric} is C {@code %.15g}: the
 * EXACT binary double correctly rounded to 15 significant decimal digits (ties to even). The Java
 * side must produce byte-identical strings or the deferred constraint rejects the COMMIT of every
 * APPROVE/REJECT/CANCEL for that run — which is exactly what happened on dev run e7f599b7, whose
 * {@code cost_estimate_high} double {@code 0.5961704999999999} (exact binary 0.59617049999999994…)
 * rounds to {@code 0.596171} under PostgreSQL semantics but rounded to {@code 0.596170} under the
 * previous shortest-representation {@code BigDecimal.valueOf} path.
 */
class PlanGateDecisionDecimalParityTest {

  @Test
  void liveBoundaryValueMatchesThePostgresFloat8NumericCast() {
    // The dev e7f599b7 value: shortest repr 0.5961704999999999, exact binary just below
    // 0.5961705 in decimal-17 but AT/ABOVE it once truncated to 15 significant digits.
    assertThat(PlanGateDecisionEvidence.decimal(0.5961704999999999d)).isEqualTo("0.596171");
  }

  @Test
  void ordinaryValuesAreUnchangedByTheParityRounding() {
    assertThat(PlanGateDecisionEvidence.decimal(0.6640305d)).isEqualTo("0.664031");
    assertThat(PlanGateDecisionEvidence.decimal(5.0d)).isEqualTo("5.000000");
    assertThat(PlanGateDecisionEvidence.decimal(0.0d)).isEqualTo("0.000000");
    assertThat(PlanGateDecisionEvidence.decimal(0.005337d)).isEqualTo("0.005337");
    assertThat(PlanGateDecisionEvidence.decimal(0.1987235d)).isEqualTo("0.198724");
  }

  @Test
  void fifteenSignificantDigitPreRoundingMirrorsPrintfG15() {
    // 1234567890.123456789 stores as a double whose %.15g rendering is 1234567890.12346;
    // six-decimal scaling then pads. The shortest-repr path would keep more digits but the
    // 15-significant-digit pre-round must dominate.
    assertThat(PlanGateDecisionEvidence.decimal(1234567890.123456789d))
        .isEqualTo("1234567890.123460");
  }
}

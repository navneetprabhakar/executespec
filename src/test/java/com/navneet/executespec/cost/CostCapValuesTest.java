package com.navneet.executespec.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * RR-825 — pins the fail-closed hard-cost-cap contract, including the exact JSON scalars that
 * defeated it. The agent confirmation surface had no bean validation, and Jackson maps the quoted
 * strings {@code "NaN"} / {@code "Infinity"} onto a {@code Double}, so a caller could persist a cap
 * that the worker's {@code hardCapUsd > 0 && spent >= hardCapUsd} test can never satisfy.
 */
class CostCapValuesTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void jacksonStillAcceptsQuotedNonFiniteScalars() throws Exception {
    // Documents WHY the validator exists — if this ever stops being true the attack surface
    // narrows, but the validator remains the boundary.
    assertThat(mapper.readValue("\"NaN\"", Double.class)).isNaN();
    assertThat(mapper.readValue("\"Infinity\"", Double.class)).isEqualTo(Double.POSITIVE_INFINITY);
    assertThat(mapper.readValue("\"-Infinity\"", Double.class)).isEqualTo(Double.NEGATIVE_INFINITY);
  }

  @Test
  void rejectsNaN() {
    assertThatThrownBy(() -> CostCapValues.requireValidOptional(Double.NaN))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("finite");
  }

  @Test
  void rejectsPositiveAndNegativeInfinity() {
    assertThatThrownBy(() -> CostCapValues.requireValidOptional(Double.POSITIVE_INFINITY))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> CostCapValues.requireValidOptional(Double.NEGATIVE_INFINITY))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void rejectsValuesOutsideTheDocumentedBand() {
    assertThatThrownBy(() -> CostCapValues.requireValidOptional(0.0))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> CostCapValues.requireValidOptional(-5.0))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> CostCapValues.requireValidOptional(1000.01))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void acceptsAbsentAndInBandValues() {
    assertThat(CostCapValues.requireValidOptional(null)).isNull();
    assertThatCode(() -> CostCapValues.requireValidOptional(CostCapValues.MIN_USD))
        .doesNotThrowAnyException();
    assertThatCode(() -> CostCapValues.requireValidOptional(12.5)).doesNotThrowAnyException();
    assertThatCode(() -> CostCapValues.requireValidOptional(CostCapValues.MAX_USD))
        .doesNotThrowAnyException();
  }
}

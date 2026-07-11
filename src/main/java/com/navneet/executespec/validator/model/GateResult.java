package com.navneet.executespec.validator.model;

import java.time.Duration;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GateResult {
  private ValidationGate gate;
  private boolean passed;
  private Duration duration;
  private List<ValidationError> errors;
  private String rawOutput; // raw stdout+stderr from the tool

  /** False when the gate was skipped because its tool/fixture is unavailable. */
  @Builder.Default private boolean measured = true;

  public static GateResult unmeasured(ValidationGate gate, String reason) {
    return GateResult.builder()
        .gate(gate)
        .passed(false)
        .measured(false)
        .duration(Duration.ZERO)
        .errors(List.of())
        .rawOutput(reason)
        .build();
  }
}

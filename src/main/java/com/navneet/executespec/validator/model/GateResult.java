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
}

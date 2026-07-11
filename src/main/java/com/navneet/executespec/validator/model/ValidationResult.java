package com.navneet.executespec.validator.model;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidationResult {
  private ValidationVerdict verdict;
  private List<ValidationError> errors;
  private List<ValidationError> warnings;
  private Map<ValidationGate, GateResult> gateResults;
  private Duration totalTime;
  private String rawOutput; // combined raw output from all gates — used for retry prompt

  /** True only when every required gate actually executed and produced evidence. */
  @Builder.Default private boolean measured = true;

  /** Machine-readable explanation when {@link #measured} is false. */
  private String evidenceStatus;

  private String unmeasuredReason;

  public static ValidationResult pass() {
    return ValidationResult.builder()
        .verdict(ValidationVerdict.PASS)
        .errors(Collections.emptyList())
        .warnings(Collections.emptyList())
        .gateResults(Collections.emptyMap())
        .measured(true)
        .evidenceStatus("VERIFIED")
        .build();
  }

  public static ValidationResult unmeasured(String reason) {
    return ValidationResult.builder()
        .verdict(ValidationVerdict.UNMEASURED)
        .errors(Collections.emptyList())
        .warnings(Collections.emptyList())
        .gateResults(Collections.emptyMap())
        .measured(false)
        .evidenceStatus("NOT_VERIFIED")
        .unmeasuredReason(reason)
        .rawOutput(reason)
        .build();
  }
}

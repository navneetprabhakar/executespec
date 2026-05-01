package com.navneet.executespec.validator.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidationError {
  private String file;
  private int line;
  private int column;
  private String gate; // "SYNTAX", "TYPE_CHECK", "LINT", "UNIT_TEST"
  private String code; // error code from the tool, e.g. "TS2304", "E0001"
  private String message; // human-readable error message
  private String suggestion; // nullable — fix hint if available
  private Severity severity;

  public enum Severity {
    ERROR,
    WARNING
  }
}

package com.navneet.executespec.testgen.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestExecutionSummary {
  private int totalTests;
  private int passed;
  private int failed;
  private int skipped;
  private int errors;
  private long executionDurationMs;
  private boolean allConnectionRefused;

  /** Empty summary for pipeline failures where no tests were executed. */
  public static TestExecutionSummary empty() {
    return TestExecutionSummary.builder().build();
  }
}

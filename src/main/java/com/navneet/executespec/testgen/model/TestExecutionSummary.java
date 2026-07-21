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

  /**
   * Freshness/provenance token (RR-1582).
   *
   * <p>{@code TRUE} means the producer proved these counts came from THIS attempt: the report
   * directory was cleared before launch, the runner exited within its deadline with a status that
   * means "tests ran", every parsed report file postdates the launch, and the tested workspace
   * fingerprint still matched the plan it was generated for (RR-1583).
   *
   * <p>{@code FALSE} means the producer explicitly could not prove that — see {@link
   * #unverifiedReason}; the counts must not be presented as executed-test evidence.
   *
   * <p>{@code null} means the producing path records no provenance signal at all. Treat it as
   * unproven, not as proven-false.
   */
  private Boolean executionVerified;

  /** Per-attempt execution id; also names the directory the parsed reports were read from. */
  private String executionId;

  /** Why {@link #executionVerified} is FALSE. Null when verified or when no signal was recorded. */
  private String unverifiedReason;

  /** Empty summary for pipeline failures where no tests were executed. */
  public static TestExecutionSummary empty() {
    return TestExecutionSummary.builder().build();
  }

  /**
   * Explicitly-unavailable summary: the attempt did not produce trustworthy counts. Carries zero
   * tests so any consumer that already special-cases 0/0 keeps behaving conservatively.
   */
  public static TestExecutionSummary unavailable(String executionId, String reason) {
    return TestExecutionSummary.builder()
        .executionVerified(Boolean.FALSE)
        .executionId(executionId)
        .unverifiedReason(reason)
        .build();
  }

  /** True only when the producer positively proved this attempt's provenance. */
  public boolean isExecutionProven() {
    return Boolean.TRUE.equals(executionVerified);
  }
}

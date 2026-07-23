package com.navneet.executespec.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class LlmUsageAuditQueryTest {

  @Test
  void convertsOrgCalendarDatesAcrossDstWithoutAssumingTwentyFourHours() {
    LlmUsageAuditQuery query =
        LlmUsageAuditQuery.of(
            "org-1",
            LocalDate.of(2026, 3, 8),
            LocalDate.of(2026, 3, 8),
            "America/New_York",
            0,
            100);

    assertThat(Duration.between(query.fromInclusive(), query.toExclusive()))
        .isEqualTo(Duration.ofHours(23));
  }

  @Test
  void rejectsUnboundedDatesPagesAndRows() {
    assertThatThrownBy(
            () ->
                LlmUsageAuditQuery.of(
                    "org-1", LocalDate.of(2025, 1, 1), LocalDate.of(2026, 7, 1), "UTC", 0, 100))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("366");
    assertThatThrownBy(
            () ->
                LlmUsageAuditQuery.of(
                    "org-1", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2), "UTC", 0, 501))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("size");
    assertThatThrownBy(
            () ->
                LlmUsageAuditQuery.of(
                    "org-1", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2), "UTC", -1, 100))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("page");
    assertThatThrownBy(
            () ->
                LlmUsageAuditQuery.of(
                    "x".repeat(65),
                    LocalDate.of(2026, 1, 1),
                    LocalDate.of(2026, 1, 2),
                    "UTC",
                    0,
                    100))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("orgId");
  }
}

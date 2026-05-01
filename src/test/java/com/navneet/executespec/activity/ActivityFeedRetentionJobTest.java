package com.navneet.executespec.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class ActivityFeedRetentionJobTest {

  private JdbcTemplate jdbc;
  private ActivityFeedRetentionJob job;

  @BeforeEach
  void setUp() {
    jdbc = mock(JdbcTemplate.class);
    // M-038 — constructor now takes FeatureFlagService too. Pass null;
    // effectiveRetentionDays() handles the null branch and falls back
    // to retentionDaysDefault.
    job = new ActivityFeedRetentionJob(jdbc, null);
    ReflectionTestUtils.setField(job, "retentionDaysDefault", 365);
    ReflectionTestUtils.setField(job, "dryRun", true);
  }

  @Test
  void parsePartitionMonth_handlesValidNames() {
    assertThat(ActivityFeedRetentionJob.parsePartitionMonth("activity_feed_y2026m04"))
        .isEqualTo(YearMonth.of(2026, 4));
    assertThat(ActivityFeedRetentionJob.parsePartitionMonth("public.activity_feed_y2027m12"))
        .isEqualTo(YearMonth.of(2027, 12));
  }

  @Test
  void parsePartitionMonth_returnsNullForInvalidNames() {
    assertThat(ActivityFeedRetentionJob.parsePartitionMonth("activity_feed_y26m04")).isNull();
    assertThat(ActivityFeedRetentionJob.parsePartitionMonth("activity_feed")).isNull();
    assertThat(ActivityFeedRetentionJob.parsePartitionMonth("foo_bar")).isNull();
    assertThat(ActivityFeedRetentionJob.parsePartitionMonth(null)).isNull();
  }

  @Test
  void dryRun_logsButDoesNotDrop() {
    when(jdbc.queryForList(anyString(), eq(String.class)))
        .thenReturn(
            List.of(
                "activity_feed_y2024m01", // very old → would drop
                "activity_feed_y2025m04", // ~1y old → depends on date
                "activity_feed_y2027m04")); // future → keep

    // Today = 2026-04-25 + retention 365d → cutoff = 2025-04
    int dropped = job.run(LocalDate.of(2026, 4, 25));
    assertThat(dropped).isEqualTo(1); // only 2024-01 is strictly < 2025-04

    // dry-run → no DETACH/DROP
    verify(jdbc, never()).execute(anyString());
  }

  @Test
  void liveMode_dropsOldPartitions() {
    ReflectionTestUtils.setField(job, "dryRun", false);
    when(jdbc.queryForList(anyString(), eq(String.class)))
        .thenReturn(
            List.of(
                "activity_feed_y2024m01", // old
                "activity_feed_y2026m04")); // current

    int dropped = job.run(LocalDate.of(2026, 4, 25));
    assertThat(dropped).isEqualTo(1);

    verify(jdbc, times(1))
        .execute(eq("ALTER TABLE activity_feed DETACH PARTITION activity_feed_y2024m01"));
    verify(jdbc, times(1)).execute(eq("DROP TABLE IF EXISTS activity_feed_y2024m01"));
  }

  @Test
  void noOldPartitions_isNoop() {
    when(jdbc.queryForList(anyString(), eq(String.class)))
        .thenReturn(List.of("activity_feed_y2026m04", "activity_feed_y2026m05"));
    int dropped = job.run(LocalDate.of(2026, 4, 25));
    assertThat(dropped).isZero();
  }

  @Test
  void partitionListLookupFailure_returnsZero_doesNotThrow() {
    when(jdbc.queryForList(anyString(), eq(String.class)))
        .thenThrow(new RuntimeException("connection lost"));
    assertThat(job.run(LocalDate.of(2026, 4, 25))).isZero();
  }

  @Test
  void invalidPartitionNames_skipped() {
    when(jdbc.queryForList(anyString(), eq(String.class)))
        .thenReturn(
            List.of(
                "activity_feed_y2024m01", // valid old
                "activity_feed", // parent table — skip
                "some_other_table")); // unrelated — skip
    int dropped = job.run(LocalDate.of(2026, 4, 25));
    assertThat(dropped).isEqualTo(1); // only the valid old one
  }
}

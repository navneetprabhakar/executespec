package com.navneet.executespec.activity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ActivityFeedPartitionJob}. Verifies that the
 * partition-creation DDL is well-formed and covers the right month
 * range — without touching a real Postgres (DDL is captured + asserted).
 */
class ActivityFeedPartitionJobTest {

    private JdbcTemplate jdbc;
    private ActivityFeedPartitionJob job;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        job = new ActivityFeedPartitionJob(jdbc);
    }

    @Test
    void ensurePartitionsFor_issuesDdlForCurrentPlusLookahead() {
        // 3 months ahead from a fixed date → 4 partitions (current + 3)
        int created = job.ensurePartitionsFor(LocalDate.of(2026, 11, 15), 3);
        assertThat(created).isEqualTo(4);
        verify(jdbc, times(4)).execute(anyString());
    }

    @Test
    void ensurePartitionsFor_zeroLookaheadIsNoop() {
        int created = job.ensurePartitionsFor(LocalDate.of(2026, 5, 15), 0);
        assertThat(created).isZero();
        verifyNoInteractions(jdbc);
    }

    @Test
    void ddl_includesIfNotExistsAndCorrectDateRange() {
        ArgumentCaptor<String> ddl = ArgumentCaptor.forClass(String.class);
        job.ensurePartitionsFor(LocalDate.of(2026, 11, 15), 1);
        verify(jdbc, times(2)).execute(ddl.capture());

        List<String> all = ddl.getAllValues();
        // First DDL covers Nov 2026; second covers Dec 2026.
        assertThat(all.get(0))
                .contains("CREATE TABLE IF NOT EXISTS activity_feed_y2026m11")
                .contains("PARTITION OF activity_feed")
                .contains("FROM ('2026-11-01')")
                .contains("TO ('2026-12-01')");
        assertThat(all.get(1))
                .contains("activity_feed_y2026m12")
                .contains("FROM ('2026-12-01')")
                .contains("TO ('2027-01-01')");
    }

    @Test
    void yearRollover_handledCorrectlyAcrossDecemberBoundary() {
        // Dec 2026 + 2 months ahead → Dec 2026, Jan 2027, Feb 2027
        ArgumentCaptor<String> ddl = ArgumentCaptor.forClass(String.class);
        job.ensurePartitionsFor(LocalDate.of(2026, 12, 1), 2);
        verify(jdbc, times(3)).execute(ddl.capture());

        assertThat(ddl.getAllValues().get(1))
                .contains("activity_feed_y2027m01")
                .contains("FROM ('2027-01-01')")
                .contains("TO ('2027-02-01')");
    }

    @Test
    void ddlExecutionFailure_isLoggedAndCounterDoesNotIncrement() {
        // Two CREATE attempts; first throws, second succeeds. Counter should be 1.
        doThrow(new RuntimeException("connection lost"))
                .doNothing()
                .when(jdbc).execute(anyString());

        int created = job.ensurePartitionsFor(LocalDate.of(2026, 5, 15), 1);
        assertThat(created).isEqualTo(1);
        verify(jdbc, times(2)).execute(anyString());
    }
}

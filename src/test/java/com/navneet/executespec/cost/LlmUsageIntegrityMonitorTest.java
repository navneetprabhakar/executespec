package com.navneet.executespec.cost;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navneet.executespec.observability.Metrics;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class LlmUsageIntegrityMonitorTest {

  @Test
  void publishesCurrentMissingTerminalCount() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    Metrics metrics = mock(Metrics.class);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(3L);
    LlmUsageIntegrityMonitor monitor = new LlmUsageIntegrityMonitor(jdbc, metrics);
    ReflectionTestUtils.setField(monitor, "staleAfterSeconds", 300L);

    monitor.measureStaleIntents();

    verify(metrics).recordStaleLlmUsageIntents(3L);
  }

  @Test
  void reportsMonitorFailureWithoutMaskingScheduler() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    Metrics metrics = mock(Metrics.class);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any()))
        .thenThrow(new IllegalStateException("database unavailable"));
    LlmUsageIntegrityMonitor monitor = new LlmUsageIntegrityMonitor(jdbc, metrics);
    ReflectionTestUtils.setField(monitor, "staleAfterSeconds", 300L);

    monitor.measureStaleIntents();

    verify(metrics).recordLlmUsagePersistence("INTEGRITY_MONITOR", "failed");
  }
}

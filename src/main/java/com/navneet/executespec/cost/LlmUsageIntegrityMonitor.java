package com.navneet.executespec.cost;

import com.navneet.executespec.observability.Metrics;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Detects provider-attempt intents that never received their immutable terminal fact. */
@Component
@RequiredArgsConstructor
@Slf4j
public class LlmUsageIntegrityMonitor {

  private final JdbcTemplate jdbc;
  private final Metrics metrics;

  @Value("${executespec.usage-stale-intent-seconds:300}")
  private long staleAfterSeconds;

  @Scheduled(
      fixedDelayString = "${executespec.usage-integrity-interval-millis:60000}",
      initialDelayString = "${executespec.usage-integrity-initial-delay-millis:60000}")
  public void measureStaleIntents() {
    try {
      Long count =
          jdbc.queryForObject(
              """
              SELECT COUNT(*)
              FROM llm_usage_attempt_intent i
              LEFT JOIN run_step_usage u ON u.physical_attempt_id = i.physical_attempt_id
              WHERE u.id IS NULL
                AND i.started_at < NOW() - (? * INTERVAL '1 second')
              """,
              Long.class,
              Math.max(1, staleAfterSeconds));
      metrics.recordStaleLlmUsageIntents(count == null ? 0 : count);
    } catch (RuntimeException failure) {
      metrics.recordLlmUsagePersistence("INTEGRITY_MONITOR", "failed");
      log.error("Unable to measure stale LLM usage intents", failure);
    }
  }
}

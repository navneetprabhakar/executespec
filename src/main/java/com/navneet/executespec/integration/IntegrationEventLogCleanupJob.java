package com.navneet.executespec.integration;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.navneet.executespec.state.repository.IntegrationEventLogRepository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Prunes integration_event_log entries older than 7 days to keep the idempotency table small. */
@Component
@ConditionalOnProperty(name = "agent.integrations.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class IntegrationEventLogCleanupJob {

  private final IntegrationEventLogRepository repo;

  @Scheduled(cron = "0 0 3 * * *") // 3 AM daily
  public void pruneOldEvents() {
    Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);
    int deleted = repo.deleteByCreatedAtBefore(cutoff);
    log.info("[Integration] Pruned {} old event log entries (older than 7 days)", deleted);
  }
}

package com.navneet.executespec.agentsession;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Starts and periodically repeats the bounded stale-stream reconciliation. */
@Component
@RequiredArgsConstructor
class AgentTurnRecoveryScheduler {

  private final AgentTurnRecoveryService recoveryService;

  @Value("${agent.turn-recovery.stale-after-seconds:90000}")
  private long staleAfterSeconds;

  @Value("${agent.turn-recovery.batch-size:100}")
  private int batchSize;

  @EventListener(ApplicationReadyEvent.class)
  public void recoverOnStartup() {
    recover();
  }

  @Scheduled(
      fixedDelayString = "${agent.turn-recovery.poll-interval-ms:300000}",
      initialDelayString = "${agent.turn-recovery.initial-delay-ms:300000}")
  public void recoverPeriodically() {
    recover();
  }

  private void recover() {
    // Operation admission permits at most a 24-hour transport/reclaim horizon. Keep an additional
    // one-hour guard before even classifying the business turn as stale.
    long boundedStaleSeconds = Math.max(90_000L, staleAfterSeconds);
    int boundedBatch = Math.max(1, Math.min(1000, batchSize));
    recoveryService.reconcile(Duration.ofSeconds(boundedStaleSeconds), boundedBatch);
  }
}

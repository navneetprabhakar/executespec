package com.navneet.executespec.integration.slack;

import com.navneet.executespec.integration.slack.model.SlackNotificationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Deletes Slack notification log entries older than 90 days.
 * Runs daily at 04:00.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty("agent.integrations.slack.enabled")
public class SlackNotificationLogCleanupJob {

    private final SlackNotificationLogRepository logRepo;

    @Scheduled(cron = "0 0 4 * * *")
    public void cleanup() {
        Instant cutoff = Instant.now().minus(90, ChronoUnit.DAYS);
        long deleted = logRepo.deleteByCreatedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("[Slack] Cleaned up {} old notification log entries", deleted);
        }
    }
}

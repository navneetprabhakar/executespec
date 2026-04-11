package com.navneet.executespec.notification;

import com.navneet.executespec.batch.BatchResumeService;
import com.navneet.executespec.notification.NotificationRepository;
import com.navneet.executespec.harness.event.PipelineEvent;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Creates in-app notifications when pipeline runs complete or fail.
 * Listens for pipeline events published by PipelineHarness.
 *
 * <p>Deduplication: PipelineHarness publishes RunCompleted/RunFailed from multiple code
 * paths (single-shot, multi-task, batch). We guard against duplicate notifications by
 * checking whether a notification for the same run link + type already exists before
 * inserting.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunCompletionNotifier {

    private final InAppNotificationService notificationService;
    private final NotificationRepository notificationRepository;
    private final StateStore stateStore;

    @Async
    @EventListener
    public void onRunCompleted(PipelineEvent.RunCompleted event) {
        try {
            PipelineRun run = stateStore.findRun(event.runId()).orElse(null);
            if (run == null || run.getCreatedByUserId() == null) return;

            boolean isBatch = "BATCH".equals(run.getApprovedExecutionMode());
            String type = isBatch ? "BATCH_COMPLETE" : "RUN_COMPLETE";
            String link = "/runs/" + event.runId();

            // Deduplicate — RunCompleted is published from multiple code paths
            if (notificationRepository.existsByLinkAndType(link, type)) {
                log.debug("[Run {}] Skipping duplicate {} notification", event.runId(), type);
                return;
            }

            String title = run.getRunTitle() != null
                    ? "Run complete: " + truncate(run.getRunTitle(), 80)
                    : "Run " + event.runId().substring(0, 8) + " completed";
            String body = run.getRunSummary() != null ? run.getRunSummary() : "Your spec has been executed successfully.";

            notificationService.create(run.getCreatedByUserId(), type, title, body, link);
            log.debug("[Run {}] Created {} notification for user {}", event.runId(), type, run.getCreatedByUserId());
        } catch (Exception e) {
            log.warn("[Run {}] Failed to create completion notification: {}", event.runId(), e.getMessage());
        }
    }

    @Async
    @EventListener
    public void onRunFailed(PipelineEvent.RunFailed event) {
        try {
            PipelineRun run = stateStore.findRun(event.runId()).orElse(null);
            if (run == null || run.getCreatedByUserId() == null) return;

            boolean isBatch = "BATCH".equals(run.getApprovedExecutionMode());
            String type = isBatch ? "BATCH_FAILED" : "RUN_FAILED";
            String link = "/runs/" + event.runId();

            // Deduplicate — RunFailed can be published from multiple recovery paths
            if (notificationRepository.existsByLinkAndType(link, type)) {
                log.debug("[Run {}] Skipping duplicate {} notification", event.runId(), type);
                return;
            }

            String title = run.getRunTitle() != null
                    ? "Run failed: " + truncate(run.getRunTitle(), 80)
                    : "Run " + event.runId().substring(0, 8) + " failed";
            String body = event.reason() != null ? truncate(event.reason(), 500) : "The run could not be completed.";

            notificationService.create(run.getCreatedByUserId(), type, title, body, link);
            log.debug("[Run {}] Created {} notification for user {}", event.runId(), type, run.getCreatedByUserId());
        } catch (Exception e) {
            log.warn("[Run {}] Failed to create failure notification: {}", event.runId(), e.getMessage());
        }
    }

    private static String truncate(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen - 3) + "..." : s;
    }
}

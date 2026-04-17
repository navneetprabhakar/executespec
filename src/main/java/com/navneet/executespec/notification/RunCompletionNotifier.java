package com.navneet.executespec.notification;

import com.navneet.executespec.harness.event.PipelineEvent;
import com.navneet.executespec.harness.event.QuotaEvent;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Creates in-app notifications when pipeline runs complete, fail, need approval,
 * or when a user's quota crosses the warning threshold.
 *
 * <p>Deduplication: RunCompleted/RunFailed are published from multiple code paths
 * (single-shot, multi-task, batch). We guard against duplicate notifications by
 * checking whether a notification for the same run link + type already exists before
 * inserting. {@code QuotaWarning} uses a synthetic per-user link + unique type so
 * the same one-shot guard applies without colliding with run notifications.
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

    /**
     * A plan-approval / clarification gate has opened. Send a nudge so the user
     * doesn't miss that their run is blocked.
     */
    @Async
    @EventListener
    public void onGateOpened(PipelineEvent.GateOpened event) {
        try {
            PipelineRun run = stateStore.findRun(event.runId()).orElse(null);
            if (run == null || run.getCreatedByUserId() == null) return;

            String type = "GATE_OPENED_" + event.gateType();
            String link = "/runs/" + event.runId();

            if (notificationRepository.existsByLinkAndType(link, type)) {
                log.debug("[Run {}] Skipping duplicate {} notification", event.runId(), type);
                return;
            }

            String runLabel = run.getRunTitle() != null
                    ? truncate(run.getRunTitle(), 80)
                    : event.runId().substring(0, 8);
            String title;
            String body;
            if ("PLAN_APPROVAL".equals(event.gateType())) {
                title = "Plan ready to approve: " + runLabel;
                body = event.credits() > 1
                        ? "Review the plan and approve to spend " + event.credits() + " credits."
                        : "Review the plan and approve to start building.";
            } else if ("CLARIFICATION".equals(event.gateType())) {
                title = "Needs your input: " + runLabel;
                body = "The planner has a question before it can proceed.";
            } else {
                title = "Action needed: " + runLabel;
                body = "Your run is waiting on you (" + event.gateType() + ").";
            }

            notificationService.create(run.getCreatedByUserId(), type, title, body, link);
            log.debug("[Run {}] Created {} notification for user {}", event.runId(), type, run.getCreatedByUserId());
        } catch (Exception e) {
            log.warn("[Run {}] Failed to create gate-opened notification: {}", event.runId(), e.getMessage());
        }
    }

    /**
     * Quota crossed 80% — nudge the user to upgrade / top-up before they hit 429.
     * One-shot per billing cycle: the deduplication key embeds the month so a
     * fresh cycle re-emits after {@link com.navneet.executespec.auth.service.MonthlyRunCounterResetJob}
     * resets the counter.
     */
    @Async
    @EventListener
    public void onQuotaWarning(QuotaEvent.QuotaWarning event) {
        try {
            String monthKey = java.time.YearMonth.now().toString(); // e.g. 2026-04
            String type = "QUOTA_WARNING_" + monthKey;
            // Scope link to org when present so ORG_ADMINs and members each get their own
            // notification; otherwise user-scoped.
            String link = event.orgId() != null
                    ? "/billing?org=" + event.orgId()
                    : "/billing";

            // Per-user dedupe — key includes userId via the existence check path.
            // Since InAppNotificationService.create is user-scoped and
            // existsByLinkAndType is global, we embed userId in the type suffix to
            // get per-user one-shot behaviour without changing the repository API.
            String dedupType = type + ":" + event.userId();
            if (notificationRepository.existsByLinkAndType(link, dedupType)) {
                log.debug("[User {}] Skipping duplicate quota-warning notification", event.userId());
                return;
            }

            String title = "Approaching run limit — " + event.percentUsed() + "% used";
            String body = "You've used " + event.current() + " of " + event.limit()
                    + " runs this cycle. Upgrade your plan or buy a top-up to keep building.";

            notificationService.create(event.userId(), dedupType, title, body, link);
            log.debug("[User {}] Created quota-warning notification at {}% ({}/{})",
                    event.userId(), event.percentUsed(), event.current(), event.limit());
        } catch (Exception e) {
            log.warn("[User {}] Failed to create quota-warning notification: {}",
                    event.userId(), e.getMessage());
        }
    }

    private static String truncate(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen - 3) + "..." : s;
    }
}

package com.navneet.executespec.notification;

import com.navneet.executespec.harness.event.PipelineEvent;
import com.navneet.executespec.harness.event.QuotaEvent;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Creates in-app notifications when pipeline runs complete, fail, need approval, or when a user's
 * quota crosses the warning threshold.
 *
 * <p>Deduplication: RunCompleted/RunFailed are published from multiple code paths (single-shot,
 * multi-task, batch). We guard against duplicate notifications by checking whether a notification
 * for the same run link + type already exists before inserting. {@code QuotaWarning} uses a
 * synthetic per-user link + unique type so the same one-shot guard applies without colliding with
 * run notifications.
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

      String title = "Run complete: " + pickTitle(run);
      // Phase 1.3 #31 — notification body upgrade. Lead with the four
      // signals the user actually cares about (verdict + cost +
      // duration + PR link if any), then the run summary as fallback.
      String body = buildRichBody(run, event.runId());

      notificationService.create(run.getCreatedByUserId(), type, title, body, link);
      log.debug(
          "[Run {}] Created {} notification for user {}",
          event.runId(),
          type,
          run.getCreatedByUserId());
    } catch (Exception e) {
      log.warn(
          "[Run {}] Failed to create completion notification: {}", event.runId(), e.getMessage());
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

      String title = "Run failed: " + pickTitle(run);
      // M-053 + M-054 (Backlog) — failure notifications get the same
      // rich-body treatment as completions: lead with the cost/duration
      // signals, then include the failure reason. Previously the body
      // was just the reason string — useful but missing the "$0.42 ·
      // 4m12s" signal that helps Sara/Raj/Marcus decide whether to
      // retry immediately or batch the fix.
      String body = buildRichFailureBody(run, event.reason());

      notificationService.create(run.getCreatedByUserId(), type, title, body, link);
      log.debug(
          "[Run {}] Created {} notification for user {}",
          event.runId(),
          type,
          run.getCreatedByUserId());
    } catch (Exception e) {
      log.warn("[Run {}] Failed to create failure notification: {}", event.runId(), e.getMessage());
    }
  }

  /**
   * M-053/M-054 — failure body mirroring buildRichBody: leading cost/duration signals, then the
   * operator-friendly reason. Returns the bare reason if the run never started accumulating cost
   * (early-stage failures) so we don't render "$0.00 · 0s" noise.
   */
  private String buildRichFailureBody(PipelineRun run, String reason) {
    StringBuilder parts = new StringBuilder();
    Double cost = run.getActualCostUsd();
    if (cost != null && cost > 0) {
      parts.append(String.format("$%.2f", cost));
    }
    if (run.getStartedAt() != null && run.getCompletedAt() != null) {
      long secs = java.time.Duration.between(run.getStartedAt(), run.getCompletedAt()).getSeconds();
      if (secs > 0) {
        if (parts.length() > 0) parts.append(" · ");
        if (secs < 60) parts.append(secs).append("s");
        else parts.append(secs / 60).append("m").append(String.format("%02ds", secs % 60));
      }
    }
    if (reason != null && !reason.isBlank()) {
      if (parts.length() > 0) parts.append("\n");
      parts.append(truncate(reason, 500));
    }
    if (parts.length() == 0) {
      return "The run could not be completed.";
    }
    return parts.toString();
  }

  /**
   * A plan-approval / clarification gate has opened. Send a nudge so the user doesn't miss that
   * their run is blocked.
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

      String runLabel = pickTitle(run);
      String title;
      String body;
      if ("PLAN_APPROVAL".equals(event.gateType())) {
        title = "Plan ready to approve: " + runLabel;
        body =
            event.credits() > 1
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
      log.debug(
          "[Run {}] Created {} notification for user {}",
          event.runId(),
          type,
          run.getCreatedByUserId());
    } catch (Exception e) {
      log.warn(
          "[Run {}] Failed to create gate-opened notification: {}", event.runId(), e.getMessage());
    }
  }

  /**
   * Quota crossed 80% — nudge the user to upgrade / top-up before they hit 429. One-shot per
   * billing cycle: the deduplication key embeds the month so a fresh cycle re-emits after {@link
   * com.navneet.executespec.auth.service.MonthlyRunCounterResetJob} resets the counter.
   */
  @Async
  @EventListener
  public void onQuotaWarning(QuotaEvent.QuotaWarning event) {
    try {
      String monthKey = java.time.YearMonth.now().toString(); // e.g. 2026-04
      String type = "QUOTA_WARNING_" + monthKey;
      // Scope link to org when present so ORG_ADMINs and members each get their own
      // notification; otherwise user-scoped.
      String link = event.orgId() != null ? "/billing?org=" + event.orgId() : "/billing";

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
      String body =
          "You've used "
              + event.current()
              + " of "
              + event.limit()
              + " runs this cycle. Upgrade your plan or buy a top-up to keep building.";

      notificationService.create(event.userId(), dedupType, title, body, link);
      log.debug(
          "[User {}] Created quota-warning notification at {}% ({}/{})",
          event.userId(), event.percentUsed(), event.current(), event.limit());
    } catch (Exception e) {
      log.warn(
          "[User {}] Failed to create quota-warning notification: {}",
          event.userId(),
          e.getMessage());
    }
  }

  /**
   * Phase 1.3 #31 — rich notification body. "$0.42 · 4m12s" + summary. Verified-score segment
   * skipped here because verifiedScore lives on the validation_report row (async, may not exist
   * when this fires); the StatusBadge / VerdictPill in the UI surface verdict elsewhere.
   */
  private String buildRichBody(PipelineRun run, String runId) {
    StringBuilder parts = new StringBuilder();
    Double cost = run.getActualCostUsd();
    if (cost != null && cost > 0) {
      parts.append(String.format("$%.2f", cost));
    }
    if (run.getStartedAt() != null && run.getCompletedAt() != null) {
      long secs = java.time.Duration.between(run.getStartedAt(), run.getCompletedAt()).getSeconds();
      if (secs > 0) {
        if (parts.length() > 0) parts.append(" · ");
        if (secs < 60) parts.append(secs).append("s");
        else parts.append(secs / 60).append("m").append(String.format("%02ds", secs % 60));
      }
    }
    String summary = run.getRunSummary();
    if (summary != null && !summary.isBlank()) {
      if (parts.length() > 0) parts.append("\n");
      parts.append(truncate(summary, 280));
    }
    if (parts.length() == 0) {
      return "Your spec has been executed successfully.";
    }
    return parts.toString();
  }

  private static String truncate(String s, int maxLen) {
    return s.length() > maxLen ? s.substring(0, maxLen - 3) + "..." : s;
  }

  /**
   * Title for run-lifecycle notifications. Picks the best available text in order: (1)
   * planner-generated {@code runTitle}, unless it looks like an LLM refusal; (2) the first N
   * characters of the original user request; (3) a generic "Run {shortId}" string. Closes
   * adversarial-test-report Finding 12 — run titles like "I need more context. What task would you
   * like..." no longer leak into the notification bell.
   */
  static String pickTitle(PipelineRun run) {
    String title = run.getRunTitle();
    if (title != null && !title.isBlank() && !isLlmRefusal(title)) {
      return truncate(title, 80);
    }
    String req = run.getUserRequest();
    if (req != null && !req.isBlank()) {
      // Use first line, stripped, capped at 80 chars.
      String firstLine = req.split("\\R", 2)[0].strip();
      if (!firstLine.isBlank()) return truncate(firstLine, 80);
    }
    return "Run " + (run.getRunId() == null ? "?" : run.getRunId().substring(0, 8));
  }

  private static boolean isLlmRefusal(String text) {
    String lower = text.toLowerCase().strip();
    return lower.startsWith("i need more context")
        || lower.startsWith("i can't ")
        || lower.startsWith("i cannot ")
        || lower.startsWith("i don't have enough")
        || lower.startsWith("please provide")
        || lower.startsWith("could you clarify")
        || lower.startsWith("sorry, ")
        || lower.contains("what task would you like");
  }
}

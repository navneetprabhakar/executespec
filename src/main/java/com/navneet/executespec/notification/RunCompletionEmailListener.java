package com.navneet.executespec.notification;

import com.navneet.executespec.activity.ActivityEventType;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.service.MailService;
import com.navneet.executespec.harness.event.PipelineEvent;
import com.navneet.executespec.notification.pref.NotificationPrefService;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * PR-O (2026-05-13) — listens for run-terminal events and emails the submitting user a
 * GitHub-CI-style summary. Only the user who created the run gets the email (not all org members)
 * per the user's explicit ask ("only to user who submitted the run").
 *
 * <p>Wired via Spring's event bus, decoupled from {@code RunOrchestrator} + {@code StateStore}. The
 * dispatcher itself is {@code @Async} so a slow SMTP server never blocks the run-completion code
 * path.
 *
 * <p>Honours the per-user notification preference matrix: a user who turned off RUN_COMPLETED for
 * the EMAIL channel won't get the success email. RUN_FAILED is defaulted-on; users can opt out of
 * failure emails too if they prefer.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RunCompletionEmailListener {

  private final PipelineRunRepository runRepo;
  private final AppUserRepository userRepo;
  private final ValidationReportRepository validationReportRepo;
  private final MailService mailService;
  private final NotificationPrefService prefService;

  @Value("${app.mail.base-url:http://localhost:5173}")
  private String baseUrl;

  @Async
  @EventListener
  public void onRunCompleted(PipelineEvent.RunCompleted event) {
    dispatch(event.runId(), null, ActivityEventType.RUN_COMPLETED);
  }

  @Async
  @EventListener
  public void onRunFailed(PipelineEvent.RunFailed event) {
    dispatch(event.runId(), event.reason(), ActivityEventType.RUN_FAILED);
  }

  /**
   * Common dispatch path. We fetch every field the template needs in one go to keep the
   * EventListener method idempotent + cheap; the only round-trips are runRepo + userRepo +
   * validationReportRepo.
   */
  private void dispatch(String runId, String failureReason, ActivityEventType eventType) {
    try {
      PipelineRun run = runRepo.findById(runId).orElse(null);
      if (run == null) {
        log.warn("RunCompletionEmail: run {} not found, skipping", runId);
        return;
      }
      String submitterId = run.getCreatedByUserId();
      if (submitterId == null) {
        // Anonymous run (free-tier sandbox, pre-ownership rows) — no one to email.
        return;
      }

      // Preference gate. The user can turn this off in the notification matrix.
      if (!prefService.shouldNotify(
          submitterId, eventType, NotificationPrefService.Channel.EMAIL)) {
        return;
      }

      AppUser user = userRepo.findById(submitterId).orElse(null);
      if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
        log.warn(
            "RunCompletionEmail: submitter {} for run {} has no email, skipping",
            submitterId,
            runId);
        return;
      }

      // verifiedScore lives on validation_report; null while scoring is skipped (cancelled before
      // reviewer or pre-v2.5.10 row).
      Double verifiedScore =
          validationReportRepo
              .findByRunId(runId)
              .map(r -> r.getVerifiedScore() != null ? r.getVerifiedScore().doubleValue() : null)
              .orElse(null);

      String runUrl = baseUrl + "/runs/" + runId;
      String firstName =
          user.getFirstName() != null && !user.getFirstName().isBlank()
              ? user.getFirstName()
              : "there";
      // statusReason explains the why behind PARTIAL/FAILED; fall back to failureReason on the
      // event (RunFailed carries a reason) when status_reason isn't populated.
      String statusReason =
          run.getStatusReason() != null && !run.getStatusReason().isBlank()
              ? run.getStatusReason()
              : failureReason;
      // Prefer the SCM-agnostic PR URL; legacy rows fall back to githubPrUrl.
      String prUrl = run.getScmPrUrl() != null ? run.getScmPrUrl() : run.getGithubPrUrl();

      mailService.sendRunCompletionEmail(
          user.getEmail(),
          firstName,
          runId,
          run.getStatus(),
          statusReason,
          run.getRunTitle(),
          verifiedScore,
          run.getActualCostUsd(),
          prUrl,
          runUrl);
      log.info(
          "Run completion email dispatched runId={} status={} to={}",
          runId,
          run.getStatus(),
          user.getEmail());
    } catch (Exception e) {
      // Swallow — email failure must never disrupt the pipeline finalization path.
      log.warn(
          "Run completion email dispatch failed runId={} eventType={}: {}",
          runId,
          eventType,
          e.getMessage());
    }
  }
}

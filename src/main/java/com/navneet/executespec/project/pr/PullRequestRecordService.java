package com.navneet.executespec.project.pr;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.notification.InAppNotificationService;
import com.navneet.executespec.notification.NotificationRepository;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class PullRequestRecordService {

  private final PullRequestRecordRepository repo;
  // v2.5.12 — needed for PR-merged in-app notifications.
  private final InAppNotificationService notificationService;
  private final NotificationRepository notificationRepository;
  private final PipelineRunRepository pipelineRunRepo;

  /** Wave 2.1 — activation funnel PR_MERGED step. */
  private final com.navneet.executespec.analytics.EventTrackingService eventTracking;

  /**
   * V82 contract — record a PR opened by a per-run code push. Defaults kind to {@link
   * PullRequestRecord.Kind#RUN} and leaves projectId null (the run row carries it).
   */
  @Transactional
  public PullRequestRecord recordCreated(
      String runId,
      UUID repoId,
      String repoFullName,
      String prUrl,
      String headBranch,
      String baseBranch) {
    return recordCreated(
        runId,
        null,
        repoId,
        repoFullName,
        prUrl,
        headBranch,
        baseBranch,
        PullRequestRecord.Kind.RUN);
  }

  /**
   * V169 (2026-05-02) — full constructor, used by system flows that aren't tied to a single run
   * (BootstrapService, ProjectArchitectureService, contract writers, project-chat tools). Pass
   * {@code runId=null} for system PRs and a non-null {@code projectId} so the project dashboard
   * surfaces the PR.
   */
  @Transactional
  public PullRequestRecord recordCreated(
      String runId,
      String projectId,
      UUID repoId,
      String repoFullName,
      String prUrl,
      String headBranch,
      String baseBranch,
      PullRequestRecord.Kind kind) {
    Integer number = extractPrNumber(prUrl);
    if (number == null) {
      log.warn("Cannot extract PR number from URL {} — skipping record", prUrl);
      return null;
    }
    Optional<PullRequestRecord> existing = repo.findByRepoFullNameAndPrNumber(repoFullName, number);
    if (existing.isPresent()) {
      log.debug("PullRequestRecord already exists for {}#{}", repoFullName, number);
      return existing.get();
    }
    PullRequestRecord record =
        PullRequestRecord.builder()
            .runId(runId)
            .projectId(projectId)
            .kind(kind == null ? PullRequestRecord.Kind.RUN : kind)
            .repoId(repoId)
            .repoFullName(repoFullName)
            .prNumber(number)
            .prUrl(prUrl)
            .headBranch(headBranch)
            .baseBranch(baseBranch)
            .status(PullRequestRecord.Status.OPEN)
            .build();
    return repo.save(record);
  }

  /**
   * Flip a stored PR to CLOSED or MERGED in response to a GitHub webhook. No-op when no matching
   * record exists (PR was not created by ExecuteSpec).
   *
   * <p>v2.5.12 — also creates an in-app notification for the run owner when the PR is merged.
   * Closes the deep-dive-audit gap where PR-merged events silently happened on GitHub but the user
   * only learned about them by refreshing the project dashboard.
   */
  @Transactional
  public void applyWebhookUpdate(
      String repoFullName, Integer prNumber, boolean merged, Instant timestamp) {
    Optional<PullRequestRecord> found = repo.findByRepoFullNameAndPrNumber(repoFullName, prNumber);
    if (found.isEmpty()) {
      log.debug("Webhook for untracked PR {}#{} — ignoring", repoFullName, prNumber);
      return;
    }
    PullRequestRecord r = found.get();
    boolean wasNotMerged = r.getStatus() != PullRequestRecord.Status.MERGED;
    if (merged) {
      r.setStatus(PullRequestRecord.Status.MERGED);
      r.setMergedAt(timestamp != null ? timestamp : Instant.now());
    } else {
      r.setStatus(PullRequestRecord.Status.CLOSED);
      r.setClosedAt(timestamp != null ? timestamp : Instant.now());
    }
    r.setUpdatedAt(Instant.now());
    repo.save(r);
    log.info("PR {}#{} updated via webhook -> {}", repoFullName, prNumber, r.getStatus());

    // v2.5.12 — fire a notification for the run owner on the merged transition.
    if (merged && wasNotMerged && r.getRunId() != null) {
      try {
        notifyMerged(r);
      } catch (Exception e) {
        // Non-fatal: notification failure must not undo the PR state update.
        log.warn(
            "Failed to create PR_MERGED notification for run {} PR {}#{}: {}",
            r.getRunId(),
            repoFullName,
            prNumber,
            e.getMessage());
      }
      // Wave 2.1 — activation funnel step 6. The run owner's first-ever
      // merged PR is the clearest "the AI shipped my code" signal.
      try {
        PipelineRun run = pipelineRunRepo.findById(r.getRunId()).orElse(null);
        if (run != null && run.getCreatedByUserId() != null) {
          eventTracking.track(
              run.getCreatedByUserId(),
              com.navneet.executespec.analytics.EventName.PR_MERGED,
              java.util.Map.of(
                  "repoFullName", repoFullName, "runId", r.getRunId() == null ? "" : r.getRunId()));
        }
      } catch (Exception e) {
        log.warn(
            "Activation track PR_MERGED failed for PR {}#{}: {}",
            repoFullName,
            prNumber,
            e.getMessage());
      }
    }
  }

  private void notifyMerged(PullRequestRecord r) {
    // Look up the run owner from the PipelineRun row. PullRequestRecord
    // doesn't carry userId so this is a single SELECT keyed on runId.
    PipelineRun run = pipelineRunRepo.findById(r.getRunId()).orElse(null);
    if (run == null || run.getCreatedByUserId() == null) return;
    String type = "PR_MERGED";
    String link = "/runs/" + r.getRunId();
    // Dedup: at most one PR_MERGED notification per (run, repo, prNumber).
    String dedupTitle = "PR merged: " + r.getRepoFullName() + " #" + r.getPrNumber();
    if (notificationRepository.existsByLinkAndType(link, type)) {
      // Loose dedup; if the user already got a PR_MERGED for this run it's fine.
      log.debug("Skipping duplicate PR_MERGED notification for run {}", r.getRunId());
      return;
    }
    String body = "Your pull request was merged. View the run for the full code + report.";
    notificationService.create(run.getCreatedByUserId(), type, dedupTitle, body, link);
  }

  public List<PullRequestRecord> listForRun(String runId) {
    return repo.findByRunIdOrderByCreatedAtAsc(runId);
  }

  /** Extracts N from URLs like {@code https://github.com/owner/repo/pull/42}. */
  public static Integer extractPrNumber(String prUrl) {
    if (prUrl == null) return null;
    int idx = prUrl.lastIndexOf("/pull/");
    if (idx < 0) return null;
    String tail = prUrl.substring(idx + 6);
    int slash = tail.indexOf('/');
    String num = slash > 0 ? tail.substring(0, slash) : tail;
    try {
      return Integer.parseInt(num);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}

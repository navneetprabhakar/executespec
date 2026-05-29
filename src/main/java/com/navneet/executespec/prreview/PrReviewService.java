package com.navneet.executespec.prreview;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.prreview.entity.PrReview;
import com.navneet.executespec.prreview.entity.PrReviewComment;
import com.navneet.executespec.prreview.repository.PrReviewCommentRepository;
import com.navneet.executespec.prreview.repository.PrReviewRepository;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * PR Review orchestrator — owns the lifecycle of a single review record.
 *
 * <p>Flow:
 *
 * <ol>
 *   <li>Tier-gate: caller must be PRO / TEAM / MAX / ENTERPRISE; FREE blocked.
 *   <li>Quota-gate: per-tier monthly cap (PRO=10, TEAM/MAX/ENTERPRISE=unlimited).
 *   <li>Idempotency: if a review for (repo, pr, head_sha) already exists in COMPLETE status, return
 *       the existing row instead of re-running.
 *   <li>Persist a row in {@code QUEUED} status.
 *   <li>Submit the actual review work to a small dedicated executor pool — async, doesn't block the
 *       controller. Status flips QUEUED → RUNNING → COMPLETE / FAILED with SSE events at each
 *       transition.
 *   <li>Agent run via {@link PrReviewAgent#review}; comments persisted in batch.
 *   <li>Tokens + cost attributed via {@link RunCostTracker} snapshot diff.
 * </ol>
 *
 * <p>SSE channel used: {@code pr-review-{prReviewId}}. The controller's {@code GET /events}
 * endpoint subscribes to this channel for live progress.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PrReviewService {

  private final PrReviewRepository reviewRepo;
  private final PrReviewCommentRepository commentRepo;
  private final PrReviewAgent agent;
  private final GitHubRepoService github;
  private final RunCostTracker costTracker;
  private final SseBroadcaster broadcaster;
  private final AppUserRepository userRepo;
  private final TierConfigRepository tierConfigRepo;

  /**
   * Dedicated executor for PR-review dispatch — sized {@code clamp(4, 12, cores × 2)}. Scoped
   * narrow because the agent path is a single LLM call (~10-90s) and we want the controller to
   * return 202 immediately. The 2× multiplier reflects PR reviews being bursty (multiple reviewers
   * click at once after a CI run); a 4-core dev VM still gets 8 threads, a small CI agent gets 4.
   * Drains on @PreDestroy so a JVM shutdown doesn't lose in-flight reviews silently.
   */
  private final ExecutorService pool =
      Executors.newFixedThreadPool(
          com.navneet.executespec.util.PoolSizing.coreMultiple(2, 4, 12),
          r -> {
            Thread t = new Thread(r, "pr-review-dispatch");
            t.setDaemon(true);
            return t;
          });

  @PreDestroy
  void shutdown() {
    pool.shutdown();
    log.info("PrReviewService dispatch pool shutdown signaled");
  }

  /**
   * Validates that the supplied user is on a paid tier with the {@code pr_review_enabled} feature
   * flag flipped on AND has remaining monthly quota. Throws on rejection so the caller (HTTP
   * controller) can map to 403/429 cleanly.
   */
  public void assertTierAndQuota(AppUser user) {
    if (user == null) throw new TierBlockedException("authentication required");
    String tier = user.getTier();
    if (tier == null || "FREE".equalsIgnoreCase(tier)) {
      throw new TierBlockedException(
          "PR review is a paid feature — upgrade to PRO or above to enable.");
    }
    TierConfig cfg = tierConfigRepo.findById(tier).orElse(null);
    if (cfg == null || !Boolean.TRUE.equals(cfg.getPrReviewEnabled())) {
      throw new TierBlockedException(
          "PR review not enabled for tier " + tier + " — contact support if this is wrong.");
    }
    int quota = cfg.getPrReviewMonthlyQuota() == null ? 0 : cfg.getPrReviewMonthlyQuota();
    if (quota >= 0) {
      // -1 = unlimited; 0 = blocked; positive = capped.
      Instant monthStart =
          Instant.now()
              .atZone(ZoneOffset.UTC)
              .toLocalDate()
              .withDayOfMonth(1)
              .atStartOfDay()
              .toInstant(ZoneOffset.UTC);
      long usedThisMonth = reviewRepo.countByUserSince(user.getId(), monthStart);
      if (usedThisMonth >= quota) {
        throw new QuotaExhaustedException(
            "Monthly PR-review quota exhausted ("
                + usedThisMonth
                + "/"
                + quota
                + "). Resets on the 1st UTC.");
      }
    }
  }

  /**
   * Submit a PR review request. Idempotent across (repo, pr, head_sha): if a COMPLETE review
   * already exists for that triple it's returned as-is; if a RUNNING review exists, the existing
   * one is returned so the caller can subscribe to its progress.
   *
   * @param projectId project the PR belongs to (for dashboard scoping)
   * @param repoFullName e.g. "pplai-app/pplai-api"
   * @param prNumber PR number
   * @param user the requesting user (must be paid-tier; tier check fires before this is called)
   * @return persisted {@link PrReview} row in QUEUED / RUNNING / COMPLETE state
   */
  @Transactional
  public PrReview submitReview(String projectId, String repoFullName, int prNumber, AppUser user) {
    String pat = user == null ? null : user.getGithubPat();
    GitHubRepoService.PullRequestSnapshot snap =
        github.getPullRequestSnapshot(repoFullName, prNumber, pat, 200_000);
    if (snap == null) {
      throw new IllegalStateException(
          "Couldn't fetch PR " + repoFullName + "#" + prNumber + " from GitHub (auth or 404).");
    }
    String headSha = snap.headSha();
    if (headSha == null || headSha.isBlank()) {
      // Fall back to a synthetic SHA marker so we still persist a row but the cross-SHA dedup
      // doesn't confuse it with a different commit.
      headSha = "unknown-" + Instant.now().toEpochMilli();
    }

    // Idempotency — if a recent review exists for this exact head SHA, return it.
    var existing =
        reviewRepo.findByRepoFullNameAndPrNumberAndHeadSha(repoFullName, prNumber, headSha);
    if (existing.isPresent()) {
      PrReview r = existing.get();
      if ("COMPLETE".equals(r.getStatus()) || "RUNNING".equals(r.getStatus())) {
        log.info(
            "PrReview idempotent re-use review={} status={} for repo={} pr=#{} sha={}",
            r.getId(),
            r.getStatus(),
            repoFullName,
            prNumber,
            headSha);
        return r;
      }
      // FAILED or QUEUED but never picked up — re-queue this row.
    }

    final String headShaForBuilder = headSha;
    PrReview row =
        existing.orElseGet(
            () ->
                PrReview.builder()
                    .projectId(projectId)
                    .repoFullName(repoFullName)
                    .prNumber(prNumber)
                    .headSha(headShaForBuilder)
                    .build());
    row.setRepoFullName(repoFullName);
    row.setPrNumber(prNumber);
    row.setHeadSha(headSha);
    row.setProjectId(projectId);
    row.setStatus("QUEUED");
    row.setStartedAt(Instant.now());
    row.setFinishedAt(null);
    row.setFailureReason(null);
    row.setCreatedByUserId(user == null ? null : user.getId());
    PrReview saved = reviewRepo.save(row);

    String channel = "pr-review-" + saved.getId();
    broadcaster.emit(channel, RunEvent.runStatus(channel, "QUEUED"));

    final UUID reviewId = saved.getId();
    final GitHubRepoService.PullRequestSnapshot snapshotForDispatch = snap;
    final String headShaForDispatch = headSha;
    pool.submit(
        () -> {
          try {
            runReview(
                reviewId,
                projectId,
                repoFullName,
                prNumber,
                headShaForDispatch,
                snapshotForDispatch);
          } catch (Throwable t) {
            // Per-row safety net — never let the dispatch thread die silently.
            log.error("PrReview dispatch threw for review={}", reviewId, t);
            failReview(reviewId, "dispatch error: " + t.getMessage());
          }
        });
    return saved;
  }

  /**
   * Background runner — invoked from {@link #submitReview} on the dispatch pool. Owns the QUEUED →
   * RUNNING → COMPLETE/FAILED transitions + persists comments.
   */
  void runReview(
      UUID reviewId,
      String projectId,
      String repoFullName,
      int prNumber,
      String headSha,
      GitHubRepoService.PullRequestSnapshot snap) {
    String channel = "pr-review-" + reviewId;
    long start = System.currentTimeMillis();

    markRunning(reviewId);
    broadcaster.emit(channel, RunEvent.runStatus(channel, "RUNNING"));
    broadcaster.emit(
        channel,
        RunEvent.log(
            channel,
            "review",
            "Reviewing "
                + repoFullName
                + " PR #"
                + prNumber
                + " ("
                + (snap == null ? "?" : snap.changedFiles())
                + " files, +"
                + (snap == null ? 0 : snap.additions())
                + "/-"
                + (snap == null ? 0 : snap.deletions())
                + ")"));

    PrReview row = reviewRepo.findById(reviewId).orElse(null);
    if (row == null) {
      log.warn("PrReview row {} vanished mid-dispatch", reviewId);
      return;
    }
    String aiProvider = "anthropic"; // default for now; PROJECT-aware override in slice 3
    PrReviewAgent.AgentRequest agentReq =
        PrReviewAgent.AgentRequest.builder()
            .prReviewId(reviewId.toString())
            .repoFullName(repoFullName)
            .prNumber(prNumber)
            .headSha(headSha)
            .baseBranch(snap == null ? "" : snap.baseBranch())
            .headBranch(snap == null ? "" : snap.headBranch())
            .title(snap == null ? "" : snap.title())
            .body(snap == null ? "" : snap.body())
            .changedFiles(snap == null ? 0 : snap.changedFiles())
            .additions(snap == null ? 0 : snap.additions())
            .deletions(snap == null ? 0 : snap.deletions())
            .diff(snap == null ? "" : snap.diffPreview())
            .provider(aiProvider)
            .build();

    final PrReviewAgent.AgentReview result;
    try {
      result = agent.review(agentReq);
    } catch (Exception e) {
      log.warn("PrReview agent failed review={}: {}", reviewId, e.getMessage());
      failReview(reviewId, "agent: " + e.getMessage());
      broadcaster.emit(
          channel, RunEvent.log(channel, "review", "❌ Agent failed: " + e.getMessage()));
      broadcaster.emit(channel, RunEvent.complete(channel, "FAILED", "agent failed"));
      return;
    }

    // Persist comments + cost in one transaction.
    completeReview(reviewId, result, System.currentTimeMillis() - start);
    broadcaster.emit(
        channel,
        RunEvent.log(
            channel,
            "review",
            "✅ Verdict: " + result.verdict() + " · " + result.comments().size() + " comments"));
    broadcaster.emit(channel, RunEvent.complete(channel, "COMPLETE", result.verdict()));
  }

  @Transactional
  void markRunning(UUID reviewId) {
    reviewRepo
        .findById(reviewId)
        .ifPresent(
            r -> {
              r.setStatus("RUNNING");
              reviewRepo.save(r);
            });
  }

  @Transactional
  void completeReview(UUID reviewId, PrReviewAgent.AgentReview result, long durationMs) {
    PrReview row = reviewRepo.findById(reviewId).orElse(null);
    if (row == null) return;
    row.setStatus("COMPLETE");
    row.setVerdict(result.verdict());
    row.setSummary(result.summary());
    row.setDurationMs(durationMs);
    row.setFinishedAt(Instant.now());
    row.setFailureReason(null);

    // Cost attribution from the per-review bucket — agent pushes into "pr-review-{id}".
    var snap = costTracker.snapshot("pr-review-" + reviewId);
    row.setCostUsd(snap.costUsd());
    row.setTotalInputTokens((long) snap.inputTokens());
    row.setTotalOutputTokens((long) snap.outputTokens());
    reviewRepo.save(row);

    // Persist comments — drop & replace pattern (cheaper than diffing for one review).
    commentRepo.deleteAll(commentRepo.findByPrReviewIdOrderByFilePathAscLineNumberAsc(reviewId));
    List<PrReviewComment> comments = new java.util.ArrayList<>();
    for (var c : result.comments()) {
      comments.add(
          PrReviewComment.builder()
              .prReviewId(reviewId)
              .filePath(c.filePath())
              .lineNumber(c.lineNumber())
              .side(c.side())
              .category(c.category())
              .severity(c.severity())
              .body(c.body())
              .build());
    }
    commentRepo.saveAll(comments);

    // Free the per-review cost bucket so it doesn't leak.
    costTracker.finalizeRun("pr-review-" + reviewId);
  }

  @Transactional
  void failReview(UUID reviewId, String reason) {
    reviewRepo
        .findById(reviewId)
        .ifPresent(
            r -> {
              r.setStatus("FAILED");
              r.setFailureReason(reason);
              r.setFinishedAt(Instant.now());
              if (r.getStartedAt() != null) {
                r.setDurationMs(ChronoUnit.MILLIS.between(r.getStartedAt(), Instant.now()));
              }
              reviewRepo.save(r);
            });
  }

  public PrReview findById(UUID id) {
    return reviewRepo.findById(id).orElse(null);
  }

  public List<PrReview> findByPr(String repoFullName, int prNumber) {
    return reviewRepo.findByRepoFullNameAndPrNumberOrderByStartedAtDesc(repoFullName, prNumber);
  }

  public List<PrReview> findByProject(String projectId) {
    return reviewRepo.findByProjectIdOrderByStartedAtDesc(projectId);
  }

  public List<PrReviewComment> commentsFor(UUID reviewId) {
    return commentRepo.findByPrReviewIdOrderByFilePathAscLineNumberAsc(reviewId);
  }

  public static class TierBlockedException extends RuntimeException {
    public TierBlockedException(String msg) {
      super(msg);
    }
  }

  public static class QuotaExhaustedException extends RuntimeException {
    public QuotaExhaustedException(String msg) {
      super(msg);
    }
  }
}

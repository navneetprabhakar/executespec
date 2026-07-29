package com.navneet.executespec.prreview;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.prreview.entity.PrReview;
import com.navneet.executespec.prreview.entity.PrReviewComment;
import com.navneet.executespec.prreview.repository.PrReviewCommentRepository;
import com.navneet.executespec.prreview.repository.PrReviewRepository;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.reviewjob.ReviewBackgroundJobStore;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ProjectProviderResolver;
import com.navneet.executespec.router.RunModelOverrides;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

/**
 * PR Review orchestrator — owns the lifecycle of a single review record.
 *
 * <p>Flow:
 *
 * <ol>
 *   <li>Tier-gate: caller must be PRO / TEAM / MAX / ENTERPRISE; FREE blocked.
 *   <li>Quota-gate: per-tier monthly cap (PRO=10, TEAM/MAX/ENTERPRISE=unlimited).
 *   <li>Idempotency: if a review for (project, immutable repo id, PR, head SHA) already exists in
 *       COMPLETE status, return the existing row instead of re-running.
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
  private final ProjectProviderResolver projectProviderResolver;
  private final ReviewBackgroundJobStore reviewJobStore;
  private final LlmExecutionPolicyFactory executionPolicyFactory;
  private final ModelResolver modelResolver;
  private final ProjectRepository projectRepository;
  private final ObjectMapper objectMapper;

  @Autowired
  public PrReviewService(
      PrReviewRepository reviewRepo,
      PrReviewCommentRepository commentRepo,
      PrReviewAgent agent,
      GitHubRepoService github,
      RunCostTracker costTracker,
      SseBroadcaster broadcaster,
      AppUserRepository userRepo,
      TierConfigRepository tierConfigRepo,
      ProjectProviderResolver projectProviderResolver,
      ReviewBackgroundJobStore reviewJobStore,
      LlmExecutionPolicyFactory executionPolicyFactory,
      ModelResolver modelResolver,
      ProjectRepository projectRepository,
      ObjectMapper objectMapper) {
    this.reviewRepo = reviewRepo;
    this.commentRepo = commentRepo;
    this.agent = agent;
    this.github = github;
    this.costTracker = costTracker;
    this.broadcaster = broadcaster;
    this.userRepo = userRepo;
    this.tierConfigRepo = tierConfigRepo;
    this.projectProviderResolver = projectProviderResolver;
    this.reviewJobStore = reviewJobStore;
    this.executionPolicyFactory = executionPolicyFactory;
    this.modelResolver = modelResolver;
    this.projectRepository = projectRepository;
    this.objectMapper = objectMapper;
  }

  /** Source-compatible constructor retained for narrow legacy unit tests. */
  PrReviewService(
      PrReviewRepository reviewRepo,
      PrReviewCommentRepository commentRepo,
      PrReviewAgent agent,
      GitHubRepoService github,
      RunCostTracker costTracker,
      SseBroadcaster broadcaster,
      AppUserRepository userRepo,
      TierConfigRepository tierConfigRepo,
      ProjectProviderResolver projectProviderResolver) {
    this(
        reviewRepo,
        commentRepo,
        agent,
        github,
        costTracker,
        broadcaster,
        userRepo,
        tierConfigRepo,
        projectProviderResolver,
        null,
        null,
        null,
        null,
        new ObjectMapper());
  }

  /** No service-owned queue remains; retained as a no-op for source compatibility. */
  void shutdown() {}

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
   * @param repoId canonical linked repository id admitted by the controller
   * @param repoFullName canonical repository full name from the durable repo row
   * @param prNumber PR number
   * @param user the requesting user (must be paid-tier; tier check fires before this is called)
   * @return persisted {@link PrReview} row in QUEUED / RUNNING / COMPLETE state
   */
  @Transactional
  public PrReview submitReview(
      String projectId, UUID repoId, String repoFullName, int prNumber, AppUser user) {
    if (repoId == null) {
      throw new IllegalArgumentException("repoId is required");
    }
    String pat = user == null ? null : user.getGithubPat();
    GitHubRepoService.PullRequestSnapshot snap =
        github.getPullRequestSnapshot(repoFullName, prNumber, pat, 200_000);
    if (snap == null) {
      throw new IllegalStateException(
          "Couldn't fetch PR " + repoFullName + "#" + prNumber + " from GitHub (auth or 404).");
    }
    String headSha = snap.headSha();
    if (headSha == null || headSha.isBlank()) {
      // RR-660 — never convert a missing head SHA into a unique "unknown-<millis>" marker. Doing so
      // made every retry a distinct billable review whose result could not be tied to immutable
      // source, yet the product presented it as review evidence for that PR. Reject until an
      // immutable commit SHA is available and record the retrieval gap separately, rather than
      // reviewing mutable/unidentified content.
      throw new IllegalStateException(
          "PR "
              + repoFullName
              + "#"
              + prNumber
              + " has no resolvable head commit SHA — retry once GitHub returns the PR head.");
    }

    // Idempotency — if a recent review exists for this exact head SHA, return it.
    var existing =
        reviewRepo.findByProjectIdAndRepoIdAndPrNumberAndHeadSha(
            projectId, repoId, prNumber, headSha);
    if (existing.isPresent()) {
      PrReview r = existing.get();
      log.info(
          "PrReview idempotent re-use review={} status={} for repo={} pr=#{} sha={}",
          r.getId(),
          r.getStatus(),
          repoFullName,
          prNumber,
          headSha);
      return r;
    }

    final String headShaForBuilder = headSha;
    PrReview row =
        existing.orElseGet(
            () ->
                PrReview.builder()
                    .projectId(projectId)
                    .repoId(repoId)
                    .repoFullName(repoFullName)
                    .prNumber(prNumber)
                    .headSha(headShaForBuilder)
                    .build());
    row.setRepoFullName(repoFullName);
    row.setRepoId(repoId);
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

    var project = projectRepository.findById(projectId).orElseThrow();
    LlmProvider provider = projectProviderResolver.resolve(projectId);
    String model = modelResolver.resolve("PrReviewAgent", provider).getModel();
    String actorUserId = user == null ? null : user.getId();
    LlmExecutionReference reference =
        executionPolicyFactory.forProject(
            projectId,
            "pr-review",
            "PR_REVIEW",
            saved.getId().toString(),
            saved.getId().toString(),
            provider.name(),
            model,
            actorUserId);
    try {
      String payload =
          objectMapper.writeValueAsString(
              Map.of(
                  "repoFullName", repoFullName,
                  "prNumber", prNumber,
                  "headSha", headSha,
                  "snapshot", snap));
      reviewJobStore.createOrGet(
          new ReviewBackgroundJobStore.Job(
              UUID.randomUUID(),
              ReviewBackgroundJobStore.Type.PR_REVIEW,
              saved.getId(),
              projectId,
              project.getOrgId(),
              repoId,
              actorUserId,
              "pr-review:" + projectId + ":" + repoId + ":" + prNumber + ":" + headSha,
              provider.name(),
              model,
              reference.contextId(),
              reference.scope().mode().name(),
              reference.scope().orgId(),
              payload,
              null,
              null,
              ReviewBackgroundJobStore.Status.QUEUED,
              0,
              1,
              Instant.now(),
              null,
              0,
              null,
              null,
              null,
              null,
              null,
              Instant.now(),
              null,
              null));
    } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
      throw new IllegalStateException("could not encode PR review job", impossible);
    }
    return saved;
  }

  /** Executes a V299 job without re-fetching a mutable PR or rebuilding its LLM route. */
  public DurablePrReviewResult runDurableJob(
      ReviewBackgroundJobStore.Lease lease,
      String repoFullName,
      int prNumber,
      String headSha,
      GitHubRepoService.PullRequestSnapshot snapshot,
      Runnable checkpoint) {
    return runDurableJob(lease, repoFullName, prNumber, headSha, snapshot, null, checkpoint);
  }

  public DurablePrReviewResult runDurableJob(
      ReviewBackgroundJobStore.Lease lease,
      String repoFullName,
      int prNumber,
      String headSha,
      GitHubRepoService.PullRequestSnapshot snapshot,
      com.navneet.executespec.tool.ExecutionContext executionContext,
      Runnable checkpoint) {
    ReviewBackgroundJobStore.Job job = lease.job();
    AppUser actor =
        userRepo
            .findById(job.requestedByUserId())
            .orElseThrow(() -> new IllegalStateException("review actor no longer exists"));
    var scope =
        new com.navneet.executespec.executioncontext.LlmExecutionScope(
            com.navneet.executespec.executioncontext.LlmExecutionScope.ScopeMode.valueOf(
                job.executionScopeMode()),
            job.executionScopeOrgId());
    LlmProvider provider = LlmProvider.valueOf(job.provider());
    java.util.concurrent.atomic.AtomicReference<DurablePrReviewResult> result =
        new java.util.concurrent.atomic.AtomicReference<>();
    RunModelOverrides.runWith(
        RunModelOverrides.withRunPin(Map.of(), provider, job.model()),
        () ->
            result.set(
                runReview(
                    lease,
                    repoFullName,
                    prNumber,
                    headSha,
                    snapshot,
                    provider,
                    job.model(),
                    actor.getId(),
                    new LlmExecutionReference(job.executionContextId(), scope),
                    executionContext,
                    checkpoint)));
    return result.get();
  }

  /**
   * Background runner — invoked from {@link #submitReview} on the dispatch pool. Owns the QUEUED →
   * RUNNING → COMPLETE/FAILED transitions + persists comments.
   */
  DurablePrReviewResult runReview(
      ReviewBackgroundJobStore.Lease lease,
      String repoFullName,
      int prNumber,
      String headSha,
      GitHubRepoService.PullRequestSnapshot snap,
      LlmProvider pinnedProvider,
      String pinnedModel,
      String actorUserId,
      LlmExecutionReference executionReference,
      com.navneet.executespec.tool.ExecutionContext executionContext,
      Runnable checkpoint) {
    UUID reviewId = lease.job().subjectId();
    String projectId = lease.job().projectId();
    String channel = "pr-review-" + reviewId;
    long start = System.currentTimeMillis();
    checkpoint.run();

    if (!reviewJobStore.updatePrReviewStatus(lease, "RUNNING")) {
      throw new ReviewBackgroundJobStore.LeaseFencedException();
    }
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
      throw new IllegalStateException("PR review row vanished mid-dispatch");
    }
    String aiProvider = pinnedProvider.name().toLowerCase();
    PrReviewAgent.AgentRequest agentReq =
        PrReviewAgent.AgentRequest.builder()
            .prReviewId(reviewId.toString())
            .projectId(projectId)
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
            .model(pinnedModel)
            .actorUserId(actorUserId)
            .executionReference(executionReference)
            .executionContext(executionContext)
            .build();

    final PrReviewAgent.AgentReview result;
    try {
      result = agent.review(agentReq);
      checkpoint.run();
    } catch (Exception e) {
      log.warn("PrReview agent failed review={}: {}", reviewId, e.getMessage());
      broadcaster.emit(
          channel, RunEvent.log(channel, "review", "❌ Agent failed: " + e.getMessage()));
      throw new IllegalStateException("agent failed", e);
    }

    return new DurablePrReviewResult(result, System.currentTimeMillis() - start);
  }

  /** Locks the V299 fence and publishes comments/result + terminal job state atomically. */
  @Transactional
  public void publishDurableResult(
      ReviewBackgroundJobStore.Lease lease, DurablePrReviewResult durableResult) {
    reviewJobStore.requireLiveLeaseForUpdate(lease);
    completeReview(
        lease.job().subjectId(), durableResult.agentReview(), durableResult.durationMs());
    if (!reviewJobStore.complete(lease)) throw new ReviewBackgroundJobStore.LeaseFencedException();
    String channel = "pr-review-" + lease.job().subjectId();
    broadcaster.emit(
        channel,
        RunEvent.log(
            channel,
            "review",
            "✅ Verdict: "
                + durableResult.agentReview().verdict()
                + " · "
                + durableResult.agentReview().comments().size()
                + " comments"));
    broadcaster.emit(
        channel, RunEvent.complete(channel, "COMPLETE", durableResult.agentReview().verdict()));
  }

  public record DurablePrReviewResult(PrReviewAgent.AgentReview agentReview, long durationMs) {}

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

  public boolean requestCancellation(UUID reviewId) {
    var job = reviewJobStore.requestCancellation(ReviewBackgroundJobStore.Type.PR_REVIEW, reviewId);
    if (job.isEmpty()
        || job.get().status().terminal()
            && job.get().status() != ReviewBackgroundJobStore.Status.CANCELLED) {
      return false;
    }
    return true;
  }

  @Transactional
  public boolean retryReview(UUID reviewId) {
    var job = reviewJobStore.retry(ReviewBackgroundJobStore.Type.PR_REVIEW, reviewId);
    if (job.isEmpty() || job.get().status() != ReviewBackgroundJobStore.Status.QUEUED) return false;
    reviewRepo
        .findById(reviewId)
        .ifPresent(
            row -> {
              row.setStatus("QUEUED");
              row.setFinishedAt(null);
              row.setFailureReason(null);
              reviewRepo.save(row);
            });
    return true;
  }

  public PrReview findById(UUID id) {
    return reviewRepo.findById(id).orElse(null);
  }

  public org.springframework.data.domain.Slice<PrReview> findByPr(
      String projectId,
      UUID repoId,
      int prNumber,
      Instant since,
      org.springframework.data.domain.Pageable pageable) {
    return reviewRepo
        .findByProjectIdAndRepoIdAndPrNumberAndStartedAtGreaterThanEqualOrderByStartedAtDescIdDesc(
            projectId, repoId, prNumber, since, pageable);
  }

  public org.springframework.data.domain.Slice<PrReview> findByProject(
      String projectId, Instant since, org.springframework.data.domain.Pageable pageable) {
    return reviewRepo.findByProjectIdAndStartedAtGreaterThanEqualOrderByStartedAtDescIdDesc(
        projectId, since, pageable);
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

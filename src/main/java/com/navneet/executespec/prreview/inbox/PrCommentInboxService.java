package com.navneet.executespec.prreview.inbox;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.navneet.executespec.project.pr.PullRequestRecord;
import com.navneet.executespec.project.pr.PullRequestRecordRepository;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service layer for the inbound PR review comment surface.
 *
 * <p>Three responsibilities:
 *
 * <ul>
 *   <li><b>Ingest</b> — called by {@link
 *       com.navneet.executespec.project.pr.GitHubWebhookController} on every {@code
 *       pull_request_review_comment.created} event. Resolves the (repoFullName, prNumber) to a
 *       runId via {@link PullRequestRecordRepository}; if no match, the comment is on a non-
 *       ExecuteSpec PR and gets dropped silently. On match, inserts a row + emits {@code
 *       PR_COMMENT_RECEIVED} SSE so the open RunDetail tab updates without a refetch.
 *   <li><b>List / count</b> — feeds the v3 RunDetail action-row chip + side-panel ChatPane
 *       pending-comments section.
 *   <li><b>Status transitions</b> — Phase P1: {@link #dismiss}. Phase P2: {@link #markAddressed}
 *       (called by {@link com.navneet.executespec.chat.ApplyService} when the apply flow
 *       completes). Both are one-way; once a comment is ADDRESSED or DISMISSED it stays that way.
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PrCommentInboxService {

  private final PrCommentInboxRepository inboxRepo;
  private final PullRequestRecordRepository prRecordRepo;
  private final SseBroadcaster broadcaster;

  /**
   * Ingest a webhook-delivered PR review comment. Returns the inserted (or existing) row, or empty
   * when the PR isn't tracked by ExecuteSpec.
   */
  @Transactional
  public Optional<PrCommentInbox> ingest(IngestRequest req) {
    Optional<PullRequestRecord> match =
        prRecordRepo.findByRepoFullNameAndPrNumber(req.repoFullName(), req.prNumber());
    if (match.isEmpty()) {
      log.debug(
          "PrCommentInbox skip — no PullRequestRecord for {}:{} (comment {})",
          req.repoFullName(),
          req.prNumber(),
          req.commentId());
      return Optional.empty();
    }
    String runId = match.get().getRunId();
    if (runId == null || runId.isBlank()) {
      log.warn(
          "PrCommentInbox skip — PullRequestRecord for {}:{} has null runId",
          req.repoFullName(),
          req.prNumber());
      return Optional.empty();
    }

    Optional<PrCommentInbox> existing =
        inboxRepo.findByRepoFullNameAndPrNumberAndCommentId(
            req.repoFullName(), req.prNumber(), req.commentId());
    if (existing.isPresent()) {
      log.debug(
          "PrCommentInbox dedup — comment {} already ingested for run {}", req.commentId(), runId);
      return existing;
    }

    PrCommentInbox row =
        PrCommentInbox.builder()
            .runId(runId)
            .repoFullName(req.repoFullName())
            .prNumber(req.prNumber())
            .commentId(req.commentId())
            .filePath(req.filePath())
            .lineNumber(req.lineNumber())
            .body(req.body() == null ? "" : req.body())
            .author(req.author() == null ? "unknown" : req.author())
            .githubUrl(req.githubUrl() == null ? "" : req.githubUrl())
            .status("PENDING")
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
    PrCommentInbox saved = inboxRepo.save(row);
    log.info(
        "PrCommentInbox ingested run={} repo={} pr=#{} comment={} author={}",
        runId,
        req.repoFullName(),
        req.prNumber(),
        req.commentId(),
        saved.getAuthor());

    // SSE: open RunDetail tab gets a live banner without a refetch round-trip.
    String preview =
        saved.getBody().length() > 80 ? saved.getBody().substring(0, 80) + "…" : saved.getBody();
    String anchor =
        saved.getFilePath() == null
            ? "PR-level"
            : saved.getFilePath()
                + (saved.getLineNumber() != null ? ":" + saved.getLineNumber() : "");
    String summary = saved.getAuthor() + "|" + anchor + "|" + preview;
    broadcaster.emit(runId, RunEvent.prCommentReceived(runId, summary));

    return Optional.of(saved);
  }

  /** Pending + Addressed + Dismissed for a run, newest first. */
  public List<PrCommentInbox> listForRun(String runId) {
    return inboxRepo.findByRunIdOrderByCreatedAtDesc(runId);
  }

  public long countPending(String runId) {
    return inboxRepo.countByRunIdAndStatus(runId, "PENDING");
  }

  /** Fetch a comment but require it to belong to the given run — defence against id-fuzzing. */
  public Optional<PrCommentInbox> findForRun(Long id, String runId) {
    return inboxRepo.findById(id).filter(c -> runId.equals(c.getRunId()));
  }

  /** User-driven dismiss. Idempotent. */
  @Transactional
  public Optional<PrCommentInbox> dismiss(Long id) {
    return inboxRepo
        .findById(id)
        .map(
            c -> {
              if (!"PENDING".equals(c.getStatus())) return c;
              c.setStatus("DISMISSED");
              c.setUpdatedAt(Instant.now());
              return inboxRepo.save(c);
            });
  }

  /**
   * Phase P2 — called by {@link com.navneet.executespec.chat.ApplyService} after the apply flow has
   * pushed a fix to the PR's branch. Stamps the new run id so the UI can deep-link from the inbox
   * row to the apply run that addressed it.
   */
  @Transactional
  public Optional<PrCommentInbox> markAddressed(Long id, String addressedByRunId) {
    return inboxRepo
        .findById(id)
        .map(
            c -> {
              c.setStatus("ADDRESSED");
              c.setAddressedRunId(addressedByRunId);
              c.setUpdatedAt(Instant.now());
              return inboxRepo.save(c);
            });
  }

  /** Webhook-payload-shaped ingest request. */
  public record IngestRequest(
      String repoFullName,
      Integer prNumber,
      Long commentId,
      String filePath,
      Integer lineNumber,
      String body,
      String author,
      String githubUrl) {}
}

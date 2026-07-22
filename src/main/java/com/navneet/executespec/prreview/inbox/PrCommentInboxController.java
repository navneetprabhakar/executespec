package com.navneet.executespec.prreview.inbox;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PreDestroy;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.chat.ApplyService;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * REST surface for the inbound PR review comment inbox.
 *
 * <ul>
 *   <li>{@code GET /api/runs/{runId}/pr-comments} — list every comment (any status) for a run.
 *   <li>{@code GET /api/runs/{runId}/pr-comments/pending-count} — count of PENDING rows; the v3
 *       RunDetail action-row chip polls this on mount + when a {@code PR_COMMENT_RECEIVED} SSE
 *       event arrives.
 *   <li>{@code POST /api/runs/{runId}/pr-comments/{id}/dismiss} — user-driven dismiss.
 * </ul>
 *
 * <p>All endpoints are guarded by {@link RunAccessGuard} so a comment leaked through the wrong
 * runId can't be surfaced to a different tenant.
 */
@RestController
@RequestMapping("/api/runs/{runId}/pr-comments")
@RequiredArgsConstructor
@Slf4j
public class PrCommentInboxController {

  private final PrCommentInboxService service;
  private final RunAccessGuard runAccessGuard;
  private final ApplyService applyService;

  /**
   * Phase P2 — small dedicated executor for the "Address with chat" dispatch. Apply runs are
   * synchronous worker loops (~30-90s); we want to return 202 to the UI immediately and let the
   * existing {@code GET /api/runs/{id}/apply/status} polling endpoint surface progress. PR-comment
   * apply throughput is much lower than chat (typically 1–5 per PR), so a 2-thread pool is enough.
   *
   * <p>RR-195 — the queue is BOUNDED and the rejection policy is AbortPolicy, so a flood of apply
   * requests cannot grow memory without limit; over-capacity work is refused with 503 and the
   * comment's claim is released for retry rather than silently queued forever.
   */
  private static final int APPLY_QUEUE_CAPACITY = 64;

  private final ThreadPoolExecutor applyDispatch =
      new ThreadPoolExecutor(
          2,
          2,
          0L,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(APPLY_QUEUE_CAPACITY),
          new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
              Thread t = new Thread(r, "pr-comment-apply-" + seq.incrementAndGet());
              t.setDaemon(true);
              return t;
            }
          },
          new ThreadPoolExecutor.AbortPolicy());

  @PreDestroy
  void shutdownApplyDispatch() {
    applyDispatch.shutdown();
    try {
      if (!applyDispatch.awaitTermination(30, TimeUnit.SECONDS)) {
        log.warn("pr-comment-apply executor did not terminate in 30s — forcing shutdown");
        applyDispatch.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      applyDispatch.shutdownNow();
    }
  }

  @GetMapping
  public List<Dto> list(@PathVariable String runId, @AuthenticationPrincipal AppUserDetails user) {
    runAccessGuard.requireRunAccess(runId, user);
    return service.listForRun(runId).stream().map(Dto::from).toList();
  }

  @GetMapping("/pending-count")
  public ResponseEntity<PendingCount> pendingCount(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails user) {
    runAccessGuard.requireRunAccess(runId, user);
    return ResponseEntity.ok(new PendingCount(service.countPending(runId)));
  }

  @PostMapping("/{commentId}/dismiss")
  public ResponseEntity<Dto> dismiss(
      @PathVariable String runId,
      @PathVariable Long commentId,
      @AuthenticationPrincipal AppUserDetails user) {
    runAccessGuard.requireRunWriteAccess(runId, user);
    return service
        .dismissForRun(commentId, runId)
        .map(Dto::from)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /**
   * Phase P2 — dispatch an {@link ApplyService} run that addresses the comment. We synthesise a
   * suggestion from the comment's body + file:line anchor + author, fire it through the existing
   * apply pipeline (which pushes to the run's branch — same branch the PR is from), and stamp the
   * inbox row as ADDRESSED. Returns 202 with the runId so the UI can poll {@code
   * /api/runs/{id}/apply/status} for live progress.
   */
  @PostMapping("/{commentId}/apply")
  public ResponseEntity<ApplyResponse> apply(
      @PathVariable String runId,
      @PathVariable Long commentId,
      @AuthenticationPrincipal AppUserDetails user) {
    runAccessGuard.requireRunWriteAccess(runId, user);
    PrCommentInbox comment =
        service
            .findForRun(commentId, runId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "comment not found"));

    // RR-195 — claim the comment atomically BEFORE enqueuing. Only the request that wins the
    // PENDING -> APPLY_QUEUED transition may dispatch, so repeated clicks/requests cannot each
    // enqueue a duplicate mutation against the same run workspace. A lost race (already claimed,
    // addressed or dismissed) is a 409.
    if (!service.claimForApply(commentId, runId)) {
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT,
          "comment already claimed or " + comment.getStatus().toLowerCase());
    }

    String suggestion = buildSuggestion(comment);
    log.info(
        "PrCommentInbox apply dispatched run={} comment={} author={}",
        runId,
        commentId,
        comment.getAuthor());
    try {
      applyDispatch.execute(
          () -> {
            try {
              applyService.apply(runId, suggestion, "PR_COMMENT");
              service.markAddressed(commentId, runId);
            } catch (Exception e) {
              log.error(
                  "PrCommentInbox apply run={} comment={} failed: {}",
                  runId,
                  commentId,
                  e.getMessage(),
                  e);
              // RR-195 — release the claim so a failed apply is retryable rather than stuck queued.
              service.releaseApplyClaim(commentId, runId);
            }
          });
    } catch (RejectedExecutionException saturated) {
      // RR-195 — bounded queue is full. Refuse with backpressure and release the claim so the
      // work is not lost; the user can retry once capacity frees.
      service.releaseApplyClaim(commentId, runId);
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "apply queue is full — retry shortly");
    }
    return ResponseEntity.accepted().body(new ApplyResponse(runId));
  }

  /**
   * Build the text we feed into {@link ApplyService}. Lead with the author + file anchor so the
   * worker has context on which file to edit; include the verbatim comment body so the worker sees
   * the reviewer's exact wording (e.g. "rename foo to bar"); include a short instruction to keep
   * the change minimal so the apply doesn't drift into unrelated refactors.
   */
  private static String buildSuggestion(PrCommentInbox c) {
    StringBuilder sb = new StringBuilder();
    sb.append("A reviewer left a comment on PR #")
        .append(c.getPrNumber())
        .append(" of ")
        .append(c.getRepoFullName())
        .append(".\n\n");
    sb.append("Author: @").append(c.getAuthor()).append("\n");
    if (c.getFilePath() != null) {
      sb.append("File: ").append(c.getFilePath());
      if (c.getLineNumber() != null) sb.append(":").append(c.getLineNumber());
      sb.append("\n");
    }
    sb.append("Comment:\n").append(c.getBody()).append("\n\n");
    sb.append(
        "Address the comment with the smallest possible change. Don't reformat unrelated code. "
            + "If the reviewer asked a question rather than requesting a change, leave a clear "
            + "code comment explaining the answer rather than rewriting the file.");
    return sb.toString();
  }

  public record Dto(
      Long id,
      String runId,
      String repoFullName,
      Integer prNumber,
      Long commentId,
      String filePath,
      Integer lineNumber,
      String body,
      String author,
      String githubUrl,
      String status,
      String addressedRunId,
      Instant createdAt) {
    public static Dto from(PrCommentInbox c) {
      return new Dto(
          c.getId(),
          c.getRunId(),
          c.getRepoFullName(),
          c.getPrNumber(),
          c.getCommentId(),
          c.getFilePath(),
          c.getLineNumber(),
          c.getBody(),
          c.getAuthor(),
          c.getGithubUrl(),
          c.getStatus(),
          c.getAddressedRunId(),
          c.getCreatedAt());
    }
  }

  public record PendingCount(long count) {}

  public record ApplyResponse(String applyRunId) {}
}

package com.navneet.executespec.prreview;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.prreview.entity.PrReview;
import com.navneet.executespec.prreview.entity.PrReviewComment;
import com.navneet.executespec.project.security.ProjectAccessGuard;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/**
 * Surface for the PR Review Agent (v2.6.4).
 *
 * <p>All endpoints require auth. {@link PrReviewService#assertTierAndQuota} enforces the paid-tier
 * gate (FREE → 403) + per-tier monthly quota (PRO=10, TEAM/MAX/ENTERPRISE=unlimited; quota
 * exhausted → 429) before dispatching.
 *
 * <p>Submit is fire-and-forget: the service queues a row, kicks off the agent on its dedicated
 * dispatch pool, and returns the row in QUEUED status immediately. The frontend subscribes to
 * {@code /api/runs/pr-review-{id}/events} (existing SSE endpoint pattern) for live progress, OR
 * polls {@link #get(UUID, AppUserDetails)} every couple of seconds.
 */
@RestController
@RequiredArgsConstructor
public class PrReviewController {

  private final PrReviewService service;
  private final ProjectAccessGuard projectAccessGuard;

  // ── Submit / fetch ─────────────────────────────────────────────────────────

  /** Body for {@link #submit}. */
  public record SubmitPrReviewRequest(String projectId, String repoFullName, Integer prNumber) {}

  /**
   * Kick off a PR review. Returns the (possibly QUEUED, possibly already-COMPLETE if a re-run on
   * the same SHA) review row.
   */
  @PostMapping("/api/pr-reviews")
  public ResponseEntity<PrReviewDto> submit(
      @AuthenticationPrincipal AppUserDetails userDetails, @RequestBody SubmitPrReviewRequest req) {
    if (userDetails == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
    }
    if (req == null
        || req.projectId() == null
        || req.repoFullName() == null
        || req.prNumber() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "projectId, repoFullName, and prNumber are required");
    }
    try {
      service.assertTierAndQuota(userDetails.getUser());
    } catch (PrReviewService.TierBlockedException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
    } catch (PrReviewService.QuotaExhaustedException e) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    PrReview row;
    try {
      row =
          service.submitReview(
              req.projectId(), req.repoFullName(), req.prNumber(), userDetails.getUser());
    } catch (IllegalStateException e) {
      // GitHub fetch failed — treat as 502 because it's an upstream-dependency error,
      // not a request validation error.
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
    }
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(PrReviewDto.from(row));
  }

  /** Get a single review by id (with comments inlined for convenience). */
  @GetMapping("/api/pr-reviews/{id}")
  public ResponseEntity<PrReviewWithComments> get(
      @PathVariable UUID id, @AuthenticationPrincipal AppUserDetails userDetails) {
    if (userDetails == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
    }
    PrReview row = service.findById(id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such PR review");
    }
    List<PrReviewComment> comments = service.commentsFor(id);
    return ResponseEntity.ok(PrReviewWithComments.from(row, comments));
  }

  /** All reviews for a single PR, newest first. */
  @GetMapping("/api/pr-reviews")
  public ResponseEntity<List<PrReviewDto>> listForPr(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @RequestParam("repo") String repoFullName,
      @RequestParam("number") int prNumber) {
    if (userDetails == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
    }
    return ResponseEntity.ok(
        service.findByPr(repoFullName, prNumber).stream().map(PrReviewDto::from).toList());
  }

  /** All reviews for a project, newest first — used by the project-level dashboard. */
  @GetMapping("/api/projects/{projectId}/pr-reviews")
  public ResponseEntity<List<PrReviewDto>> listForProject(
      @PathVariable String projectId, @AuthenticationPrincipal AppUserDetails userDetails) {
    if (userDetails == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
    }
    // GA-readiness P4.1-followup — tenant guard. PR review payload includes
    // line-anchored comments which can leak code structure across orgs.
    projectAccessGuard.assertCanRead(projectId, userDetails);
    return ResponseEntity.ok(
        service.findByProject(projectId).stream().map(PrReviewDto::from).toList());
  }

  // ── DTOs ───────────────────────────────────────────────────────────────────

  /** Trim the entity for JSON; client never needs the raw cost columns sliced separately. */
  public record PrReviewDto(
      UUID id,
      String projectId,
      String repoFullName,
      Integer prNumber,
      String headSha,
      String status,
      String verdict,
      String summary,
      Double costUsd,
      Long durationMs,
      Instant startedAt,
      Instant finishedAt,
      String failureReason) {
    static PrReviewDto from(PrReview r) {
      return new PrReviewDto(
          r.getId(),
          r.getProjectId(),
          r.getRepoFullName(),
          r.getPrNumber(),
          r.getHeadSha(),
          r.getStatus(),
          r.getVerdict(),
          r.getSummary(),
          r.getCostUsd(),
          r.getDurationMs(),
          r.getStartedAt(),
          r.getFinishedAt(),
          r.getFailureReason());
    }
  }

  public record PrReviewCommentDto(
      UUID id,
      String filePath,
      Integer lineNumber,
      String side,
      String category,
      String severity,
      String body) {
    static PrReviewCommentDto from(PrReviewComment c) {
      return new PrReviewCommentDto(
          c.getId(),
          c.getFilePath(),
          c.getLineNumber(),
          c.getSide(),
          c.getCategory(),
          c.getSeverity(),
          c.getBody());
    }
  }

  public record PrReviewWithComments(PrReviewDto review, List<PrReviewCommentDto> comments) {
    static PrReviewWithComments from(PrReview r, List<PrReviewComment> comments) {
      return new PrReviewWithComments(
          PrReviewDto.from(r), comments.stream().map(PrReviewCommentDto::from).toList());
    }
  }
}

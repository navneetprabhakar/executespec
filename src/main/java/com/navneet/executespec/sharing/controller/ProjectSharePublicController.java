package com.navneet.executespec.sharing.controller;

import java.time.Instant;

import com.navneet.executespec.codeintel.entity.ProjectArchitectureDoc;
import com.navneet.executespec.codeintel.repository.ProjectArchitectureDocRepository;
import com.navneet.executespec.codereview.entity.CodeReviewIssue;
import com.navneet.executespec.codereview.entity.CodeReviewScan;
import com.navneet.executespec.codereview.repository.CodeReviewIssueRepository;
import com.navneet.executespec.codereview.repository.CodeReviewScanRepository;
import com.navneet.executespec.prreview.entity.PrReview;
import com.navneet.executespec.prreview.entity.PrReviewComment;
import com.navneet.executespec.prreview.repository.PrReviewCommentRepository;
import com.navneet.executespec.prreview.repository.PrReviewRepository;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.sharing.entity.ProjectShareLink;
import com.navneet.executespec.sharing.service.ProjectShareLinkService;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/**
 * V175 — public, no-auth read path for project share links. The {@code /api/shared/**} prefix is
 * whitelisted in {@link com.navneet.executespec.auth.security.SecurityConfig}.
 *
 * <p>Each surface returns only the read-safe slice — never internal IDs, internal tokens, or
 * credentials. The link's token is the bearer; revocation or expiry returns 410 Gone the same way
 * the existing {@code GET /api/reports/shared/{token}} does.
 */
@RestController
@RequiredArgsConstructor
public class ProjectSharePublicController {

  private final ProjectShareLinkService linkService;
  private final PipelineRunRepository runRepo;
  private final ValidationReportRepository reportRepo;
  private final CodeReviewScanRepository scanRepo;
  private final CodeReviewIssueRepository issueRepo;
  private final PrReviewRepository prReviewRepo;
  private final PrReviewCommentRepository prCommentRepo;
  private final ProjectArchitectureDocRepository archRepo;

  /**
   * Resolve a share link by token + surface metadata so the frontend can dispatch to the right
   * read-only viewer. Doesn't include the actual content — the caller hits the surface-specific
   * endpoint below to pull the payload.
   */
  @GetMapping("/api/shared/{token}/meta")
  public ResponseEntity<SharedLinkMeta> meta(@PathVariable String token) {
    ProjectShareLink link = linkService.resolveActive(token);
    return ResponseEntity.ok(
        new SharedLinkMeta(
            link.getSurface().name(),
            link.getScopeId(),
            link.getLabel(),
            link.getProjectId(),
            link.getCreatedAt(),
            link.getExpiresAt()));
  }

  // ── Surface-specific public reads ─────────────────────────────────────────

  @GetMapping("/api/shared/{token}/run")
  public ResponseEntity<SharedRun> sharedRun(@PathVariable String token) {
    ProjectShareLink link = mustBe(token, ProjectShareLink.Surface.RUN_DETAIL);
    PipelineRun run =
        runRepo
            .findById(link.getScopeId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
    assertSameProject(run.getProjectId(), link);
    linkService.recordView(link);
    return ResponseEntity.ok(
        new SharedRun(
            run.getRunId(),
            run.getRunTitle(),
            run.getStatus(),
            run.getStatusReason(),
            run.getStartedAt(),
            run.getCompletedAt(),
            run.getActualCostUsd(),
            link.getLabel()));
  }

  @GetMapping("/api/shared/{token}/validation-report")
  public ResponseEntity<SharedValidationReport> sharedReport(@PathVariable String token) {
    ProjectShareLink link = mustBe(token, ProjectShareLink.Surface.VALIDATION_REPORT);
    ValidationReport report =
        reportRepo
            .findByRunId(link.getScopeId())
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Validation report not found"));
    // The report carries no projectId of its own — its owning run is the authority. Resolve the
    // run and verify it belongs to the link's project before returning the report payload.
    PipelineRun reportRun =
        runRepo
            .findById(report.getRunId())
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Validation report not found"));
    assertSameProject(reportRun.getProjectId(), link);
    linkService.recordView(link);
    return ResponseEntity.ok(
        new SharedValidationReport(
            report.getRunId(),
            report.getAccuracyScore(),
            report.getVerifiedScore(),
            report.getReviewerVerdict(),
            report.getFinalBuildPassed(),
            report.getTestsPassed(),
            report.getTestsTotal(),
            null, // recommendation field removed in v2.5.10 — left null for back-compat
            report.getCreatedAt()));
  }

  @GetMapping("/api/shared/{token}/code-review")
  public ResponseEntity<SharedCodeReview> sharedCodeReview(@PathVariable String token) {
    ProjectShareLink link = mustBe(token, ProjectShareLink.Surface.CODE_REVIEW);
    java.util.UUID scanId = java.util.UUID.fromString(link.getScopeId());
    CodeReviewScan scan =
        scanRepo
            .findById(scanId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Code review not found"));
    assertSameProject(scan.getProjectId(), link);
    linkService.recordView(link);
    java.util.List<CodeReviewIssue> issues =
        issueRepo.findByScanIdOrderBySeverityAscFilePathAscLineStartAsc(scanId);
    return ResponseEntity.ok(
        new SharedCodeReview(
            scan.getId(),
            scan.getRepoFullName(),
            scan.getStatus(),
            scan.getReportMarkdown(),
            issues.size(),
            (int) issues.stream().filter(i -> "CRITICAL".equals(i.getSeverity())).count(),
            (int) issues.stream().filter(i -> "MAJOR".equals(i.getSeverity())).count(),
            link.getLabel()));
  }

  @GetMapping("/api/shared/{token}/pr-review")
  public ResponseEntity<SharedPrReview> sharedPrReview(@PathVariable String token) {
    ProjectShareLink link = mustBe(token, ProjectShareLink.Surface.PR_REVIEW);
    java.util.UUID reviewId = java.util.UUID.fromString(link.getScopeId());
    PrReview review =
        prReviewRepo
            .findById(reviewId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PR review not found"));
    assertSameProject(review.getProjectId(), link);
    linkService.recordView(link);
    java.util.List<PrReviewComment> comments =
        prCommentRepo.findByPrReviewIdOrderByFilePathAscLineNumberAsc(reviewId);
    return ResponseEntity.ok(
        new SharedPrReview(
            review.getId(),
            review.getRepoFullName(),
            review.getPrNumber(),
            review.getVerdict(),
            review.getSummary(),
            comments.size(),
            link.getLabel()));
  }

  @GetMapping("/api/shared/{token}/architecture")
  public ResponseEntity<SharedArchitecture> sharedArchitecture(@PathVariable String token) {
    ProjectShareLink link = mustBe(token, ProjectShareLink.Surface.ARCHITECTURE_DOC);
    ProjectArchitectureDoc doc =
        archRepo
            .findFirstByProjectIdOrderByVersionDesc(link.getProjectId())
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Architecture doc not generated"));
    linkService.recordView(link);
    return ResponseEntity.ok(
        new SharedArchitecture(
            doc.getProjectId(),
            doc.getVersion(),
            doc.getContent(),
            doc.getGeneratedAt(),
            link.getLabel()));
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private ProjectShareLink mustBe(String token, ProjectShareLink.Surface expected) {
    ProjectShareLink link = linkService.resolveActive(token);
    if (link.getSurface() != expected) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "Share link does not target this surface");
    }
    return link;
  }

  /**
   * Load-bearing cross-org IDOR guard for the public read path: the entity resolved by the link's
   * {@code scopeId} MUST belong to the link's own project. This holds even if a malformed link was
   * minted before the create-side guard existed. Returns {@code 404 NOT_FOUND} (not 403) on a
   * mismatch so this unauthenticated endpoint never leaks whether the foreign entity exists.
   *
   * <p>Null-safe: a null {@code entityProjectId} or a null link project id never passes.
   */
  private static void assertSameProject(String entityProjectId, ProjectShareLink link) {
    String linkProjectId = link.getProjectId();
    if (entityProjectId == null
        || linkProjectId == null
        || !entityProjectId.equals(linkProjectId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
    }
  }

  // ── Public-safe DTOs (never include internal tokens / credentials) ───────

  public record SharedLinkMeta(
      String surface,
      String scopeId,
      String label,
      String projectId,
      Instant createdAt,
      Instant expiresAt) {}

  public record SharedRun(
      String runId,
      String title,
      String status,
      String statusReason,
      Instant startedAt,
      Instant completedAt,
      Double costUsd,
      String label) {}

  public record SharedValidationReport(
      String runId,
      Integer accuracyScore,
      Integer verifiedScore,
      String reviewerVerdict,
      Boolean finalBuildPassed,
      Integer testsPassed,
      Integer testsTotal,
      String recommendation,
      Instant createdAt) {}

  public record SharedCodeReview(
      java.util.UUID id,
      String repoFullName,
      String status,
      String reportMarkdown,
      int totalIssues,
      int criticalCount,
      int majorCount,
      String label) {}

  public record SharedPrReview(
      java.util.UUID id,
      String repoFullName,
      Integer prNumber,
      String verdict,
      String summary,
      int commentCount,
      String label) {}

  public record SharedArchitecture(
      String projectId, Integer version, String content, Instant generatedAt, String label) {}
}

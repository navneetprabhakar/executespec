package com.navneet.executespec.codereview;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.codereview.entity.CodeReviewIssue;
import com.navneet.executespec.codereview.entity.CodeReviewScan;
import com.navneet.executespec.cost.CostVisibilityPolicy;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.project.service.ProjectRepoService;
import com.navneet.executespec.security.GeneratedSecretScanner;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
 * Surface for the whole-repo Code Review Agent.
 *
 * <p>Tier-gated to PRO/TEAM/MAX/ENTERPRISE; FREE returns 403. Per-tier monthly quota enforced
 * before dispatch (PRO=4, others unlimited; quota exhausted → 429).
 *
 * <p>Submit is fire-and-forget: the service queues a row + kicks off the scan on its dispatch pool.
 * Frontend polls {@link #get(UUID, AppUserDetails)} (or subscribes to the {@code
 * code-review-{scanId}} SSE channel via the existing run-events surface) for live progress.
 *
 * <p>The {@code report.md} download endpoint exists so the chat surface can hand the user the
 * verbatim markdown report without going through the issue list.
 */
@RestController
@RequiredArgsConstructor
public class CodeReviewController {

  static final int MAX_HISTORY_DAYS = 366;
  static final int MAX_HISTORY_PAGE_SIZE = 200;
  static final int MAX_HISTORY_PAGE = 1000;

  private final CodeReviewService service;
  private final ProjectAccessGuard projectAccessGuard;
  private final ProjectRepoService projectRepoService;
  private final CodeReviewAutofixService autofixService;
  private final GeneratedSecretScanner secretScanner;
  private final ProjectRepository projectRepository;

  /**
   * RR-563 — raw provider-dollar cost is a billing figure. The scan DTO exposed {@code costUsd} to
   * every project reader (including non-billing developers and cross-org collaborators). Only a
   * billing-role viewer of the project's own org sees it; for everyone else the field is {@code
   * null}, and the DTO shape is otherwise unchanged so the dashboard keeps rendering.
   */
  private boolean canSeeCost(String projectId, AppUserDetails userDetails) {
    String orgId = projectRepository.findById(projectId).map(Project::getOrgId).orElse(null);
    return CostVisibilityPolicy.canViewRawCost(userDetails, orgId);
  }

  public record SubmitCodeReviewRequest(String projectId, UUID repoId, String repoFullName) {}

  /** Kick off a scan. Returns a 202 with the QUEUED row. */
  @PostMapping("/api/code-reviews")
  public ResponseEntity<CodeReviewScanDto> submit(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @RequestBody SubmitCodeReviewRequest req) {
    if (userDetails == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
    }
    if (req == null || req.projectId() == null || req.repoId() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "projectId and repoId are required");
    }
    // Tenant/write admission and canonical repository resolution happen before the paid-tier or
    // quota checks. A caller must neither probe another tenant's linked repos nor consume quota for
    // an arbitrary repoFullName.
    projectAccessGuard.assertCanWrite(req.projectId(), userDetails);
    var repo =
        projectRepoService.resolveGitHubReviewRepoStrict(
            req.projectId(), req.repoId(), req.repoFullName());
    String canonicalFullName = repo.getGithubRepoFullName();
    try {
      service.assertTierAndQuota(userDetails.getUser());
    } catch (CodeReviewService.TierBlockedException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
    } catch (CodeReviewService.QuotaExhaustedException e) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    CodeReviewScan row;
    try {
      row =
          service.submitScan(
              req.projectId(), repo.getId(), canonicalFullName, userDetails.getUser());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(CodeReviewScanDto.from(row, canSeeCost(req.projectId(), userDetails)));
  }

  /** Body for {@link #autofix}. */
  public record AutofixRequest(String severity) {}

  /** Response for {@link #autofix}. */
  public record AutofixResponse(String runId, String findingKey, String outcome) {}

  /**
   * Phase 4 (V183) — dispatch a patch-mode run that addresses every OPEN issue of the named
   * severity bucket from a code review scan. One PR per severity so reviewers can grade
   * independently. Idempotent server-side: an in-flight run for the same (scan, severity) returns
   * the existing run with {@code outcome=REUSED_ACTIVE}.
   */
  @PostMapping("/api/code-reviews/{id}/autofix")
  public ResponseEntity<AutofixResponse> autofix(
      @PathVariable UUID id,
      @RequestBody AutofixRequest req,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    if (userDetails == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
    }
    CodeReviewScan scan = service.findScan(id);
    if (scan == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such scan");
    }
    // Audit 2026-05-12 review item B #12 — autofix dispatches a patch-mode run + opens a draft
    // PR. PROJECT_VIEWER must not be able to trigger this.
    projectAccessGuard.assertCanWrite(scan.getProjectId(), userDetails);

    var result =
        autofixService.dispatchFixForSeverity(
            id, req == null ? null : req.severity(), userDetails.getUser());
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(new AutofixResponse(result.runId(), result.findingKey(), result.outcome().name()));
  }

  @PostMapping("/api/code-reviews/{id}/cancel")
  public ResponseEntity<Void> cancel(
      @PathVariable UUID id, @AuthenticationPrincipal AppUserDetails userDetails) {
    CodeReviewScan scan = requireWritableScan(id, userDetails);
    if (!service.requestCancellation(scan.getId())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "scan is not cancellable");
    }
    return ResponseEntity.accepted().build();
  }

  @PostMapping("/api/code-reviews/{id}/retry")
  public ResponseEntity<CodeReviewScanDto> retry(
      @PathVariable UUID id, @AuthenticationPrincipal AppUserDetails userDetails) {
    CodeReviewScan scan = requireWritableScan(id, userDetails);
    if (!service.retryScan(scan.getId())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "scan is not retryable");
    }
    return ResponseEntity.accepted()
        .body(
            CodeReviewScanDto.from(
                service.findScan(id), canSeeCost(scan.getProjectId(), userDetails)));
  }

  private CodeReviewScan requireWritableScan(UUID id, AppUserDetails userDetails) {
    if (userDetails == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
    }
    CodeReviewScan scan = service.findScan(id);
    if (scan == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such scan");
    projectAccessGuard.assertCanWrite(scan.getProjectId(), userDetails);
    return scan;
  }

  /** Get a single scan by id (with issues inlined for convenience). */
  @GetMapping("/api/code-reviews/{id}")
  public ResponseEntity<CodeReviewScanWithIssues> get(
      @PathVariable UUID id, @AuthenticationPrincipal AppUserDetails userDetails) {
    if (userDetails == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
    }
    CodeReviewScan row = service.findScan(id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such scan");
    }
    projectAccessGuard.assertCanRead(row.getProjectId(), userDetails);
    List<CodeReviewIssue> issues = service.issuesFor(id);
    return ResponseEntity.ok(
        CodeReviewScanWithIssues.from(row, issues, canSeeCost(row.getProjectId(), userDetails)));
  }

  /**
   * All scans for a project, newest first. Used by the Code Quality dashboard tab to render
   * historical scans + the latest verdict per repo.
   */
  @GetMapping("/api/projects/{projectId}/code-reviews")
  public ResponseEntity<List<CodeReviewScanDto>> listForProject(
      @PathVariable String projectId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "366") int sinceDays,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    if (userDetails == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
    }
    // GA-readiness P4.1-followup — code review scans expose source-file paths
    // + suggested-fix snippets, both leak across orgs without a tenant guard.
    projectAccessGuard.assertCanRead(projectId, userDetails);
    int safePage = Math.min(Math.max(page, 0), MAX_HISTORY_PAGE);
    int safeLimit = Math.min(Math.max(limit, 1), MAX_HISTORY_PAGE_SIZE);
    int safeSinceDays = Math.min(Math.max(sinceDays, 1), MAX_HISTORY_DAYS);
    boolean includeCost = canSeeCost(projectId, userDetails);
    var rows =
        service.listForProject(
            projectId,
            Instant.now().minusSeconds((long) safeSinceDays * 86_400L),
            org.springframework.data.domain.PageRequest.of(safePage, safeLimit));
    return ResponseEntity.ok()
        .header("X-Page", Integer.toString(safePage))
        .header("X-Page-Size", Integer.toString(safeLimit))
        .header("X-Has-More", Boolean.toString(rows.hasNext()))
        .body(rows.getContent().stream().map(s -> CodeReviewScanDto.from(s, includeCost)).toList());
  }

  /**
   * Latest COMPLETE scan for a (project, repo) — used when the user opens the Code Quality tab
   * cold. 204 if no successful scan exists.
   */
  @GetMapping("/api/projects/{projectId}/code-reviews/latest")
  public ResponseEntity<CodeReviewScanWithIssues> latest(
      @PathVariable String projectId,
      @RequestParam("repo") String repoFullName,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    if (userDetails == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
    }
    // GA-readiness P4.1-followup — same rationale as listForProject above.
    projectAccessGuard.assertCanRead(projectId, userDetails);
    // Dashboard wants to see FAILED scans with their failureReason too — only the chat tool path
    // restricts to COMPLETE so it never narrates a partial / failed report as if it were canonical.
    Optional<CodeReviewScan> latest =
        service.latestForProjectRepoAnyStatus(projectId, repoFullName);
    if (latest.isEmpty()) return ResponseEntity.noContent().build();
    List<CodeReviewIssue> issues = service.issuesFor(latest.get().getId());
    return ResponseEntity.ok(
        CodeReviewScanWithIssues.from(latest.get(), issues, canSeeCost(projectId, userDetails)));
  }

  /**
   * Download the markdown report verbatim. Same surface contract the existing {@code GET
   * /api/projects/{id}/docs/{type}/download} endpoints use (Content-Disposition attachment,
   * text/markdown).
   */
  @GetMapping("/api/code-reviews/{id}/report")
  public ResponseEntity<String> downloadReport(
      @PathVariable UUID id, @AuthenticationPrincipal AppUserDetails userDetails) {
    if (userDetails == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
    }
    CodeReviewScan row = service.findScan(id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such scan");
    }
    projectAccessGuard.assertCanRead(row.getProjectId(), userDetails);
    String md = row.getReportMarkdown();
    if (md == null || md.isBlank()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "scan has no report yet");
    }
    if (secretScanner.scan(md).isPresent()) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "Code review contains credential-like material and cannot be downloaded");
    }
    String filename =
        "code-review-"
            + row.getRepoFullName().replace('/', '-')
            + "-"
            + shortSha(row.getScannedSha())
            + ".md";
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
        .contentType(MediaType.parseMediaType("text/markdown; charset=UTF-8"))
        .body(md);
  }

  static String shortSha(String sha) {
    if (sha == null) return "none";
    return sha.length() <= 7 ? sha : sha.substring(0, 7);
  }

  public record CodeReviewScanDto(
      UUID id,
      String projectId,
      UUID repoId,
      String repoFullName,
      String scannedSha,
      String status,
      String triggerSource,
      Integer totalFilesScanned,
      Integer totalLoc,
      // RR-1696 — coverage of the file-sampling walk: COMPLETE | TRUNCATED_* | null (read as
      // UNKNOWN), so a possibly-truncated totalFilesScanned/totalLoc is not presented as fact.
      String scanCoverage,
      Integer staticFindingsCount,
      Integer llmFindingsCount,
      Integer llmFilteredOutCount,
      Double costUsd,
      Long durationMs,
      Instant startedAt,
      Instant finishedAt,
      String failureReason) {
    /**
     * RR-563 — {@code includeCost=false} nulls the raw provider-dollar cost for non-billing
     * readers.
     */
    static CodeReviewScanDto from(CodeReviewScan s, boolean includeCost) {
      return new CodeReviewScanDto(
          s.getId(),
          s.getProjectId(),
          s.getRepoId(),
          s.getRepoFullName(),
          s.getScannedSha(),
          s.getStatus(),
          s.getTriggerSource(),
          s.getTotalFilesScanned(),
          s.getTotalLoc(),
          s.getScanCoverage(),
          s.getStaticFindingsCount(),
          s.getLlmFindingsCount(),
          s.getLlmFilteredOutCount(),
          includeCost ? s.getCostUsd() : null,
          s.getDurationMs(),
          s.getStartedAt(),
          s.getFinishedAt(),
          s.getFailureReason());
    }
  }

  public record CodeReviewIssueDto(
      UUID id,
      String fingerprint,
      String severity,
      String category,
      String source,
      String ruleId,
      String filePath,
      Integer lineStart,
      Integer lineEnd,
      String message,
      String suggestedFix,
      String status) {
    static CodeReviewIssueDto from(CodeReviewIssue i) {
      return new CodeReviewIssueDto(
          i.getId(),
          i.getFingerprint(),
          i.getSeverity(),
          i.getCategory(),
          i.getSource(),
          i.getRuleId(),
          i.getFilePath(),
          i.getLineStart(),
          i.getLineEnd(),
          i.getMessage(),
          i.getSuggestedFix(),
          i.getStatus());
    }
  }

  public record CodeReviewScanWithIssues(
      CodeReviewScanDto scan, String reportMarkdown, List<CodeReviewIssueDto> issues) {
    static CodeReviewScanWithIssues from(
        CodeReviewScan s, List<CodeReviewIssue> issues, boolean includeCost) {
      return new CodeReviewScanWithIssues(
          CodeReviewScanDto.from(s, includeCost),
          s.getReportMarkdown(),
          issues.stream().map(CodeReviewIssueDto::from).toList());
    }
  }
}

package com.navneet.executespec.codereview;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.codereview.entity.CodeReviewIssue;
import com.navneet.executespec.codereview.entity.CodeReviewScan;

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

  private final CodeReviewService service;

  // ── Submit / fetch ─────────────────────────────────────────────────────────

  public record SubmitCodeReviewRequest(String projectId, UUID repoId, String repoFullName) {}

  /** Kick off a scan. Returns a 202 with the QUEUED row. */
  @PostMapping("/api/code-reviews")
  public ResponseEntity<CodeReviewScanDto> submit(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @RequestBody SubmitCodeReviewRequest req) {
    if (userDetails == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
    }
    if (req == null || req.projectId() == null || req.repoFullName() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "projectId and repoFullName are required");
    }
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
              req.projectId(), req.repoId(), req.repoFullName(), userDetails.getUser());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(CodeReviewScanDto.from(row));
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
    List<CodeReviewIssue> issues = service.issuesFor(id);
    return ResponseEntity.ok(CodeReviewScanWithIssues.from(row, issues));
  }

  /**
   * All scans for a project, newest first. Used by the Code Quality dashboard tab to render
   * historical scans + the latest verdict per repo.
   */
  @GetMapping("/api/projects/{projectId}/code-reviews")
  public ResponseEntity<List<CodeReviewScanDto>> listForProject(
      @PathVariable String projectId, @AuthenticationPrincipal AppUserDetails userDetails) {
    if (userDetails == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
    }
    return ResponseEntity.ok(
        service.listForProject(projectId).stream().map(CodeReviewScanDto::from).toList());
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
    // Dashboard wants to see FAILED scans with their failureReason too — only the chat tool path
    // restricts to COMPLETE so it never narrates a partial / failed report as if it were canonical.
    Optional<CodeReviewScan> latest =
        service.latestForProjectRepoAnyStatus(projectId, repoFullName);
    if (latest.isEmpty()) return ResponseEntity.noContent().build();
    List<CodeReviewIssue> issues = service.issuesFor(latest.get().getId());
    return ResponseEntity.ok(CodeReviewScanWithIssues.from(latest.get(), issues));
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
    String md = row.getReportMarkdown();
    if (md == null || md.isBlank()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "scan has no report yet");
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

  // ── DTOs ───────────────────────────────────────────────────────────────────

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
      Integer staticFindingsCount,
      Integer llmFindingsCount,
      Integer llmFilteredOutCount,
      Double costUsd,
      Long durationMs,
      Instant startedAt,
      Instant finishedAt,
      String failureReason) {
    static CodeReviewScanDto from(CodeReviewScan s) {
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
          s.getStaticFindingsCount(),
          s.getLlmFindingsCount(),
          s.getLlmFilteredOutCount(),
          s.getCostUsd(),
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
    static CodeReviewScanWithIssues from(CodeReviewScan s, List<CodeReviewIssue> issues) {
      return new CodeReviewScanWithIssues(
          CodeReviewScanDto.from(s),
          s.getReportMarkdown(),
          issues.stream().map(CodeReviewIssueDto::from).toList());
    }
  }
}

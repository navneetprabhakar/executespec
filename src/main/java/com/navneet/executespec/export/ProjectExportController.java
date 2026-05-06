package com.navneet.executespec.export;

import java.time.Instant;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.project.security.ProjectAccessGuard;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * E4.7 — synchronous export endpoint for v1.
 *
 * <p>Async job-tracking ({@code POST /export} → 202 + job id; {@code GET /exports/{jobId}} → status
 * + download URL on completion) deferred to keep the slice bounded — current shape is suitable for
 * projects up to a few MB of bundle output. For larger projects, the GCS-upload path will replace
 * this synchronous response.
 *
 * <p>Rate-limit (1/h, 5/d per project) deferred to a follow-up using the existing {@code
 * RateLimitFilter} group enum.
 *
 * <p>PROJECT_ADMIN gating advisory in v1 — the existing project- membership middleware applies;
 * tighter role-based gating lands when the standards-UI surface (E3.16) gets its admin gates added.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/export")
@RequiredArgsConstructor
@Slf4j
public class ProjectExportController {

  private final ProjectExportService exportService;
  private final ProjectAccessGuard projectAccessGuard;

  /**
   * M-012 (Wave 1): tightened from any-project-member to ORG_ADMIN+. Project export bundles the
   * entire .executespec/ folder (standards, ADRs, invariants, contracts, activity feed) — Carmen /
   * compliance concern flagged in the TEAM/ENT UX assessment.
   */
  @PostMapping
  @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','BILLING_ADMIN')")
  public ResponseEntity<byte[]> export(
      @PathVariable String projectId,
      Authentication auth,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    if (auth == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "auth required");
    }
    // GA-readiness P4.1-followup — tenant guard. The @PreAuthorize role gate
    // above checks that the caller is an admin SOMEWHERE; it does NOT verify
    // they admin THIS project. Without this assertCanRead, an ORG_ADMIN of
    // org A could request the export of a project owned by org B.
    projectAccessGuard.assertCanRead(projectId, userDetails);
    try {
      byte[] bundle = exportService.composeBundle(projectId);
      String filename =
          "project-"
              + projectId
              + "-export-"
              + Instant.now().toString().replace(':', '-')
              + ".tar.gz";
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.parseMediaType("application/gzip"));
      headers.setContentDispositionFormData("attachment", filename);
      headers.setContentLength(bundle.length);
      return new ResponseEntity<>(bundle, headers, HttpStatus.OK);
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
    } catch (Exception ex) {
      log.error(
          "[ProjectExportController] export failed for {}: {}", projectId, ex.getMessage(), ex);
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "export failed: " + ex.getMessage());
    }
  }
}

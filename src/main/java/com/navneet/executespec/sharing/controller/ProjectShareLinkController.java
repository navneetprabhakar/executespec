package com.navneet.executespec.sharing.controller;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.sharing.entity.ProjectShareLink;
import com.navneet.executespec.sharing.service.ProjectShareLinkService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/**
 * V175 — auth-protected admin surface for managing project share links. The public read path lives
 * in {@link ProjectSharePublicController} so the security filter can whitelist it cleanly.
 */
@RestController
@RequiredArgsConstructor
public class ProjectShareLinkController {

  private final ProjectShareLinkService linkService;
  private final ProjectAccessGuard accessGuard;

  /**
   * @param surface required — see {@link ProjectShareLink.Surface}
   * @param scopeId required for run / scan / pr-review / report surfaces; ignored for project-wide
   *     ones
   * @param label optional human-readable label
   * @param expiresInDays optional — if null the link doesn't expire (admin-revocation only)
   */
  public record CreateShareLinkRequest(
      String surface, String scopeId, String label, Integer expiresInDays) {}

  @PostMapping("/api/projects/{projectId}/share-links")
  public ResponseEntity<ShareLinkDto> create(
      @PathVariable String projectId,
      @RequestBody CreateShareLinkRequest req,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    accessGuard.assertCanRead(projectId, userDetails);
    if (req == null || req.surface() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "surface is required");
    }
    ProjectShareLink.Surface surface;
    try {
      surface = ProjectShareLink.Surface.valueOf(req.surface().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "unknown surface: " + req.surface());
    }
    Instant expiresAt =
        req.expiresInDays() == null
            ? null
            : Instant.now().plusSeconds(req.expiresInDays() * 24L * 3600L);
    ProjectShareLink link =
        linkService.createLink(
            projectId, surface, req.scopeId(), req.label(), expiresAt, userDetails.getUser());
    return ResponseEntity.status(HttpStatus.CREATED).body(ShareLinkDto.from(link));
  }

  @GetMapping("/api/projects/{projectId}/share-links")
  public ResponseEntity<ShareLinkListResponse> list(
      @PathVariable String projectId, @AuthenticationPrincipal AppUserDetails userDetails) {
    accessGuard.assertCanRead(projectId, userDetails);
    List<ShareLinkDto> all =
        linkService.listForProject(projectId).stream().map(ShareLinkDto::from).toList();
    long active = linkService.activeCountForProject(projectId);
    return ResponseEntity.ok(new ShareLinkListResponse(all, active));
  }

  @DeleteMapping("/api/projects/{projectId}/share-links/{linkId}")
  public ResponseEntity<Void> revoke(
      @PathVariable String projectId,
      @PathVariable UUID linkId,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    accessGuard.assertCanRead(projectId, userDetails);
    linkService.revoke(linkId, userDetails.getUser());
    return ResponseEntity.noContent().build();
  }

  // ── DTOs ──────────────────────────────────────────────────────────────────

  /** Includes the token because the dashboard owner needs it to copy the URL. */
  public record ShareLinkDto(
      UUID id,
      String projectId,
      String token,
      ProjectShareLink.Surface surface,
      String scopeId,
      String label,
      String createdByUserId,
      Instant expiresAt,
      Instant revokedAt,
      Long views,
      Instant lastViewedAt,
      Instant createdAt,
      boolean active) {
    static ShareLinkDto from(ProjectShareLink l) {
      return new ShareLinkDto(
          l.getId(),
          l.getProjectId(),
          l.getToken(),
          l.getSurface(),
          l.getScopeId(),
          l.getLabel(),
          l.getCreatedByUserId(),
          l.getExpiresAt(),
          l.getRevokedAt(),
          l.getViews(),
          l.getLastViewedAt(),
          l.getCreatedAt(),
          l.isActive());
    }
  }

  public record ShareLinkListResponse(List<ShareLinkDto> links, long activeCount) {}
}

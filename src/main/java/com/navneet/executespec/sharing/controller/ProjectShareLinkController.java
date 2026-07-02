package com.navneet.executespec.sharing.controller;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.entity.ProjectMember;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;
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
  private final ProjectMemberRepository projectMemberRepo;

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
    // Audit 2026-05-12 review item B #10 — minting a public share link is a write op; was
    // assertCanRead which let PROJECT_VIEWER create them.
    Project project = accessGuard.assertCanWrite(projectId, userDetails);
    assertCanManageShareLinks(project, userDetails);
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
    return ResponseEntity.status(HttpStatus.CREATED).body(ShareLinkDto.from(link, true));
  }

  @GetMapping("/api/projects/{projectId}/share-links")
  public ResponseEntity<ShareLinkListResponse> list(
      @PathVariable String projectId, @AuthenticationPrincipal AppUserDetails userDetails) {
    Project project = accessGuard.assertCanRead(projectId, userDetails);
    boolean includeToken = canManageShareLinks(project, userDetails);
    List<ShareLinkDto> all =
        linkService.listForProject(projectId).stream()
            .map(l -> ShareLinkDto.from(l, includeToken))
            .toList();
    long active = linkService.activeCountForProject(projectId);
    return ResponseEntity.ok(new ShareLinkListResponse(all, active));
  }

  @DeleteMapping("/api/projects/{projectId}/share-links/{linkId}")
  public ResponseEntity<Void> revoke(
      @PathVariable String projectId,
      @PathVariable UUID linkId,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    // Audit 2026-05-12 review item B #10 — revoke is also a write.
    Project project = accessGuard.assertCanWrite(projectId, userDetails);
    assertCanManageShareLinks(project, userDetails);
    linkService.revoke(projectId, linkId, userDetails.getUser());
    return ResponseEntity.noContent().build();
  }

  private void assertCanManageShareLinks(Project project, AppUserDetails userDetails) {
    if (!canManageShareLinks(project, userDetails)) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "You don't have share-link management access for this project");
    }
  }

  private boolean canManageShareLinks(Project project, AppUserDetails userDetails) {
    if (project == null || userDetails == null || userDetails.getUser() == null) {
      return false;
    }
    if ("SUPER_ADMIN".equals(userDetails.getUser().getGlobalRole())) {
      return true;
    }

    String userId = userDetails.getUser().getId();
    if (userId != null) {
      var membership = projectMemberRepo.findByProjectIdAndUserId(project.getId(), userId);
      if (membership.isPresent()) {
        return isProjectWriter(membership.get());
      }
    }

    String callerOrgId = userDetails.getUser().getOrgId();
    boolean sameOrg = callerOrgId != null && callerOrgId.equals(project.getOrgId());
    return sameOrg
        && userDetails.getOrgMember() != null
        && "ORG_ADMIN".equals(userDetails.getOrgMember().getOrgRole());
  }

  private boolean isProjectWriter(ProjectMember member) {
    String role = member.getProjectRole();
    return "PROJECT_ADMIN".equals(role) || "PROJECT_DEVELOPER".equals(role);
  }

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
    static ShareLinkDto from(ProjectShareLink l, boolean includeToken) {
      return new ShareLinkDto(
          l.getId(),
          l.getProjectId(),
          includeToken ? l.getToken() : null,
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

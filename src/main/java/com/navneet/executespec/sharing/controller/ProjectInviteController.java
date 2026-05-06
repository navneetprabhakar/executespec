package com.navneet.executespec.sharing.controller;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.sharing.entity.ProjectInvite;
import com.navneet.executespec.sharing.service.ProjectInviteService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/**
 * V175 — REST surface for cross-org project invites. Project admins create invites here; the email
 * recipient consumes them via {@code POST /api/invites/accept?token=...}.
 *
 * <p>Auth model: project-create / list / revoke require {@link
 * com.navneet.executespec.project.security.ProjectAccessGuard} (which now consults project_member
 * too). Accept-by-token requires authentication but not project access — the token's email-match
 * check is the gate.
 */
@RestController
@RequiredArgsConstructor
public class ProjectInviteController {

  private final ProjectInviteService inviteService;
  private final ProjectAccessGuard accessGuard;

  // ── Project-scoped admin endpoints ────────────────────────────────────────

  public record CreateInviteRequest(String email, String role) {}

  @PostMapping("/api/projects/{projectId}/invites")
  public ResponseEntity<ProjectInviteDto> create(
      @PathVariable String projectId,
      @RequestBody CreateInviteRequest req,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    accessGuard.assertCanRead(projectId, userDetails);
    if (req == null || req.email() == null || req.email().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "email is required");
    }
    ProjectInvite invite =
        inviteService.createInvite(projectId, req.email(), req.role(), userDetails.getUser());
    return ResponseEntity.status(HttpStatus.CREATED).body(ProjectInviteDto.from(invite));
  }

  @GetMapping("/api/projects/{projectId}/invites")
  public ResponseEntity<List<ProjectInviteDto>> listPending(
      @PathVariable String projectId, @AuthenticationPrincipal AppUserDetails userDetails) {
    accessGuard.assertCanRead(projectId, userDetails);
    return ResponseEntity.ok(
        inviteService.listPending(projectId).stream().map(ProjectInviteDto::from).toList());
  }

  @DeleteMapping("/api/projects/{projectId}/invites/{inviteId}")
  public ResponseEntity<Void> revoke(
      @PathVariable String projectId,
      @PathVariable UUID inviteId,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    accessGuard.assertCanRead(projectId, userDetails);
    inviteService.revoke(inviteId, userDetails.getUser());
    return ResponseEntity.noContent().build();
  }

  // ── Acceptance (recipient-facing) ─────────────────────────────────────────

  public record AcceptResponse(String projectId) {}

  /**
   * Consume a pending invite. Caller's email must match the invite's email (case-insensitive). On
   * success the caller is added as an external {@code ProjectMember} and the invite is marked
   * ACCEPTED.
   */
  @PostMapping("/api/invites/accept")
  public ResponseEntity<AcceptResponse> accept(
      @RequestParam String token, @AuthenticationPrincipal AppUserDetails userDetails) {
    if (userDetails == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
    }
    String projectId = inviteService.accept(token, userDetails.getUser());
    return ResponseEntity.ok(new AcceptResponse(projectId));
  }

  /** Public — view a pending invite's metadata before accepting (for the signup landing page). */
  @GetMapping("/api/invites/preview")
  public ResponseEntity<InvitePreview> preview(@RequestParam String token) {
    ProjectInvite invite =
        inviteService
            .findByToken(token)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invite not found"));
    return ResponseEntity.ok(
        new InvitePreview(
            invite.getEmail(),
            invite.getProjectId(),
            invite.getProjectRole(),
            invite.getStatus(),
            invite.getExpiresAt()));
  }

  // ── DTOs ──────────────────────────────────────────────────────────────────

  public record ProjectInviteDto(
      UUID id,
      String projectId,
      String email,
      String projectRole,
      String status,
      Instant expiresAt,
      Instant createdAt,
      String invitedByUserId) {
    static ProjectInviteDto from(ProjectInvite i) {
      return new ProjectInviteDto(
          i.getId(),
          i.getProjectId(),
          i.getEmail(),
          i.getProjectRole(),
          i.getStatus(),
          i.getExpiresAt(),
          i.getCreatedAt(),
          i.getInvitedByUserId());
    }
  }

  /** Public-safe slice — never includes the token. */
  public record InvitePreview(
      String email, String projectId, String projectRole, String status, Instant expiresAt) {}
}

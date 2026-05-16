package com.navneet.executespec.sharing.controller;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonAlias;
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

  /**
   * SDET 2026-05-16 — BUG-002 / BUG-003 fix.
   *
   * <p><b>BUG-002:</b> a junk email like {@code "not an email"} was being saved as a PENDING invite
   * because there was no format check. Add {@code @Email} + {@code @Size(max=254)} (RFC 5321) +
   * {@code @NotBlank} and let bean validation reject before persistence.
   *
   * <p><b>BUG-003:</b> the wire contract every caller uses is {@code projectRole} (verified in
   * {@code frontend/src/lib/api.ts} and the OpenAPI client). The record's field was {@code role};
   * Jackson silently dropped the unknown {@code projectRole} key, the resolved value ended up
   * {@code null}, and the service downstream defaulted it to {@code PROJECT_VIEWER} — a security
   * concern when a caller intended a higher role. Rename + {@code @JsonAlias("role")} keeps
   * back-compat for any old callers still sending {@code "role"}. {@code @NotBlank} +
   * {@code @Pattern} now reject unknown roles loud at the controller boundary.
   */
  public record CreateInviteRequest(
      @NotBlank(message = "email is required")
          @Email(message = "email must be a valid address")
          @Size(max = 254, message = "email must not exceed 254 characters")
          String email,
      @NotBlank(message = "projectRole is required")
          @Pattern(
              regexp = "PROJECT_ADMIN|PROJECT_DEVELOPER|PROJECT_VIEWER",
              message = "projectRole must be PROJECT_ADMIN, PROJECT_DEVELOPER, or PROJECT_VIEWER")
          @JsonAlias("role")
          String projectRole) {}

  @PostMapping("/api/projects/{projectId}/invites")
  public ResponseEntity<ProjectInviteDto> create(
      @PathVariable String projectId,
      @Valid @RequestBody CreateInviteRequest req,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    // Audit 2026-05-12 review item B #9 — assertCanRead used as a write-gate; now uses
    // assertCanWrite so PROJECT_VIEWER members can't mint invites.
    accessGuard.assertCanWrite(projectId, userDetails);
    // Defensive null-check survives even if a test or service-layer caller bypasses @Valid.
    if (req == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
    }
    ProjectInvite invite =
        inviteService.createInvite(
            projectId, req.email(), req.projectRole(), userDetails.getUser());
    return ResponseEntity.status(HttpStatus.CREATED).body(ProjectInviteDto.from(invite));
  }

  @GetMapping("/api/projects/{projectId}/invites")
  public ResponseEntity<List<ProjectInviteDto>> listPending(
      @PathVariable String projectId, @AuthenticationPrincipal AppUserDetails userDetails) {
    // List is a read operation — assertCanRead is correct here.
    accessGuard.assertCanRead(projectId, userDetails);
    return ResponseEntity.ok(
        inviteService.listPending(projectId).stream().map(ProjectInviteDto::from).toList());
  }

  @DeleteMapping("/api/projects/{projectId}/invites/{inviteId}")
  public ResponseEntity<Void> revoke(
      @PathVariable String projectId,
      @PathVariable UUID inviteId,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    // Audit 2026-05-12 review item B #9 — revoke is a mutate; needs write authority.
    accessGuard.assertCanWrite(projectId, userDetails);
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

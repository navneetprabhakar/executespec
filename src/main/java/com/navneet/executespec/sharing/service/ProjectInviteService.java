package com.navneet.executespec.sharing.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.entity.ProjectMember;
import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.auth.service.MailService;
import com.navneet.executespec.sharing.entity.ProjectInvite;
import com.navneet.executespec.sharing.repository.ProjectInviteRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * V175 — orchestrator for cross-org project sharing via invitations.
 *
 * <p>The flow is intentionally email-first (not user-id-first) so the inviter doesn't need to know
 * whether the recipient already has an account on the platform:
 *
 * <ol>
 *   <li>Project admin calls {@link #createInvite}; we create a {@code project_invite} row + email
 *       the recipient a magic link.
 *   <li>Recipient clicks the link → frontend calls {@link #accept}.
 *   <li>If the email matches an existing user → a {@link ProjectMember} row is created with {@code
 *       is_external=true} and the invite is marked ACCEPTED.
 *   <li>If not, the frontend redirects to signup with the token preserved; on email-verify the same
 *       {@code accept} call fires with the now-linked user.
 * </ol>
 *
 * <p>Tier-gating is light here — only PRO+ may invite externally — but a project that's already
 * been invited to (regardless of tier) keeps working forever.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectInviteService {

  private static final SecureRandom RNG = new SecureRandom();
  private static final Set<String> VALID_ROLES =
      Set.of("PROJECT_ADMIN", "PROJECT_DEVELOPER", "PROJECT_VIEWER");
  private static final Set<String> PAID_TIERS = Set.of("PRO", "TEAM", "MAX", "ENTERPRISE");
  private static final long DEFAULT_EXPIRY_DAYS = 14;

  private final ProjectInviteRepository inviteRepo;
  private final ProjectRepository projectRepo;
  private final ProjectMemberRepository memberRepo;
  private final AppUserRepository userRepo;
  private final TierConfigRepository tierConfigRepo;
  private final MailService mailService;

  /** Public-facing base URL — used to build accept-invite links in the email body. */
  @Value("${app.base-url:http://localhost:5173}")
  private String appBaseUrl;

  // ── Tier gating ──────────────────────────────────────────────────────────

  /**
   * Validate that the inviter's tier permits external invites. Same shape as the other {@code
   * assertTierAndQuota} methods so a future quota knob can be added in one place.
   */
  void assertCanInvite(AppUser inviter, String projectId) {
    if (inviter == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
    }
    String tier = inviter.getTier();
    if (tier == null || !PAID_TIERS.contains(tier.toUpperCase())) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "Inviting external collaborators is a paid feature — upgrade to PRO or above.");
    }
    Optional<TierConfig> cfg = tierConfigRepo.findById(tier);
    if (cfg.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tier config not found");
    }
    // Future: per-tier monthly invite cap. Today: paid tier is enough.
  }

  // ── Public entry ─────────────────────────────────────────────────────────

  /**
   * Create or refresh a pending invite. If a PENDING invite already exists for the same (project,
   * email) tuple, we extend its expiry + bump the role rather than creating a duplicate.
   */
  @Transactional
  public ProjectInvite createInvite(String projectId, String email, String role, AppUser inviter) {
    assertCanInvite(inviter, projectId);
    if (email == null || email.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "email is required");
    }
    String normalizedEmail = email.trim().toLowerCase();
    String resolvedRole = role == null ? "PROJECT_VIEWER" : role.toUpperCase();
    if (!VALID_ROLES.contains(resolvedRole)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "role must be PROJECT_ADMIN, PROJECT_DEVELOPER, or PROJECT_VIEWER");
    }
    Project project =
        projectRepo
            .findById(projectId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));

    // If the recipient already has an account AND is already a member, skip — don't bother
    // creating an invite they don't need.
    Optional<AppUser> existingUser = userRepo.findByEmail(normalizedEmail);
    if (existingUser.isPresent()
        && memberRepo.existsByProjectIdAndUserId(projectId, existingUser.get().getId())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "User is already a member of this project");
    }

    Optional<ProjectInvite> existing =
        inviteRepo.findFirstByProjectIdAndEmailIgnoreCaseAndStatusOrderByCreatedAtDesc(
            projectId, normalizedEmail, "PENDING");
    ProjectInvite invite;
    if (existing.isPresent()) {
      invite = existing.get();
      invite.setProjectRole(resolvedRole);
      invite.setExpiresAt(Instant.now().plusSeconds(DEFAULT_EXPIRY_DAYS * 24 * 3600));
      // Refresh the token so an old leaked email link can't be used after a re-invite — minor
      // hardening; we could keep the old token if we wanted re-invite to be idempotent on the
      // recipient side. Going with rotation by default.
      invite.setToken(generateToken());
    } else {
      invite =
          ProjectInvite.builder()
              .id(UUID.randomUUID())
              .projectId(projectId)
              .email(normalizedEmail)
              .projectRole(resolvedRole)
              .token(generateToken())
              .invitedByUserId(inviter.getId())
              .invitedOrgId(inviter.getOrgId())
              .status("PENDING")
              .expiresAt(Instant.now().plusSeconds(DEFAULT_EXPIRY_DAYS * 24 * 3600))
              .createdAt(Instant.now())
              .build();
    }
    invite = inviteRepo.save(invite);

    // Best-effort email — never fail the create if the mailer is down.
    try {
      mailService.sendProjectInviteEmail(
          normalizedEmail,
          project.getName(),
          inviter.getEmail(),
          appBaseUrl + "/invites/accept?token=" + invite.getToken(),
          DEFAULT_EXPIRY_DAYS);
    } catch (Exception e) {
      log.warn("ProjectInvite email send failed for {}: {}", normalizedEmail, e.getMessage());
    }
    return invite;
  }

  /** Accept an invite. Returns the project id the caller now has access to. */
  @Transactional
  public String accept(String token, AppUser caller) {
    if (token == null || token.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "token is required");
    }
    if (caller == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
    }
    ProjectInvite invite =
        inviteRepo
            .findByToken(token)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invite not found"));
    if (!"PENDING".equals(invite.getStatus())) {
      throw new ResponseStatusException(HttpStatus.GONE, "Invite is no longer valid");
    }
    if (invite.getExpiresAt() != null && Instant.now().isAfter(invite.getExpiresAt())) {
      invite.setStatus("EXPIRED");
      inviteRepo.save(invite);
      throw new ResponseStatusException(HttpStatus.GONE, "Invite has expired");
    }
    // Only the email recipient can accept — case-insensitive match.
    if (caller.getEmail() == null
        || !invite.getEmail().equalsIgnoreCase(caller.getEmail().trim())) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Invite was sent to a different email address");
    }

    // Idempotent — if a member row already exists, mark accepted + return.
    if (!memberRepo.existsByProjectIdAndUserId(invite.getProjectId(), caller.getId())) {
      memberRepo.save(
          ProjectMember.builder()
              .id(UUID.randomUUID().toString())
              .projectId(invite.getProjectId())
              .userId(caller.getId())
              .projectRole(invite.getProjectRole())
              .external(true)
              .assignedAt(Instant.now())
              .build());
    }
    invite.setStatus("ACCEPTED");
    invite.setAcceptedAt(Instant.now());
    invite.setAcceptedByUserId(caller.getId());
    inviteRepo.save(invite);
    return invite.getProjectId();
  }

  @Transactional
  public void revoke(UUID inviteId, AppUser actor) {
    ProjectInvite invite =
        inviteRepo
            .findById(inviteId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invite not found"));
    if (!"PENDING".equals(invite.getStatus())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Invite is not pending");
    }
    invite.setStatus("REVOKED");
    invite.setRevokedAt(Instant.now());
    invite.setRevokedByUserId(actor == null ? null : actor.getId());
    inviteRepo.save(invite);
  }

  // ── Read paths ──────────────────────────────────────────────────────────

  public List<ProjectInvite> listPending(String projectId) {
    return inviteRepo.findByProjectIdAndStatusOrderByCreatedAtDesc(projectId, "PENDING");
  }

  public Optional<ProjectInvite> findByToken(String token) {
    return inviteRepo.findByToken(token);
  }

  // ── Helpers ─────────────────────────────────────────────────────────────

  /** 32-byte URL-safe random token (~43 chars after base64). */
  private static String generateToken() {
    byte[] buf = new byte[32];
    RNG.nextBytes(buf);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
  }
}

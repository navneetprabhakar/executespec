package com.navneet.executespec.sharing.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.OrgMember;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.entity.ProjectMember;
import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgMemberRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.auth.service.MailService;
import com.navneet.executespec.auth.service.TierEnforcementService;
import com.navneet.executespec.sharing.entity.ProjectInvite;
import com.navneet.executespec.sharing.repository.ProjectInviteRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * V175 — orchestrator for project-only external collaborator invitations.
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
  private static final long DEFAULT_EXPIRY_DAYS = 14;

  private final ProjectInviteRepository inviteRepo;
  private final ProjectRepository projectRepo;
  private final ProjectMemberRepository memberRepo;
  private final AppUserRepository userRepo;
  private final TierConfigRepository tierConfigRepo;
  private final OrgRepository orgRepo;
  private final OrgMemberRepository orgMemberRepo;
  private final MailService mailService;

  /**
   * Retained for the static {@code isPaidTier} authority and for constructor compatibility. RR-973
   * deliberately stopped calling {@code resolveUserTier} here: that resolver maxes in every
   * external host-org tier the caller has collected, so using it to gate a HOST-scoped invite would
   * let entitlement earned in one tenant authorise sharing in another.
   */
  @SuppressWarnings("unused")
  private final TierEnforcementService tierEnforcementService;

  /**
   * RL-2026-05-041 (audit #11) — project-only external collaborator changes belong in the project
   * activity feed.
   */
  private final com.navneet.executespec.activity.ActivityEventEmitter activityEmitter;

  /**
   * RL-2026-05-066 — in-app notification for an invitee who already has an account (the email link
   * alone was missable). Best-effort; never fails the invite.
   */
  private final com.navneet.executespec.notification.InAppNotificationService notificationService;

  /** Public-facing base URL — used to build accept-invite links in the email body. */
  @Value("${app.base-url:http://localhost:5173}")
  private String appBaseUrl;

  /**
   * RR-974 — the host-side admin authority that every invite management operation (create / list /
   * revoke) requires, independent of the flag + entitlement gates that only creation adds. Read or
   * write access to a project is not authority to see pending-recipient PII or to revoke standing
   * grants: an external project VIEWER could enumerate invitee emails/roles, and a project
   * DEVELOPER/external writer could revoke invitations they lack the authority to create. The
   * decision is an <b>org-level</b> one about the HOST org whose resources are being shared, so it
   * demands ORG_ADMIN of the host org (or SUPER_ADMIN). PROJECT_ADMIN is deliberately insufficient,
   * and being an org admin of a <i>different</i> tenant carries no weight here.
   *
   * <p>Deliberately does NOT require the {@code external_sharing_enabled} flag or a paid tier:
   * disabling sharing or letting entitlement lapse must not strand existing invites — a host admin
   * still needs to list and revoke them.
   *
   * @return the resolved host {@link Project} so callers can reuse it without a second lookup.
   */
  Project assertHostInviteAdmin(AppUser actor, String projectId) {
    if (actor == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
    }
    // Resolve the HOST first — the authority question is about the host org.
    Project project =
        projectRepo
            .findById(projectId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    if ("SUPER_ADMIN".equals(actor.getGlobalRole())) {
      return project;
    }
    String hostOrgId = project.getOrgId();
    if (hostOrgId == null || hostOrgId.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "This project has no owning organisation, so external collaborator policy can't be"
              + " resolved for it.");
    }
    // Role gate — ORG_ADMIN of the HOST org. orgRole lives on OrgMember, not AppUser.
    OrgMember membership =
        orgMemberRepo.findByOrgIdAndUserId(hostOrgId, actor.getId()).orElse(null);
    if (membership == null || !"ORG_ADMIN".equals(membership.getOrgRole())) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "Only an admin of the organisation that owns this project can manage its external"
              + " collaborator invitations.");
    }
    return project;
  }

  /**
   * Validate that the inviter is allowed to create an external invite <b>into this project</b>.
   *
   * <p><b>RR-973 (2026-07-21) — the host project's org decides, not the inviter's.</b> Authority
   * previously came entirely from the inviter's OWN home-org role and their OWN {@code
   * external_sharing_enabled} flag. An external collaborator who happened to be ORG_ADMIN of their
   * own paid, sharing-enabled org could therefore hand arbitrary outsiders — up to PROJECT_ADMIN —
   * access to <i>another tenant's</i> project. Read/write visibility of a project is not authority
   * to delegate access to it; the grant is against the host, so the host's policy governs.
   *
   * <p>Gates, all evaluated against the <b>host</b> org that owns the project: the shared {@link
   * #assertHostInviteAdmin} role + host-resolution gate, then the HOST org's V198 {@code
   * external_sharing_enabled} flag, then the paid-entitlement gate judged on the host org (with the
   * proven host admin's own personal tier admitted only as a floor). SUPER_ADMIN bypasses the flag
   * and entitlement gates — support/ops provisioning a cross-org collaborator.
   */
  void assertCanInvite(AppUser inviter, String projectId) {
    // Role + host-resolution gate (shared with list/revoke). SUPER_ADMIN passes the role gate here
    // and the org-flag + entitlement gates below are skipped for it.
    Project project = assertHostInviteAdmin(inviter, projectId);
    if ("SUPER_ADMIN".equals(inviter.getGlobalRole())) {
      // SUPER_ADMIN bypasses the org-flag + entitlement gates — handled centrally by ops/support.
      return;
    }
    String hostOrgId = project.getOrgId();

    // Org-flag gate — the HOST org must allow project-only external collaborators.
    Org hostOrg = orgRepo.findById(hostOrgId).orElse(null);
    if (hostOrg == null || !hostOrg.isExternalSharingEnabled()) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "Project external collaborators are disabled for the organisation that owns this"
              + " project. Enable them in Org Settings → Project external collaborators before"
              + " sending project invites.");
    }

    // Entitlement gate — paid feature, judged on the host org.
    // RR-1508 — ask the single tier authority whether the resolved tier is paid. The old hardcoded
    // Set.of("PRO","TEAM","MAX","ENTERPRISE") omitted V253's first-class PRO_PLUS and BYOK, so a
    // paying Pro+/BYOK customer was told to upgrade, and still honoured retired MAX.
    String tier = hostEntitlementTier(hostOrg, inviter);
    if (!TierEnforcementService.isPaidTier(tier)) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "Inviting external collaborators is a paid feature — upgrade to a paid plan.");
    }
    Optional<TierConfig> cfg = tierConfigRepo.findById(tier);
    if (cfg.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tier config not found");
    }
  }

  /**
   * RR-973 — the entitlement backing a host-org invite. The host org's own tier is authoritative;
   * the inviter's PERSONAL tier is admitted only as a floor, and only because the caller has
   * already been proven ORG_ADMIN of this same host org. That keeps a solo customer working when
   * their auto-provisioned personal org still carries the FREE tier it was stamped with at signup
   * (V162 mirrors the user's tier at bootstrap and does not follow later upgrades), without letting
   * entitlement be borrowed from any org other than the host.
   */
  private String hostEntitlementTier(Org hostOrg, AppUser inviter) {
    String hostTier = normalizeTier(hostOrg.getTier());
    if (TierEnforcementService.isPaidTier(hostTier)) return hostTier;
    String inviterOwnTier = normalizeTier(inviter.getTier());
    return TierEnforcementService.isPaidTier(inviterOwnTier) ? inviterOwnTier : hostTier;
  }

  private static String normalizeTier(String tier) {
    if (tier == null || tier.isBlank()) return "FREE";
    String normalized = tier.trim().toUpperCase();
    // "MAX" was retired into TEAM; keep legacy rows resolving to something the tier authority
    // knows.
    return "MAX".equals(normalized) ? "TEAM" : normalized;
  }

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
              // RR-973 — record the HOST org whose resources are being shared, not the inviter's
              // home org. The column exists ("captured at invite time so accept can validate even
              // if the org rotates", V175) precisely so acceptance can re-consult the granting
              // tenant; storing the inviter's org made it useless for that.
              .invitedOrgId(project.getOrgId())
              .status("PENDING")
              .expiresAt(Instant.now().plusSeconds(DEFAULT_EXPIRY_DAYS * 24 * 3600))
              .createdAt(Instant.now())
              .build();
    }
    final ProjectInvite savedInvite = inviteRepo.save(invite);
    final String token = savedInvite.getToken();
    final Optional<AppUser> recipientUser = existingUser;

    // RR-977 — the emailed link and the in-app notification are external, irreversible side effects
    // that must not be published until this transaction actually commits. Dispatching them inline
    // meant a recipient could receive a working link (or an in-app notification) for an invite that
    // subsequently rolled back. Defer both to after-commit; when there is no active transaction
    // (e.g. a direct service-layer call) run inline so behaviour is unchanged.
    runAfterCommit(
        () -> {
          // Best-effort email — never surface a mailer outage as an invite failure.
          try {
            mailService.sendProjectInviteEmail(
                normalizedEmail,
                project.getName(),
                inviter.getEmail(),
                appBaseUrl + "/invites/accept?token=" + token,
                DEFAULT_EXPIRY_DAYS);
          } catch (Exception e) {
            log.warn("ProjectInvite email send failed for {}: {}", normalizedEmail, e.getMessage());
          }

          // RL-2026-05-066 — in-app notification when the invitee already has an account, so the
          // invite isn't only discoverable via email. recipientUser is either empty (no account
          // yet — they get the email + sign up) or present-and-not-yet-a-member (the already-member
          // case threw CONFLICT above). Best-effort. The link matches the email + /invites/accept.
          if (recipientUser.isPresent()) {
            try {
              notificationService.create(
                  recipientUser.get().getId(),
                  "PROJECT_INVITE",
                  "Invited to " + project.getName(),
                  (inviter.getEmail() == null ? "A teammate" : inviter.getEmail())
                      + " invited you to "
                      + project.getName()
                      + " as "
                      + resolvedRole
                      + ".",
                  "/invites/accept?token=" + token);
            } catch (Exception e) {
              log.warn(
                  "ProjectInvite in-app notification failed for {}: {}",
                  normalizedEmail,
                  e.getMessage());
            }
          }
        });
    return savedInvite;
  }

  /**
   * Run an irreversible external side effect only after the surrounding transaction commits (RR-977
   * / RL-2026-05-041). With no active transaction — a direct service call or a unit test — the
   * action runs inline so behaviour is identical for non-transactional callers.
   */
  private void runAfterCommit(Runnable action) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              action.run();
            }
          });
    } else {
      action.run();
    }
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

    // RR-973 — revalidate the HOST org's policy at acceptance, not only at issue. A pending invite
    // is a standing grant against another tenant's project; if that tenant has external sharing
    // disabled (including invites minted before host authority was enforced), the grant must not
    // complete. The check is on the project's CURRENT owning org, so a project that moved tenants
    // is judged by the tenant that holds it now.
    assertHostStillPermitsExternalCollaborators(invite.getProjectId());

    // Idempotent — if a member row already exists, mark accepted + return.
    boolean memberAdded = false;
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
      memberAdded = true;
    }
    invite.setStatus("ACCEPTED");
    invite.setAcceptedAt(Instant.now());
    invite.setAcceptedByUserId(caller.getId());
    inviteRepo.save(invite);

    // RL-2026-05-041 (audit #11) — record the external member joining on the project activity feed.
    // RR-977 — defer to after-commit so the feed never claims a membership that rolled back.
    if (memberAdded) {
      final ProjectInvite acceptedInvite = invite;
      runAfterCommit(() -> emitMemberAdded(acceptedInvite, caller));
    }
    return invite.getProjectId();
  }

  /**
   * RR-973 — the host-side half of the invite contract, evaluated at acceptance. Refuses when the
   * project has vanished, has no owning org, or that org has external collaborators disabled.
   */
  private void assertHostStillPermitsExternalCollaborators(String projectId) {
    Project project =
        projectRepo
            .findById(projectId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.GONE, "Project no longer exists"));
    String hostOrgId = project.getOrgId();
    Org hostOrg = hostOrgId == null ? null : orgRepo.findById(hostOrgId).orElse(null);
    if (hostOrg == null || !hostOrg.isExternalSharingEnabled()) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "The organisation that owns this project no longer allows external collaborators.");
    }
  }

  @Transactional
  public void revoke(String projectId, UUID inviteId, AppUser actor) {
    ProjectInvite invite =
        inviteRepo
            .findById(inviteId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invite not found"));
    if (!invite.getProjectId().equals(projectId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Invite not found");
    }
    // RR-974 — revoking a standing cross-org grant is host-admin authority, not mere project write.
    assertHostInviteAdmin(actor, projectId);
    if (!"PENDING".equals(invite.getStatus())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Invite is not pending");
    }
    invite.setStatus("REVOKED");
    invite.setRevokedAt(Instant.now());
    invite.setRevokedByUserId(actor == null ? null : actor.getId());
    inviteRepo.save(invite);
  }

  /**
   * RL-2026-05-041 (audit #11) — emit MEMBER_ADDED to the project activity feed when an external
   * collaborator accepts an invite. MEMBER_ADDED is HIGH_SIGNAL (feed-only); the cross-org grant is
   * also captured in the immutable {@code activity_audit} trail via the existing org/auth audit
   * paths. Best-effort: the emitter is @Async + self-swallowing.
   */
  private void emitMemberAdded(ProjectInvite invite, AppUser caller) {
    try {
      UUID projectUuid = UUID.fromString(invite.getProjectId());
      activityEmitter.emit(
          projectUuid,
          com.navneet.executespec.activity.ActivityEventType.MEMBER_ADDED,
          caller.getId() == null ? null : UUID.fromString(caller.getId()),
          "MEMBER",
          caller.getId(),
          (caller.getEmail() == null ? "A collaborator" : caller.getEmail())
              + " joined as "
              + invite.getProjectRole()
              + " (external)",
          java.util.Map.of(
              "userId", caller.getId() == null ? "" : caller.getId(),
              "email", caller.getEmail() == null ? "" : caller.getEmail(),
              "projectRole", invite.getProjectRole() == null ? "" : invite.getProjectRole(),
              "external", true));
    } catch (Exception e) {
      log.warn("[ProjectInviteService] MEMBER_ADDED emit failed: {}", e.getMessage());
    }
  }

  /**
   * List still-actionable pending invites for a project.
   *
   * <p>RR-974 — the rows carry recipient PII (email / role / inviter / expiry), so listing requires
   * host invite-admin authority rather than plain project read access.
   *
   * <p>RR-979 — a row whose {@code expiresAt} has passed is no longer a live grant, but the status
   * column only flips to EXPIRED lazily on an accept attempt. Filter expired rows out here so an
   * admin's pending list reflects what is actually still acceptable instead of stale phantoms.
   */
  public List<ProjectInvite> listPending(String projectId, AppUser actor) {
    assertHostInviteAdmin(actor, projectId);
    Instant now = Instant.now();
    return inviteRepo.findByProjectIdAndStatusOrderByCreatedAtDesc(projectId, "PENDING").stream()
        .filter(i -> i.getExpiresAt() == null || i.getExpiresAt().isAfter(now))
        .toList();
  }

  public Optional<ProjectInvite> findByToken(String token) {
    return inviteRepo.findByToken(token);
  }

  /** 32-byte URL-safe random token (~43 chars after base64). */
  private static String generateToken() {
    byte[] buf = new byte[32];
    RNG.nextBytes(buf);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
  }
}

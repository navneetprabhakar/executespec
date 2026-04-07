package com.navneet.executespec.auth.service;

import com.navneet.executespec.auth.dto.InviteRequest;
import com.navneet.executespec.auth.entity.*;
import com.navneet.executespec.auth.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class InvitationService {

    private final OrgInvitationRepository invitationRepo;
    private final OrgRepository orgRepo;
    private final OrgMemberRepository orgMemberRepo;
    private final AppUserRepository userRepo;
    private final ProjectMemberRepository projectMemberRepo;
    private final TierConfigRepository tierConfigRepo;
    private final MailService mailService;
    private final ActivityAuditService auditService;

    @Value("${app.mail.base-url:http://localhost:5173}")
    private String baseUrl;

    private static final String SUPER_ADMIN_EMAIL = "admin@executespec.ai";

    // ── Common consumer email domains (blocked for org invitations) ──────────
    private static final Set<String> BLOCKED_DOMAINS = Set.of(
            "gmail.com", "googlemail.com", "yahoo.com", "yahoo.co.in", "yahoo.co.uk",
            "hotmail.com", "outlook.com", "live.com", "msn.com", "aol.com",
            "icloud.com", "me.com", "mac.com", "protonmail.com", "proton.me",
            "zoho.com", "yandex.com", "mail.com", "gmx.com", "gmx.net",
            "fastmail.com", "tutanota.com", "hey.com", "pm.me",
            "rediffmail.com", "inbox.com", "mail.ru", "rambler.ru",
            "qq.com", "163.com", "126.com", "sina.com",
            "naver.com", "daum.net", "hanmail.net",
            "web.de", "t-online.de", "laposte.net", "orange.fr",
            "libero.it", "virgilio.it", "seznam.cz",
            "comcast.net", "verizon.net", "att.net", "sbcglobal.net",
            "cox.net", "charter.net", "earthlink.net",
            "btinternet.com", "sky.com", "talktalk.net",
            "rogers.com", "shaw.ca", "bell.net",
            "bigpond.com", "optusnet.com.au"
    );

    @Transactional
    public OrgInvitation createInvitation(String orgId, String invitedByUserId, InviteRequest req) {
        Org org = orgRepo.findById(orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));

        String email = req.email().toLowerCase();
        String orgRole = req.orgRole() != null ? req.orgRole() : "DEVELOPER";
        String projectIdsStr = req.projectIds() != null
                ? String.join(",", req.projectIds())
                : null;

        // ── Common domain blocking ──────────────────────────────────────────
        // ENTERPRISE: always blocked. PRO: blocked only if admin has enabled it.
        boolean shouldBlockCommon = "ENTERPRISE".equals(org.getTier()) || org.isBlockCommonDomains();
        if (shouldBlockCommon && isCommonDomain(email)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Consumer email domains (e.g. gmail.com, yahoo.com) cannot be used for org invitations. " +
                    "Please use a work email address.");
        }

        // ── Member limit check ──────────────────────────────────────────────
        int maxMembers = resolveMaxMembers(org);
        if (maxMembers > 0 && orgMemberRepo.countByOrgId(orgId) >= maxMembers) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Organization has reached its member limit (" + maxMembers + "). " +
                    "Upgrade to ENTERPRISE for unlimited members.");
        }

        String token = UUID.randomUUID().toString().replace("-", "");
        boolean domainMatches = domainMatches(email, org.getDomainPatterns());

        // ── Domain enforcement mode ─────────────────────────────────────────
        // ENTERPRISE is always ENFORCED; PRO respects the stored flag.
        String effectiveMode = "ENTERPRISE".equals(org.getTier())
                ? "ENFORCED"
                : (org.getDomainEnforcementMode() != null ? org.getDomainEnforcementMode() : "OPEN");

        if (!domainMatches && "ENFORCED".equals(effectiveMode)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only emails matching org domain patterns (" + org.getDomainPatterns() + ") are allowed. " +
                    "Domain enforcement is set to ENFORCED.");
        }

        String status = domainMatches ? "PENDING" : "ESCALATED";

        OrgInvitation invitation = OrgInvitation.builder()
                .id(UUID.randomUUID().toString())
                .orgId(orgId)
                .invitedEmail(email)
                .invitedBy(invitedByUserId)
                .orgRole(orgRole)
                .token(token)
                .status(status)
                .projectIds(projectIdsStr)
                .expiresAt(Instant.now().plus(7, ChronoUnit.DAYS))
                .build();

        if (!domainMatches) {
            invitation.setEscalationNote("Email domain not in org's registered domains: " + org.getDomainPatterns());
            log.warn("Invitation for {} escalated — domain mismatch for org {}", email, orgId);
        }

        invitationRepo.save(invitation);

        if (domainMatches) {
            mailService.sendOrgInviteEmail(email, org.getName(), token);
            auditService.record(ActivityAudit.builder()
                    .eventType("INVITATION_CREATED")
                    .entityType("INVITATION").entityId(invitation.getId())
                    .orgId(orgId).actorId(invitedByUserId)
                    .newValue("{\"email\":\"" + email + "\",\"role\":\"" + orgRole + "\"}")
                    .build());
        } else {
            mailService.sendEscalationToAdmin(SUPER_ADMIN_EMAIL, email, org.getName(), invitation.getId());
            auditService.record(ActivityAudit.builder()
                    .eventType("INVITATION_ESCALATED")
                    .entityType("INVITATION").entityId(invitation.getId())
                    .orgId(orgId).actorId(invitedByUserId)
                    .newValue("{\"email\":\"" + email + "\",\"reason\":\"domain mismatch\"}")
                    .build());
        }

        return invitation;
    }

    @Transactional
    public void approveEscalation(String invitationId, String adminUserId) {
        OrgInvitation invitation = invitationRepo.findById(invitationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation not found"));
        if (!"ESCALATED".equals(invitation.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invitation is not in ESCALATED state");
        }

        invitation.setStatus("PENDING");
        invitationRepo.save(invitation);

        Org org = orgRepo.findById(invitation.getOrgId()).orElseThrow();
        mailService.sendOrgInviteEmail(invitation.getInvitedEmail(), org.getName(), invitation.getToken());

        auditService.record(ActivityAudit.builder()
                .eventType("INVITATION_APPROVED")
                .entityType("INVITATION").entityId(invitationId)
                .orgId(invitation.getOrgId()).actorId(adminUserId)
                .newValue("{\"email\":\"" + invitation.getInvitedEmail() + "\"}")
                .build());

        log.info("Escalated invitation {} approved by {}", invitationId, adminUserId);
    }

    @Transactional
    public void rejectEscalation(String invitationId, String adminUserId) {
        OrgInvitation invitation = invitationRepo.findById(invitationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation not found"));
        if (!"ESCALATED".equals(invitation.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invitation is not in ESCALATED state");
        }

        invitation.setStatus("REJECTED");
        invitation.setResolvedAt(Instant.now());
        invitationRepo.save(invitation);

        // Notify org admin
        userRepo.findById(invitation.getInvitedBy()).ifPresent(admin ->
            mailService.sendInviteRejectedEmail(admin.getEmail(), invitation.getInvitedEmail())
        );

        auditService.record(ActivityAudit.builder()
                .eventType("INVITATION_REJECTED")
                .entityType("INVITATION").entityId(invitationId)
                .orgId(invitation.getOrgId()).actorId(adminUserId)
                .newValue("{\"email\":\"" + invitation.getInvitedEmail() + "\"}")
                .build());

        log.info("Escalated invitation {} rejected by {}", invitationId, adminUserId);
    }

    @Transactional
    public void acceptInvitation(String token, String currentUserId) {
        OrgInvitation invitation = invitationRepo.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation not found"));

        // Idempotent: if already accepted, return success silently
        if ("ACCEPTED".equals(invitation.getStatus())) {
            log.info("Invitation {} already accepted — returning success (idempotent)", invitation.getId());
            return;
        }

        if (!"PENDING".equals(invitation.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invitation is " + invitation.getStatus() + " and cannot be accepted");
        }
        if (invitation.getExpiresAt().isBefore(Instant.now())) {
            invitation.setStatus("EXPIRED");
            invitationRepo.save(invitation);
            throw new ResponseStatusException(HttpStatus.GONE, "Invitation has expired");
        }

        // Resolve the user
        String userId;
        if (currentUserId != null) {
            userId = currentUserId;
        } else {
            // User must have just registered via the abbreviated invite form
            AppUser existing = userRepo.findByEmail(invitation.getInvitedEmail()).orElse(null);
            if (existing == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Please register first then accept the invitation");
            }
            userId = existing.getId();
        }

        AppUser user = userRepo.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        if (user.getOrgId() != null && !user.getOrgId().equals(invitation.getOrgId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User is already a member of another org");
        }

        // Add to org — catch constraint violation from concurrent/duplicate requests
        if (!orgMemberRepo.existsByOrgIdAndUserId(invitation.getOrgId(), userId)) {
            try {
                orgMemberRepo.save(OrgMember.builder()
                        .id(UUID.randomUUID().toString())
                        .orgId(invitation.getOrgId())
                        .userId(userId)
                        .orgRole(invitation.getOrgRole())
                        .build());
                orgMemberRepo.flush();
            } catch (DataIntegrityViolationException e) {
                log.warn("OrgMember already exists for org={} user={} (race condition) — continuing",
                        invitation.getOrgId(), userId);
            }
        }

        // Update user's org_id and inherit org tier
        user.setOrgId(invitation.getOrgId());
        orgRepo.findById(invitation.getOrgId()).ifPresent(org -> {
            if (org.getTier() != null) {
                user.setTier(org.getTier());
            }
        });
        user.setUpdatedAt(Instant.now());
        userRepo.save(user);

        // Auto-assign to projects
        if (invitation.getProjectIds() != null && !invitation.getProjectIds().isBlank()) {
            List<String> projectIds = Arrays.asList(invitation.getProjectIds().split(","));
            for (String pid : projectIds) {
                String trimmedPid = pid.trim();
                if (trimmedPid.isEmpty()) continue;
                if (!projectMemberRepo.existsByProjectIdAndUserId(trimmedPid, userId)) {
                    try {
                        projectMemberRepo.save(ProjectMember.builder()
                                .id(UUID.randomUUID().toString())
                                .projectId(trimmedPid)
                                .userId(userId)
                                .projectRole("PROJECT_DEVELOPER")
                                .build());
                        projectMemberRepo.flush();
                    } catch (DataIntegrityViolationException e) {
                        log.warn("ProjectMember already exists or project {} not found — skipping", trimmedPid);
                    }
                }
            }
        }

        invitation.setStatus("ACCEPTED");
        invitation.setResolvedAt(Instant.now());
        invitationRepo.save(invitation);

        auditService.record(ActivityAudit.builder()
                .eventType("INVITATION_ACCEPTED")
                .entityType("INVITATION").entityId(invitation.getId())
                .orgId(invitation.getOrgId()).actorId(userId)
                .newValue("{\"email\":\"" + invitation.getInvitedEmail() + "\",\"userId\":\"" + userId + "\"}")
                .build());

        auditService.record(ActivityAudit.builder()
                .eventType("ORG_MEMBER_ADDED")
                .entityType("ORG_MEMBER").entityId(userId)
                .orgId(invitation.getOrgId()).actorId(userId)
                .newValue("{\"userId\":\"" + userId + "\",\"role\":\"" + invitation.getOrgRole()
                        + "\",\"email\":\"" + invitation.getInvitedEmail() + "\"}")
                .build());

        log.info("Invitation {} accepted by user {}", invitation.getId(), userId);
    }

    /**
     * Super-admin direct invite — bypasses domain check entirely.
     * The target org must already exist (caller resolves org creation before calling this).
     */
    @Transactional
    public OrgInvitation createAdminInvitation(String orgId, String invitedEmail,
                                               String orgRole, String adminUserId) {
        Org org = orgRepo.findById(orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));

        String email = invitedEmail.toLowerCase();
        String token = UUID.randomUUID().toString().replace("-", "");

        OrgInvitation invitation = OrgInvitation.builder()
                .id(UUID.randomUUID().toString())
                .orgId(orgId)
                .invitedEmail(email)
                .invitedBy(adminUserId)
                .orgRole(orgRole != null ? orgRole : "DEVELOPER")
                .token(token)
                .status("PENDING")
                .expiresAt(Instant.now().plus(7, ChronoUnit.DAYS))
                .build();

        invitationRepo.save(invitation);
        mailService.sendOrgInviteEmail(email, org.getName(), token);

        auditService.record(ActivityAudit.builder()
                .eventType("ADMIN_INVITATION_CREATED")
                .entityType("INVITATION").entityId(invitation.getId())
                .orgId(orgId).actorId(adminUserId)
                .newValue("{\"email\":\"" + email + "\",\"orgId\":\"" + orgId + "\"}")
                .build());

        log.info("Admin invite sent to {} for org {} by {}", email, orgId, adminUserId);
        return invitation;
    }

    /**
     * Resend an invitation email. Resets expiry to 7 days from now.
     * Works for PENDING and EXPIRED invitations (re-activates expired ones).
     */
    @Transactional
    public void resendInvitation(String invitationId, String actorUserId) {
        OrgInvitation invitation = invitationRepo.findById(invitationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation not found"));

        if ("ACCEPTED".equals(invitation.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invitation has already been accepted");
        }
        if ("REJECTED".equals(invitation.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invitation was rejected");
        }
        if ("ESCALATED".equals(invitation.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invitation is awaiting super-admin approval and cannot be resent");
        }

        // Re-activate expired invitations, reset expiry for pending ones
        invitation.setStatus("PENDING");
        invitation.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
        invitationRepo.save(invitation);

        Org org = orgRepo.findById(invitation.getOrgId()).orElseThrow();
        mailService.sendOrgInviteEmail(invitation.getInvitedEmail(), org.getName(), invitation.getToken());

        auditService.record(ActivityAudit.builder()
                .eventType("INVITATION_RESENT")
                .entityType("INVITATION").entityId(invitationId)
                .orgId(invitation.getOrgId()).actorId(actorUserId)
                .newValue("{\"email\":\"" + invitation.getInvitedEmail() + "\"}")
                .build());

        log.info("Invitation {} resent to {} by {}", invitationId, invitation.getInvitedEmail(), actorUserId);
    }

    public List<OrgInvitation> listEscalated() {
        return invitationRepo.findByStatus("ESCALATED");
    }

    public List<OrgInvitation> listByOrg(String orgId) {
        return invitationRepo.findByOrgId(orgId);
    }

    // ── domain matching ───────────────────────────────────────────────────────

    private boolean domainMatches(String email, String domainPatterns) {
        if (domainPatterns == null || domainPatterns.isBlank()) return false;
        String emailLower = email.toLowerCase();
        return Arrays.stream(domainPatterns.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .anyMatch(d -> emailLower.endsWith("@" + d));
    }

    /** Check if the email belongs to a consumer/free email provider. */
    private boolean isCommonDomain(String email) {
        int at = email.lastIndexOf('@');
        if (at < 0) return false;
        String domain = email.substring(at + 1).toLowerCase();
        return BLOCKED_DOMAINS.contains(domain);
    }

    /** Resolve max org members: per-org override → tier_config default. */
    private int resolveMaxMembers(Org org) {
        if (org.getMaxMembers() != null) return org.getMaxMembers();
        return tierConfigRepo.findById(org.getTier())
                .map(com.navneet.executespec.auth.entity.TierConfig::getMaxOrgMembers)
                .orElse(-1);
    }
}

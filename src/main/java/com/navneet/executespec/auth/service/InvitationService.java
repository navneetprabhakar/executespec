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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
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
    private final MailService mailService;

    @Value("${app.mail.base-url:http://localhost:5173}")
    private String baseUrl;

    private static final String SUPER_ADMIN_EMAIL = "admin@executespec.ai";

    @Transactional
    public OrgInvitation createInvitation(String orgId, String invitedByUserId, InviteRequest req) {
        Org org = orgRepo.findById(orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));

        String email = req.email().toLowerCase();
        String orgRole = req.orgRole() != null ? req.orgRole() : "DEVELOPER";
        String projectIdsStr = req.projectIds() != null
                ? String.join(",", req.projectIds())
                : null;

        String token = UUID.randomUUID().toString().replace("-", "");
        boolean domainMatches = domainMatches(email, org.getDomainPatterns());
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
        } else {
            mailService.sendEscalationToAdmin(SUPER_ADMIN_EMAIL, email, org.getName(), invitation.getId());
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
        log.info("Escalated invitation {} rejected by {}", invitationId, adminUserId);
    }

    @Transactional
    public void acceptInvitation(String token, String currentUserId) {
        OrgInvitation invitation = invitationRepo.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation not found"));

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

        // Add to org
        if (!orgMemberRepo.existsByOrgIdAndUserId(invitation.getOrgId(), userId)) {
            orgMemberRepo.save(OrgMember.builder()
                    .id(UUID.randomUUID().toString())
                    .orgId(invitation.getOrgId())
                    .userId(userId)
                    .orgRole(invitation.getOrgRole())
                    .build());
        }

        // Update user's org_id
        user.setOrgId(invitation.getOrgId());
        user.setUpdatedAt(Instant.now());
        userRepo.save(user);

        // Auto-assign to projects
        if (invitation.getProjectIds() != null && !invitation.getProjectIds().isBlank()) {
            List<String> projectIds = Arrays.asList(invitation.getProjectIds().split(","));
            for (String pid : projectIds) {
                if (!projectMemberRepo.existsByProjectIdAndUserId(pid.trim(), userId)) {
                    projectMemberRepo.save(ProjectMember.builder()
                            .id(UUID.randomUUID().toString())
                            .projectId(pid.trim())
                            .userId(userId)
                            .projectRole("PROJECT_DEVELOPER")
                            .build());
                }
            }
        }

        invitation.setStatus("ACCEPTED");
        invitation.setResolvedAt(Instant.now());
        invitationRepo.save(invitation);

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
        log.info("Admin invite sent to {} for org {} by {}", email, orgId, adminUserId);
        return invitation;
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
}

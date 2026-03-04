package com.navneet.agentteam.auth.controller;

import com.navneet.agentteam.auth.dto.AdminInviteRequest;
import com.navneet.agentteam.auth.dto.OrgDto;
import com.navneet.agentteam.auth.dto.UserDto;
import com.navneet.agentteam.auth.entity.AppUser;
import com.navneet.agentteam.auth.entity.Org;
import com.navneet.agentteam.auth.entity.OrgInvitation;
import com.navneet.agentteam.auth.entity.TierConfig;
import com.navneet.agentteam.auth.repository.AppUserRepository;
import com.navneet.agentteam.auth.repository.OrgMemberRepository;
import com.navneet.agentteam.auth.repository.TierConfigRepository;
import com.navneet.agentteam.auth.security.AppUserDetails;
import com.navneet.agentteam.auth.service.InvitationService;
import com.navneet.agentteam.auth.service.OrgService;
import com.navneet.agentteam.auth.service.PaymentAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class SuperAdminController {

    private final AppUserRepository userRepo;
    private final OrgMemberRepository orgMemberRepo;
    private final OrgService orgService;
    private final InvitationService invitationService;
    private final PaymentAuditService paymentAuditService;
    private final TierConfigRepository tierConfigRepo;

    // ── Users ─────────────────────────────────────────────────────────────────

    @GetMapping("/users")
    public ResponseEntity<List<UserDto>> listUsers() {
        List<UserDto> users = userRepo.findAllActive().stream()
                .map(u -> {
                    var member = u.getOrgId() != null
                            ? orgMemberRepo.findByOrgIdAndUserId(u.getOrgId(), u.getId()).orElse(null)
                            : null;
                    return UserDto.from(u, member);
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(users);
    }

    @GetMapping("/users/{id}")
    public ResponseEntity<UserDto> getUser(@PathVariable String id) {
        AppUser user = userRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        var member = user.getOrgId() != null
                ? orgMemberRepo.findByOrgIdAndUserId(user.getOrgId(), user.getId()).orElse(null)
                : null;
        return ResponseEntity.ok(UserDto.from(user, member));
    }

    @PutMapping("/users/{id}/status")
    public ResponseEntity<UserDto> updateUserStatus(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        AppUser user = userRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        user.setStatus(body.get("status"));
        user.setUpdatedAt(Instant.now());
        userRepo.save(user);
        return ResponseEntity.ok(UserDto.from(user, null));
    }

    @PutMapping("/users/{id}/tier")
    public ResponseEntity<UserDto> updateUserTier(
            @AuthenticationPrincipal AppUserDetails adminDetails,
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        AppUser user = userRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        String oldTier = user.getTier();
        user.setTier(body.get("tier"));
        user.setUpdatedAt(Instant.now());
        userRepo.save(user);
        paymentAuditService.record("USER", id, "PLAN_UPGRADED", oldTier, body.get("tier"),
                adminDetails.getUser().getId(), null);
        return ResponseEntity.ok(UserDto.from(user, null));
    }

    @PutMapping("/users/{id}/role")
    public ResponseEntity<UserDto> updateUserRole(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        AppUser user = userRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        user.setGlobalRole(body.get("role"));
        user.setUpdatedAt(Instant.now());
        userRepo.save(user);
        return ResponseEntity.ok(UserDto.from(user, null));
    }

    // ── Orgs ─────────────────────────────────────────────────────────────────

    @GetMapping("/orgs")
    public ResponseEntity<List<OrgDto>> listOrgs() {
        return ResponseEntity.ok(orgService.listAllOrgs());
    }

    @GetMapping("/orgs/{id}")
    public ResponseEntity<OrgDto> getOrg(@PathVariable String id) {
        return ResponseEntity.ok(orgService.getOrg(id));
    }

    @PutMapping("/orgs/{id}/status")
    public ResponseEntity<OrgDto> updateOrgStatus(
            @AuthenticationPrincipal AppUserDetails adminDetails,
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        orgService.changeOrgStatus(id, body.get("status"), adminDetails.getUser().getId());
        return ResponseEntity.ok(orgService.getOrg(id));
    }

    @PutMapping("/orgs/{id}/tier")
    public ResponseEntity<OrgDto> updateOrgTier(
            @AuthenticationPrincipal AppUserDetails adminDetails,
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        orgService.changeOrgTier(id, body.get("tier"), adminDetails.getUser().getId());
        return ResponseEntity.ok(orgService.getOrg(id));
    }

    /** Override (or clear) the per-org monthly run limit. Null limit resets to tier default. */
    @PutMapping("/orgs/{id}/run-limit")
    public ResponseEntity<OrgDto> updateOrgRunLimit(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        Object raw = body.get("limit");
        Integer limit = raw == null ? null : ((Number) raw).intValue();
        return ResponseEntity.ok(orgService.updateRunLimitOverride(id, limit));
    }

    /** Toggle the api_keys_provided flag for an enterprise org. */
    @PutMapping("/orgs/{id}/api-keys")
    public ResponseEntity<OrgDto> updateOrgApiKeys(
            @PathVariable String id,
            @RequestBody Map<String, Boolean> body) {
        boolean provided = Boolean.TRUE.equals(body.get("provided"));
        return ResponseEntity.ok(orgService.updateApiKeysProvided(id, provided));
    }

    // ── Tier Config ───────────────────────────────────────────────────────────

    @GetMapping("/tier-config")
    public ResponseEntity<List<TierConfig>> listTierConfig() {
        return ResponseEntity.ok(tierConfigRepo.findAll());
    }

    @PutMapping("/tier-config/{tier}")
    public ResponseEntity<TierConfig> updateTierConfig(
            @PathVariable String tier,
            @RequestBody Map<String, Integer> body) {
        TierConfig config = tierConfigRepo.findById(tier.toUpperCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tier not found: " + tier));
        config.setMonthlyRunLimit(body.get("monthlyRunLimit"));
        return ResponseEntity.ok(tierConfigRepo.save(config));
    }

    // ── Admin Invite ──────────────────────────────────────────────────────────

    @PostMapping("/invite")
    public ResponseEntity<Map<String, String>> adminInvite(
            @AuthenticationPrincipal AppUserDetails adminDetails,
            @RequestBody AdminInviteRequest req) {

        String adminId = adminDetails.getUser().getId();
        String resolvedOrgId = null;

        if (req.newOrgName() != null && !req.newOrgName().isBlank()) {
            Org newOrg = orgService.createOrgAsAdmin(req.newOrgName(), adminId);
            resolvedOrgId = newOrg.getId();
        } else if (req.orgId() != null && !req.orgId().isBlank()) {
            resolvedOrgId = req.orgId();
        }

        if (resolvedOrgId != null) {
            invitationService.createAdminInvitation(resolvedOrgId, req.email(), req.orgRole(), adminId);
        }
        // FREE invite: user registers independently via the normal flow

        return ResponseEntity.ok(Map.of("message", "Invitation sent to " + req.email()));
    }

    // ── Escalated Invitations ─────────────────────────────────────────────────

    @GetMapping("/invitations/escalated")
    public ResponseEntity<List<OrgInvitation>> listEscalated() {
        return ResponseEntity.ok(invitationService.listEscalated());
    }

    @PostMapping("/invitations/{id}/approve")
    public ResponseEntity<Map<String, String>> approveInvitation(
            @AuthenticationPrincipal AppUserDetails adminDetails,
            @PathVariable String id) {
        invitationService.approveEscalation(id, adminDetails.getUser().getId());
        return ResponseEntity.ok(Map.of("message", "Invitation approved and sent to invitee"));
    }

    @PostMapping("/invitations/{id}/reject")
    public ResponseEntity<Map<String, String>> rejectInvitation(
            @AuthenticationPrincipal AppUserDetails adminDetails,
            @PathVariable String id) {
        invitationService.rejectEscalation(id, adminDetails.getUser().getId());
        return ResponseEntity.ok(Map.of("message", "Invitation rejected"));
    }
}

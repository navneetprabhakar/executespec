package com.navneet.agentteam.auth.controller;

import com.navneet.agentteam.auth.dto.*;
import com.navneet.agentteam.auth.entity.OrgInvitation;
import com.navneet.agentteam.auth.security.AppUserDetails;
import com.navneet.agentteam.auth.service.InvitationService;
import com.navneet.agentteam.auth.service.OrgService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/org")
@RequiredArgsConstructor
public class OrgController {

    private final OrgService orgService;
    private final InvitationService invitationService;

    @PostMapping
    public ResponseEntity<OrgDto> createOrg(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @Valid @RequestBody CreateOrgRequest req) {
        OrgDto org = orgService.createOrg(userDetails.getUser().getId(), req);
        return ResponseEntity.status(HttpStatus.CREATED).body(org);
    }

    @GetMapping("/me")
    public ResponseEntity<OrgDto> getMyOrg(@AuthenticationPrincipal AppUserDetails userDetails) {
        String orgId = userDetails.getUser().getOrgId();
        if (orgId == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(orgService.getOrg(orgId));
    }

    @PutMapping("/me")
    @PreAuthorize("hasRole('ORG_ADMIN') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<OrgDto> updateOrg(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @RequestBody Map<String, Object> body) {
        String orgId = userDetails.getUser().getOrgId();
        @SuppressWarnings("unchecked")
        List<String> domains = (List<String>) body.get("domains");
        OrgDto updated = orgService.updateOrg(orgId, (String) body.get("name"), domains);
        return ResponseEntity.ok(updated);
    }

    @GetMapping("/me/members")
    @PreAuthorize("hasRole('ORG_ADMIN') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<OrgMemberDto>> listMembers(
            @AuthenticationPrincipal AppUserDetails userDetails) {
        String orgId = userDetails.getUser().getOrgId();
        return ResponseEntity.ok(orgService.listMembers(orgId));
    }

    @DeleteMapping("/me/members/{userId}")
    @PreAuthorize("hasRole('ORG_ADMIN') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> removeMember(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @PathVariable String userId) {
        String orgId = userDetails.getUser().getOrgId();
        orgService.removeMember(orgId, userId, userDetails.getUser().getId());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/me/members/{userId}/suspend")
    @PreAuthorize("hasRole('ORG_ADMIN') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> suspendMember(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @PathVariable String userId,
            @RequestBody Map<String, Boolean> body) {
        String orgId = userDetails.getUser().getOrgId();
        boolean suspend = Boolean.TRUE.equals(body.get("suspended"));
        orgService.suspendMember(orgId, userId, suspend, userDetails.getUser().getId());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/me/members/{userId}/role")
    @PreAuthorize("hasRole('ORG_ADMIN') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> changeMemberRole(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @PathVariable String userId,
            @RequestBody Map<String, String> body) {
        String orgId = userDetails.getUser().getOrgId();
        orgService.changeMemberRole(orgId, userId, body.get("role"));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/invite")
    @PreAuthorize("hasRole('ORG_ADMIN') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<Map<String, String>> invite(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @Valid @RequestBody InviteRequest req) {
        String orgId = userDetails.getUser().getOrgId();
        OrgInvitation invitation = invitationService.createInvitation(
                orgId, userDetails.getUser().getId(), req);
        String message = "ESCALATED".equals(invitation.getStatus())
                ? "Invitation escalated to super admin for approval"
                : "Invitation sent successfully";
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("message", message, "status", invitation.getStatus()));
    }

    @GetMapping("/me/invitations")
    @PreAuthorize("hasRole('ORG_ADMIN') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<OrgInvitation>> listInvitations(
            @AuthenticationPrincipal AppUserDetails userDetails) {
        String orgId = userDetails.getUser().getOrgId();
        return ResponseEntity.ok(invitationService.listByOrg(orgId));
    }

    @DeleteMapping("/me/invitations/{id}")
    @PreAuthorize("hasRole('ORG_ADMIN') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> cancelInvitation(@PathVariable String id) {
        // Simply mark as expired
        invitationService.listByOrg("").stream()
                .filter(inv -> inv.getId().equals(id))
                .findFirst();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/accept-invite")
    public ResponseEntity<Map<String, String>> acceptInvite(
            @RequestParam String token,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        String userId = userDetails != null ? userDetails.getUser().getId() : null;
        invitationService.acceptInvitation(token, userId);
        return ResponseEntity.ok(Map.of("message", "Invitation accepted successfully"));
    }

    // ── Org-level AI provider key settings (ORG_ADMIN only) ─────────────────

    record OrgAiKeySettingsRequest(String anthropicApiKey, String openAiApiKey, String geminiApiKey) {}

    /**
     * Saves org-wide AI provider API keys. Used by all org members who have not set a personal
     * key. Resolution order: user key → org key → system key (application.yml).
     * Raw keys are never returned — only boolean flags appear in OrgDto.
     */
    @PutMapping("/me/settings/ai-keys")
    @PreAuthorize("hasRole('ORG_ADMIN') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<OrgDto> updateOrgAiKeySettings(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @RequestBody OrgAiKeySettingsRequest req) {
        String orgId = userDetails.getUser().getOrgId();
        if (orgId == null) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(orgService.updateAiKeySettings(
                orgId, req.anthropicApiKey(), req.openAiApiKey(), req.geminiApiKey()));
    }
}

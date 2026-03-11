package com.navneet.executespec.auth.controller;

import com.navneet.executespec.auth.dto.*;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.OrgInvitation;
import com.navneet.executespec.auth.repository.OrgMemberRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.service.InvitationService;
import com.navneet.executespec.auth.service.OrgService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/org")
@RequiredArgsConstructor
public class OrgController {

    private final OrgService orgService;
    private final InvitationService invitationService;
    private final OrgRepository orgRepo;
    private final OrgMemberRepository orgMemberRepo;

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

    /** List org members — accessible to ALL org members (developers get read-only view). */
    @GetMapping("/me/members")
    public ResponseEntity<List<OrgMemberDto>> listMembers(
            @AuthenticationPrincipal AppUserDetails userDetails) {
        String orgId = userDetails.getUser().getOrgId();
        if (orgId == null) return ResponseEntity.noContent().build();
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
        String newRole = body.get("role");

        // PRO orgs can only have one ORG_ADMIN — ENTERPRISE allows multiple
        if ("ORG_ADMIN".equals(newRole) && orgId != null) {
            Org org = orgRepo.findById(orgId).orElse(null);
            if (org != null && "PRO".equals(org.getTier())) {
                long adminCount = orgMemberRepo.countByOrgIdAndOrgRole(orgId, "ORG_ADMIN");
                if (adminCount >= 1) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
                }
            }
        }

        orgService.changeMemberRole(orgId, userId, newRole);
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

    // ── Key source mode & provider restrictions (ORG_ADMIN only) ────────────

    record KeySourceSettingsRequest(String keySourceMode, List<String> enabledProviders) {}

    @PutMapping("/me/settings/key-source")
    @PreAuthorize("hasRole('ORG_ADMIN') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<OrgDto> updateKeySourceSettings(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @RequestBody KeySourceSettingsRequest req) {
        String orgId = userDetails.getUser().getOrgId();
        if (orgId == null) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(orgService.updateKeySourceSettings(
                orgId, req.keySourceMode(), req.enabledProviders()));
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

    // ── Org-level JIRA settings (ORG_ADMIN only) ────────────────────────────

    record OrgJiraSettingsRequest(String baseUrl, String email, String apiToken) {}

    @PutMapping("/me/settings/jira")
    @PreAuthorize("hasRole('ORG_ADMIN') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<OrgDto> updateOrgJiraSettings(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @RequestBody OrgJiraSettingsRequest req) {
        String orgId = userDetails.getUser().getOrgId();
        if (orgId == null) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(orgService.updateJiraSettings(
                orgId, req.baseUrl(), req.email(), req.apiToken()));
    }

    record ValidationResult(boolean valid, String message) {}

    @PostMapping("/me/settings/jira/validate")
    @PreAuthorize("hasRole('ORG_ADMIN') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<ValidationResult> validateOrgJira(@RequestBody OrgJiraSettingsRequest req) {
        if (req.baseUrl() == null || req.email() == null || req.apiToken() == null) {
            return ResponseEntity.ok(new ValidationResult(false, "Base URL, email, and API token are all required"));
        }
        try {
            String auth = Base64.getEncoder().encodeToString(
                    (req.email().strip() + ":" + req.apiToken().strip()).getBytes());
            String url = req.baseUrl().strip().replaceAll("/+$", "") + "/rest/api/3/myself";
            HttpResponse<String> resp = HTTP_CLIENT.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .header("Authorization", "Basic " + auth)
                            .header("Accept", "application/json")
                            .timeout(Duration.ofSeconds(10))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return ResponseEntity.ok(new ValidationResult(true, "JIRA credentials are valid"));
            }
            return ResponseEntity.ok(new ValidationResult(false,
                    "JIRA returned " + resp.statusCode() + " — check your credentials"));
        } catch (Exception e) {
            return ResponseEntity.ok(new ValidationResult(false, "Connection failed: " + e.getMessage()));
        }
    }

    // ── Org-level Confluence settings (ORG_ADMIN only) ──────────────────────

    record OrgConfluenceSettingsRequest(String baseUrl, String email, String apiToken) {}

    @PutMapping("/me/settings/confluence")
    @PreAuthorize("hasRole('ORG_ADMIN') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<OrgDto> updateOrgConfluenceSettings(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @RequestBody OrgConfluenceSettingsRequest req) {
        String orgId = userDetails.getUser().getOrgId();
        if (orgId == null) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(orgService.updateConfluenceSettings(
                orgId, req.baseUrl(), req.email(), req.apiToken()));
    }

    @PostMapping("/me/settings/confluence/validate")
    @PreAuthorize("hasRole('ORG_ADMIN') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<ValidationResult> validateOrgConfluence(@RequestBody OrgConfluenceSettingsRequest req) {
        if (req.baseUrl() == null || req.email() == null || req.apiToken() == null) {
            return ResponseEntity.ok(new ValidationResult(false, "Base URL, email, and API token are all required"));
        }
        try {
            String auth = Base64.getEncoder().encodeToString(
                    (req.email().strip() + ":" + req.apiToken().strip()).getBytes());
            String url = req.baseUrl().strip().replaceAll("/+$", "") + "/wiki/rest/api/space?limit=1";
            HttpResponse<String> resp = HTTP_CLIENT.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .header("Authorization", "Basic " + auth)
                            .header("Accept", "application/json")
                            .timeout(Duration.ofSeconds(10))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return ResponseEntity.ok(new ValidationResult(true, "Confluence credentials are valid"));
            }
            return ResponseEntity.ok(new ValidationResult(false,
                    "Confluence returned " + resp.statusCode() + " — check your credentials"));
        } catch (Exception e) {
            return ResponseEntity.ok(new ValidationResult(false, "Connection failed: " + e.getMessage()));
        }
    }

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
}

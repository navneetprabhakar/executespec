package com.navneet.executespec.auth.controller;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import com.navneet.executespec.auth.dto.CreateOrgRequest;
import com.navneet.executespec.auth.dto.InviteRequest;
import com.navneet.executespec.auth.dto.OrgDto;
import com.navneet.executespec.auth.dto.OrgMemberDto;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.OrgInvitation;
import com.navneet.executespec.auth.repository.OrgMemberRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.service.ActivityAuditService;
import com.navneet.executespec.auth.service.InvitationService;
import com.navneet.executespec.auth.service.OrgService;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelRegistry;
import com.navneet.executespec.router.OpenRouterModelMapper;
import com.navneet.executespec.router.OrgModelOverrideService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/org")
@RequiredArgsConstructor
public class OrgController {

  private final OrgService orgService;
  private final InvitationService invitationService;
  private final ActivityAuditService auditService;
  private final OrgRepository orgRepo;
  private final OrgMemberRepository orgMemberRepo;
  private final OrgModelOverrideService orgModelOverrideService;
  private final ModelRegistry modelRegistry;

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
      @AuthenticationPrincipal AppUserDetails userDetails, @RequestBody Map<String, Object> body) {
    String orgId = userDetails.getUser().getOrgId();
    @SuppressWarnings("unchecked")
    List<String> domains = (List<String>) body.get("domains");
    OrgDto updated =
        orgService.updateOrg(
            orgId, userDetails.getUser().getId(), (String) body.get("name"), domains);
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
      @AuthenticationPrincipal AppUserDetails userDetails, @PathVariable String userId) {
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

    orgService.changeMemberRole(orgId, userId, newRole, userDetails.getUser().getId());
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/invite")
  @PreAuthorize("hasRole('ORG_ADMIN') or hasRole('SUPER_ADMIN')")
  public ResponseEntity<Map<String, String>> invite(
      @AuthenticationPrincipal AppUserDetails userDetails, @Valid @RequestBody InviteRequest req) {
    String orgId = userDetails.getUser().getOrgId();
    OrgInvitation invitation =
        invitationService.createInvitation(orgId, userDetails.getUser().getId(), req);
    String message =
        "ESCALATED".equals(invitation.getStatus())
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
    invitationService.listByOrg("").stream().filter(inv -> inv.getId().equals(id)).findFirst();
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/me/invitations/{id}/resend")
  @PreAuthorize("hasRole('ORG_ADMIN') or hasRole('SUPER_ADMIN')")
  public ResponseEntity<Map<String, String>> resendInvitation(
      @AuthenticationPrincipal AppUserDetails userDetails, @PathVariable String id) {
    invitationService.resendInvitation(id, userDetails.getUser().getId());
    return ResponseEntity.ok(Map.of("message", "Invitation resent successfully"));
  }

  @PostMapping("/accept-invite")
  public ResponseEntity<Map<String, String>> acceptInvite(
      @RequestParam String token, @AuthenticationPrincipal AppUserDetails userDetails) {
    String userId = userDetails != null ? userDetails.getUser().getId() : null;
    invitationService.acceptInvitation(token, userId);
    return ResponseEntity.ok(Map.of("message", "Invitation accepted successfully"));
  }

  // Audit Logs (ORG_ADMIN only, scoped to own org)

  @GetMapping("/me/audit-logs")
  @PreAuthorize("hasRole('ORG_ADMIN') or hasRole('SUPER_ADMIN')")
  public ResponseEntity<com.navneet.executespec.auth.dto.AuditLogPage> getOrgAuditLogs(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @RequestParam(required = false) String entityType,
      @RequestParam(required = false) String eventType,
      @RequestParam(required = false) String actorId,
      @RequestParam(required = false) String entityId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    String orgId = userDetails.getUser().getOrgId();
    if (orgId == null) return ResponseEntity.noContent().build();
    Instant fromInstant = from != null ? Instant.parse(from) : null;
    Instant toInstant = to != null ? Instant.parse(to) : null;
    return ResponseEntity.ok(
        auditService.query(
            orgId, entityType, eventType, actorId, entityId, fromInstant, toInstant, page, size));
  }

  // Key source mode & provider restrictions (ORG_ADMIN only)

  record KeySourceSettingsRequest(String keySourceMode, List<String> enabledProviders) {}

  @PutMapping("/me/settings/key-source")
  @PreAuthorize("hasRole('ORG_ADMIN') or hasRole('SUPER_ADMIN')")
  public ResponseEntity<OrgDto> updateKeySourceSettings(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @RequestBody KeySourceSettingsRequest req) {
    String orgId = userDetails.getUser().getOrgId();
    if (orgId == null) return ResponseEntity.badRequest().build();
    return ResponseEntity.ok(
        orgService.updateKeySourceSettings(
            orgId, req.keySourceMode(), req.enabledProviders(), userDetails.getUser().getId()));
  }

  // External sharing toggle (V198, 2026-05-16)

  record ExternalSharingRequest(boolean enabled) {}

  /**
   * Enable or disable external project sharing for the caller's org. When false (default), the
   * {@code POST /api/projects/{id}/invites} endpoint refuses any new invite even from PRO+ org
   * admins. When true, ORG_ADMIN / SUPER_ADMIN may invite any email (gmail, outlook, etc.) as a
   * cross-org PROJECT_VIEWER or PROJECT_DEVELOPER on a single project.
   *
   * <p>Gated to ORG_ADMIN of the caller's org or SUPER_ADMIN — same as the other compliance-grade
   * org settings endpoints. The Spring-AOP @PreAuthorize fires before the service even runs.
   */
  @PutMapping("/me/settings/external-sharing")
  @PreAuthorize("hasRole('ORG_ADMIN') or hasRole('SUPER_ADMIN')")
  public ResponseEntity<OrgDto> updateExternalSharing(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @RequestBody ExternalSharingRequest req) {
    String orgId = userDetails.getUser().getOrgId();
    if (orgId == null) return ResponseEntity.badRequest().build();
    return ResponseEntity.ok(
        orgService.updateExternalSharing(orgId, req.enabled(), userDetails.getUser().getId()));
  }

  // Org-level AI provider key settings (ORG_ADMIN only)

  record OrgAiKeySettingsRequest(
      String anthropicApiKey, String openAiApiKey, String geminiApiKey, String openRouterApiKey) {}

  /**
   * Saves org-wide AI provider API keys. Used by all org members who have not set a personal key.
   * Resolution order: user key → org key → system key (application.yml). Raw keys are never
   * returned — only boolean flags appear in OrgDto.
   */
  @PutMapping("/me/settings/ai-keys")
  @PreAuthorize("hasRole('ORG_ADMIN') or hasRole('SUPER_ADMIN')")
  public ResponseEntity<OrgDto> updateOrgAiKeySettings(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @RequestBody OrgAiKeySettingsRequest req) {
    String orgId = userDetails.getUser().getOrgId();
    if (orgId == null) return ResponseEntity.badRequest().build();
    return ResponseEntity.ok(
        orgService.updateAiKeySettings(
            orgId,
            req.anthropicApiKey(),
            req.openAiApiKey(),
            req.geminiApiKey(),
            req.openRouterApiKey(),
            userDetails.getUser().getId()));
  }

  // Org-level model-matrix overrides (F3, ENTERPRISE)

  record ModelOverridesRequest(Map<String, String> overrides) {}

  /**
   * The matrix defaults + this org's per-(provider, tier) overrides + the picker options. Readable
   * by any org admin (so the grid renders for everyone); only ENTERPRISE orgs may save — the PUT
   * 403s otherwise. {@code editable} lets the UI render the grid read-only with an upgrade note for
   * non-Enterprise tiers.
   */
  record ModelMatrixView(
      String tier,
      boolean editable,
      List<String> providers,
      List<String> tiers,
      Map<String, String> defaults,
      Map<String, String> overrides,
      Map<String, List<String>> modelsByProvider) {}

  @GetMapping("/me/model-matrix")
  @PreAuthorize("hasRole('ORG_ADMIN') or hasRole('SUPER_ADMIN')")
  public ResponseEntity<ModelMatrixView> getModelMatrix(
      @AuthenticationPrincipal AppUserDetails userDetails) {
    String orgId = userDetails.getUser().getOrgId();
    if (orgId == null) return ResponseEntity.badRequest().build();
    return ResponseEntity.ok(buildMatrixView(orgId));
  }

  @PutMapping("/me/model-matrix")
  @PreAuthorize("hasRole('ORG_ADMIN') or hasRole('SUPER_ADMIN')")
  public ResponseEntity<ModelMatrixView> updateModelMatrix(
      @AuthenticationPrincipal AppUserDetails userDetails, @RequestBody ModelOverridesRequest req) {
    String orgId = userDetails.getUser().getOrgId();
    if (orgId == null) return ResponseEntity.badRequest().build();
    orgModelOverrideService.updateOverrides(orgId, req.overrides(), userDetails.getUser().getId());
    return ResponseEntity.ok(buildMatrixView(orgId));
  }

  private ModelMatrixView buildMatrixView(String orgId) {
    Org org = orgRepo.findById(orgId).orElse(null);
    String tier = org != null ? org.getTier() : null;
    boolean editable = "ENTERPRISE".equalsIgnoreCase(tier);

    List<String> providers = new ArrayList<>();
    for (LlmProvider p : LlmProvider.values()) providers.add(p.name().toLowerCase());
    List<String> tiers = new ArrayList<>();
    for (AgentTier t : AgentTier.values()) tiers.add(t.name().toLowerCase());

    Map<String, String> defaults = new LinkedHashMap<>();
    for (String p : providers) {
      putIfPresent(defaults, p + ":planning", modelRegistry.planningModelFor(p));
      putIfPresent(defaults, p + ":workhorse", modelRegistry.workhouseModelFor(p));
      putIfPresent(defaults, p + ":lightweight", modelRegistry.lightweightModelFor(p));
    }

    Map<String, List<String>> modelsByProvider = new LinkedHashMap<>();
    for (String p : providers) modelsByProvider.put(p, selectableModelsFor(p));

    return new ModelMatrixView(
        tier,
        editable,
        providers,
        tiers,
        defaults,
        orgModelOverrideService.currentOverrides(orgId),
        modelsByProvider);
  }

  private static void putIfPresent(Map<String, String> m, String k, String v) {
    if (v != null && !v.isBlank()) m.put(k, v);
  }

  /**
   * Models a given provider can run — its registered matrix models (OpenRouter = the allow-list).
   */
  private List<String> selectableModelsFor(String provider) {
    if ("openrouter".equalsIgnoreCase(provider)) {
      return OpenRouterModelMapper.allowList().keySet().stream().sorted().toList();
    }
    return modelRegistry.allModels().keySet().stream()
        .filter(id -> provider.equalsIgnoreCase(modelRegistry.providerFor(id)))
        .sorted()
        .toList();
  }

  // Org-level JIRA settings (ORG_ADMIN only)

  record OrgJiraSettingsRequest(String baseUrl, String email, String apiToken) {}

  @PutMapping("/me/settings/jira")
  @PreAuthorize("hasRole('ORG_ADMIN') or hasRole('SUPER_ADMIN')")
  public ResponseEntity<OrgDto> updateOrgJiraSettings(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @RequestBody OrgJiraSettingsRequest req) {
    String orgId = userDetails.getUser().getOrgId();
    if (orgId == null) return ResponseEntity.badRequest().build();
    return ResponseEntity.ok(
        orgService.updateJiraSettings(
            orgId, req.baseUrl(), req.email(), req.apiToken(), userDetails.getUser().getId()));
  }

  record ValidationResult(boolean valid, String message) {}

  @PostMapping("/me/settings/jira/validate")
  @PreAuthorize("hasRole('ORG_ADMIN') or hasRole('SUPER_ADMIN')")
  public ResponseEntity<ValidationResult> validateOrgJira(@RequestBody OrgJiraSettingsRequest req) {
    if (req.baseUrl() == null || req.email() == null || req.apiToken() == null) {
      return ResponseEntity.ok(
          new ValidationResult(false, "Base URL, email, and API token are all required"));
    }
    try {
      String auth =
          Base64.getEncoder()
              .encodeToString((req.email().strip() + ":" + req.apiToken().strip()).getBytes());
      String url = req.baseUrl().strip().replaceAll("/+$", "") + "/rest/api/3/myself";
      HttpResponse<String> resp =
          HTTP_CLIENT.send(
              HttpRequest.newBuilder()
                  .uri(URI.create(url))
                  .header("Authorization", "Basic " + auth)
                  .header("Accept", "application/json")
                  .timeout(Duration.ofSeconds(10))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() == 200) {
        return ResponseEntity.ok(new ValidationResult(true, "JIRA credentials are valid"));
      }
      return ResponseEntity.ok(
          new ValidationResult(
              false, "JIRA returned " + resp.statusCode() + " — check your credentials"));
    } catch (Exception e) {
      return ResponseEntity.ok(new ValidationResult(false, "Connection failed: " + e.getMessage()));
    }
  }

  // Org-level Confluence settings (ORG_ADMIN only)

  record OrgConfluenceSettingsRequest(String baseUrl, String email, String apiToken) {}

  @PutMapping("/me/settings/confluence")
  @PreAuthorize("hasRole('ORG_ADMIN') or hasRole('SUPER_ADMIN')")
  public ResponseEntity<OrgDto> updateOrgConfluenceSettings(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @RequestBody OrgConfluenceSettingsRequest req) {
    String orgId = userDetails.getUser().getOrgId();
    if (orgId == null) return ResponseEntity.badRequest().build();
    return ResponseEntity.ok(
        orgService.updateConfluenceSettings(
            orgId, req.baseUrl(), req.email(), req.apiToken(), userDetails.getUser().getId()));
  }

  @PostMapping("/me/settings/confluence/validate")
  @PreAuthorize("hasRole('ORG_ADMIN') or hasRole('SUPER_ADMIN')")
  public ResponseEntity<ValidationResult> validateOrgConfluence(
      @RequestBody OrgConfluenceSettingsRequest req) {
    if (req.baseUrl() == null || req.email() == null || req.apiToken() == null) {
      return ResponseEntity.ok(
          new ValidationResult(false, "Base URL, email, and API token are all required"));
    }
    try {
      String auth =
          Base64.getEncoder()
              .encodeToString((req.email().strip() + ":" + req.apiToken().strip()).getBytes());
      String url = req.baseUrl().strip().replaceAll("/+$", "") + "/wiki/rest/api/space?limit=1";
      HttpResponse<String> resp =
          HTTP_CLIENT.send(
              HttpRequest.newBuilder()
                  .uri(URI.create(url))
                  .header("Authorization", "Basic " + auth)
                  .header("Accept", "application/json")
                  .timeout(Duration.ofSeconds(10))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() == 200) {
        return ResponseEntity.ok(new ValidationResult(true, "Confluence credentials are valid"));
      }
      return ResponseEntity.ok(
          new ValidationResult(
              false, "Confluence returned " + resp.statusCode() + " — check your credentials"));
    } catch (Exception e) {
      return ResponseEntity.ok(new ValidationResult(false, "Connection failed: " + e.getMessage()));
    }
  }

  private static final HttpClient HTTP_CLIENT =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  // Seat management (v2.6.0 TEAM tier)

  public record SeatsAdjustRequest(int newSeats) {}

  public record SeatsAdjustResponse(
      int previousSeats, int newSeats, int activeMembers, String message) {}

  /**
   * v2.6.0 — adjust the seat count on a TEAM-tier org subscription. Updates the Stripe/Razorpay
   * subscription quantity (prorated) then syncs {@code org.seat_count} + {@code
   * BillingSubscription.totalSeats} locally — the webhook will also fire but we update eagerly so
   * the UI reflects the new count immediately.
   *
   * <p>Rejects reducing seats below current active member count (400) — admin must remove members
   * first.
   *
   * <p>Rejects seats outside 2-20 range (400) — larger orgs route to ENTERPRISE sales.
   *
   * <p>Returns 402 if no active TEAM subscription found (the org isn't on a seat-priced tier).
   */
  @PostMapping("/me/seats/adjust")
  @PreAuthorize("hasRole('ORG_ADMIN') or hasRole('SUPER_ADMIN')")
  public ResponseEntity<SeatsAdjustResponse> adjustSeats(
      @AuthenticationPrincipal AppUserDetails userDetails, @RequestBody SeatsAdjustRequest req) {
    String orgId = userDetails.getUser().getOrgId();
    if (orgId == null) return ResponseEntity.badRequest().build();
    SeatsAdjustResponse resp =
        orgService.adjustSeats(orgId, req.newSeats(), userDetails.getUser().getId());
    return ResponseEntity.ok(resp);
  }
}

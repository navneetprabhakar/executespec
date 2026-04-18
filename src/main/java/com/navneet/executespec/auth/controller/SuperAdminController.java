package com.navneet.executespec.auth.controller;

import com.navneet.executespec.auth.dto.AdminInviteRequest;
import com.navneet.executespec.auth.dto.OrgDto;
import com.navneet.executespec.auth.dto.UserDto;
import com.navneet.executespec.auth.entity.*;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgMemberRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.service.*;
import com.navneet.executespec.billing.entity.RunTopup;
import com.navneet.executespec.billing.entity.TopupPackage;
import com.navneet.executespec.billing.repository.TopupPackageRepository;
import com.navneet.executespec.billing.service.RunTopupService;
import com.navneet.executespec.config.PromptVersioningService;
import com.navneet.executespec.optimiser.PromptAuditService;
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
import java.util.UUID;
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
    private final MailService mailService;
    private final ActivityAuditService auditService;
    private final RunTopupService runTopupService;
    private final TopupPackageRepository topupPackageRepo;
    private final com.navneet.executespec.report.UsageReportService usageReportService;
    private final PromptVersioningService promptVersioningService;
    private final PromptAuditService promptAuditService;
    private final com.navneet.executespec.execution.service.AdminReconciliationService adminReconciliationService;
    private final com.navneet.executespec.security.guardrail.GuardrailLevelResolver guardrailLevelResolver;

    // ── Guardrail level diagnostic (Plan 1 P1) ────────────────────────────────

    /**
     * Reports the currently-resolved security posture. Useful during incident
     * response to confirm a flag flip picked up before escalating. Read-only;
     * changes happen via {@code PUT /api/admin/feature-flags/security.guardrail_level}.
     */
    @GetMapping("/guardrail")
    public ResponseEntity<Map<String, Object>> currentGuardrail() {
        com.navneet.executespec.security.guardrail.GuardrailLevel level = guardrailLevelResolver.current();
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("level", level.name());
        body.put("flagKey", com.navneet.executespec.security.guardrail.GuardrailLevelResolver.FLAG_KEY);
        body.put("atLeastMedium", level.atLeastMedium());
        body.put("atLeastHigh", level.atLeastHigh());
        body.put("resolvedAt", Instant.now().toString());
        // Diagnostic reads can lag live enforcement by up to ~5 s after a PUT on
        // the flag — FeatureFlagService uses Redis pub/sub invalidation plus a
        // 60 s scheduled reload. The rate-limit filter reads through on every
        // request so it picks up flips first; this diagnostic endpoint reads the
        // same service but may observe a slightly-later pub/sub delivery.
        // Retest-report N3 (2026-04-18).
        body.put("note", "may lag live enforcement by up to ~5 s right after a flag flip");
        return ResponseEntity.ok(body);
    }

    // ── Execution reconcile ───────────────────────────────────────────────────

    /**
     * One-off cleanup for orphaned {@code ExecutionRun} rows stuck in non-terminal
     * status (pre-v2.5.1 bug). See
     * {@link com.navneet.executespec.execution.service.AdminReconciliationService}.
     *
     * Body (optional): {@code {"staleMinutes": 30}}. Defaults to 30.
     */
    @PostMapping("/executions/reconcile")
    public ResponseEntity<com.navneet.executespec.execution.service.AdminReconciliationService.ReconcileReport>
            reconcileExecutions(@RequestBody(required = false) Map<String, Object> body) {
        long staleMinutes = 30;
        if (body != null && body.get("staleMinutes") instanceof Number n) {
            staleMinutes = Math.max(1, n.longValue());
        }
        return ResponseEntity.ok(adminReconciliationService.reconcileOrphans(staleMinutes));
    }

    // ── Prompt Versioning ─────────────────────────────────────────────────────

    @PostMapping("/prompts/{name}/rollback")
    public ResponseEntity<Map<String, Object>> rollbackPrompt(@PathVariable String name) {
        boolean done = promptVersioningService.rollback(name);
        if (!done) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "No audit history found for prompt: " + name));
        }
        return ResponseEntity.ok(Map.of("rolledBack", name));
    }

    @GetMapping("/prompts/{name}/history")
    public ResponseEntity<List<com.navneet.executespec.state.entity.PromptAuditEntry>> promptHistory(
            @PathVariable String name) {
        return ResponseEntity.ok(promptAuditService.getHistory(name));
    }

    // ── Usage Reports ──────────────────────────────────────────────────────────

    @PostMapping("/usage-reports/trigger")
    public ResponseEntity<Map<String, Object>> triggerUsageReports(@RequestBody Map<String, String> body) {
        String period = body.getOrDefault("period", "WEEKLY").toUpperCase();
        if (!List.of("WEEKLY", "MONTHLY").contains(period)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "period must be WEEKLY or MONTHLY");
        }
        int count = usageReportService.triggerReports(period);
        return ResponseEntity.ok(Map.of("emailsSent", count, "period", period));
    }

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
            @AuthenticationPrincipal AppUserDetails adminDetails,
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        AppUser user = userRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        String oldStatus = user.getStatus();
        String newStatus = body.get("status");
        user.setStatus(newStatus);
        user.setUpdatedAt(Instant.now());
        userRepo.save(user);

        auditService.record(ActivityAudit.builder()
                .eventType("USER_STATUS_CHANGED")
                .entityType("USER").entityId(id)
                .actorId(adminDetails.getUser().getId())
                .oldValue("{\"status\":\"" + oldStatus + "\"}")
                .newValue("{\"status\":\"" + newStatus + "\"}")
                .build());

        // Notify user on suspension
        if ("SUSPENDED".equals(newStatus) && !"SUSPENDED".equals(oldStatus)) {
            mailService.sendSuspensionEmail(user.getEmail(), user.getFirstName());
        }

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
        String newTier = body.get("tier");
        user.setTier(newTier);
        user.setUpdatedAt(Instant.now());
        userRepo.save(user);
        paymentAuditService.record("USER", id, "PLAN_UPGRADED", oldTier, newTier,
                adminDetails.getUser().getId(), null);

        auditService.record(ActivityAudit.builder()
                .eventType("USER_TIER_CHANGED")
                .entityType("USER").entityId(id)
                .actorId(adminDetails.getUser().getId())
                .oldValue("{\"tier\":\"" + oldTier + "\"}")
                .newValue("{\"tier\":\"" + newTier + "\"}")
                .build());

        // Notify user of tier upgrade
        if (!oldTier.equals(newTier)) {
            mailService.sendTierUpgradeEmail(user.getEmail(), user.getFirstName(), newTier, newTier);
        }

        return ResponseEntity.ok(UserDto.from(user, null));
    }

    @PutMapping("/users/{id}/role")
    public ResponseEntity<UserDto> updateUserRole(
            @AuthenticationPrincipal AppUserDetails adminDetails,
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        AppUser user = userRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        String oldRole = user.getGlobalRole();
        user.setGlobalRole(body.get("role"));
        user.setUpdatedAt(Instant.now());
        userRepo.save(user);

        auditService.record(ActivityAudit.builder()
                .eventType("USER_ROLE_CHANGED")
                .entityType("USER").entityId(id)
                .actorId(adminDetails.getUser().getId())
                .oldValue("{\"role\":\"" + oldRole + "\"}")
                .newValue("{\"role\":\"" + body.get("role") + "\"}")
                .build());

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
            @AuthenticationPrincipal AppUserDetails adminDetails,
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        Object raw = body.get("limit");
        Integer limit = raw == null ? null : ((Number) raw).intValue();
        return ResponseEntity.ok(orgService.updateRunLimitOverride(id, limit, adminDetails.getUser().getId()));
    }

    /** Toggle the api_keys_provided flag for an enterprise org. */
    @PutMapping("/orgs/{id}/api-keys")
    public ResponseEntity<OrgDto> updateOrgApiKeys(
            @AuthenticationPrincipal AppUserDetails adminDetails,
            @PathVariable String id,
            @RequestBody Map<String, Boolean> body) {
        boolean provided = Boolean.TRUE.equals(body.get("provided"));
        return ResponseEntity.ok(orgService.updateApiKeysProvided(id, provided, adminDetails.getUser().getId()));
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

    // ── Audit Logs ─────────────────────────────────────────────────────────────

    @GetMapping("/audit-logs")
    public ResponseEntity<com.navneet.executespec.auth.dto.AuditLogPage> getAuditLogs(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Instant fromInstant = from != null ? Instant.parse(from) : null;
        Instant toInstant = to != null ? Instant.parse(to) : null;
        return ResponseEntity.ok(auditService.query(
                null, entityType, eventType, actorId, entityId, fromInstant, toInstant, page, size));
    }

    // ── Test Email ────────────────────────────────────────────────────────────

    /**
     * Sends a quick test email to verify that Gmail OAuth is configured correctly.
     * Body: {"to": "someone@example.com"}
     */
    @PostMapping("/test-email")
    public ResponseEntity<Map<String, String>> sendTestEmail(
            @RequestBody Map<String, String> body) {
        String to = body.get("to");
        if (to == null || to.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Field 'to' is required"));
        }
        mailService.sendNewUserAlertToAdmin(to, "test-trigger@executespec.local");
        return ResponseEntity.ok(Map.of("message", "Test email dispatched to " + to));
    }

    // ── Grant Bonus Runs ──────────────────────────────────────────────────────

    @PostMapping("/users/{id}/grant-runs")
    public ResponseEntity<RunTopup> grantUserRuns(
            @AuthenticationPrincipal AppUserDetails adminDetails,
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        int quantity = ((Number) body.get("quantity")).intValue();
        Integer expiryDays = body.containsKey("expiryDays") && body.get("expiryDays") != null
                ? ((Number) body.get("expiryDays")).intValue() : null;
        String reason = (String) body.get("reason");

        RunTopup topup = runTopupService.grantBonusRuns(
                id, null, quantity, expiryDays, reason, adminDetails.getUser().getId());

        // Notify user of bonus runs
        AppUser grantedUser = userRepo.findById(id).orElse(null);
        if (grantedUser != null) {
            mailService.sendBonusRunsEmail(grantedUser.getEmail(), grantedUser.getFirstName(), quantity);
        }

        return ResponseEntity.ok(topup);
    }

    @PostMapping("/orgs/{id}/grant-runs")
    public ResponseEntity<RunTopup> grantOrgRuns(
            @AuthenticationPrincipal AppUserDetails adminDetails,
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        int quantity = ((Number) body.get("quantity")).intValue();
        Integer expiryDays = body.containsKey("expiryDays") && body.get("expiryDays") != null
                ? ((Number) body.get("expiryDays")).intValue() : null;
        String reason = (String) body.get("reason");

        RunTopup topup = runTopupService.grantBonusRuns(
                null, id, quantity, expiryDays, reason, adminDetails.getUser().getId());
        return ResponseEntity.ok(topup);
    }

    // ── Top-Up Package CRUD ─────────────────────────────────────────────────

    @GetMapping("/topup-packages")
    public ResponseEntity<List<TopupPackage>> listTopupPackages() {
        return ResponseEntity.ok(topupPackageRepo.findAllByOrderBySortOrder());
    }

    @PostMapping("/topup-packages")
    public ResponseEntity<TopupPackage> createTopupPackage(
            @RequestBody Map<String, Object> body) {
        TopupPackage pkg = TopupPackage.builder()
                .name((String) body.get("name"))
                .runCount(((Number) body.get("runCount")).intValue())
                .amountInr(((Number) body.get("amountInr")).longValue())
                .amountUsd(body.containsKey("amountUsd") ? ((Number) body.get("amountUsd")).longValue() : 0)
                .active(true)
                .sortOrder(body.containsKey("sortOrder") ? ((Number) body.get("sortOrder")).intValue() : 0)
                .build();
        return ResponseEntity.ok(topupPackageRepo.save(pkg));
    }

    @PutMapping("/topup-packages/{id}")
    public ResponseEntity<TopupPackage> updateTopupPackage(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body) {
        TopupPackage pkg = topupPackageRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Package not found"));
        if (body.containsKey("name")) pkg.setName((String) body.get("name"));
        if (body.containsKey("runCount")) pkg.setRunCount(((Number) body.get("runCount")).intValue());
        if (body.containsKey("amountInr")) pkg.setAmountInr(((Number) body.get("amountInr")).longValue());
        if (body.containsKey("amountUsd")) pkg.setAmountUsd(((Number) body.get("amountUsd")).longValue());
        if (body.containsKey("active")) pkg.setActive((Boolean) body.get("active"));
        if (body.containsKey("sortOrder")) pkg.setSortOrder(((Number) body.get("sortOrder")).intValue());
        return ResponseEntity.ok(topupPackageRepo.save(pkg));
    }

    @DeleteMapping("/topup-packages/{id}")
    public ResponseEntity<Map<String, String>> deleteTopupPackage(@PathVariable UUID id) {
        TopupPackage pkg = topupPackageRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Package not found"));
        pkg.setActive(false);
        topupPackageRepo.save(pkg);
        return ResponseEntity.ok(Map.of("message", "Package deactivated"));
    }
}

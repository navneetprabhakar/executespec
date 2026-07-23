package com.navneet.executespec.auth.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class SuperAdminController {

  private final AppUserRepository userRepo;
  private final OrgMemberRepository orgMemberRepo;
  private final OrgService orgService;
  private final InvitationService invitationService;
  private final TierConfigRepository tierConfigRepo;
  private final MailService mailService;
  private final ActivityAuditService auditService;
  private final RunTopupService runTopupService;
  private final TopupPackageRepository topupPackageRepo;
  private final com.navneet.executespec.report.UsageReportService usageReportService;
  private final PromptVersioningService promptVersioningService;
  private final PromptAuditService promptAuditService;
  private final com.navneet.executespec.execution.service.AdminReconciliationService
      adminReconciliationService;
  private final com.navneet.executespec.security.guardrail.GuardrailLevelResolver
      guardrailLevelResolver;
  private final com.navneet.executespec.integration.RunLifecycleService runLifecycleService;
  private final com.navneet.executespec.report.VerifiedScoreBackfillService
      verifiedScoreBackfillService;
  private final AdminUserMutationService adminUserMutationService;
  private final AdminUserQueryService adminUserQueryService;
  private final com.navneet.executespec.billing.service.TopupPackageAdminService
      topupPackageAdminService;
  private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

  // Guardrail level diagnostic (Plan 1 P1)

  /**
   * Reports the currently-resolved security posture. Useful during incident response to confirm a
   * flag flip picked up before escalating. Read-only; changes happen via {@code PUT
   * /api/admin/feature-flags/security.guardrail_level}.
   */
  @GetMapping("/guardrail")
  public ResponseEntity<Map<String, Object>> currentGuardrail() {
    com.navneet.executespec.security.guardrail.GuardrailLevel level =
        guardrailLevelResolver.current();
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

  /**
   * One-off cleanup for orphaned {@code ExecutionRun} rows stuck in non-terminal status (pre-v2.5.1
   * bug). See {@link com.navneet.executespec.execution.service.AdminReconciliationService}.
   *
   * <p>Body (optional): {@code {"staleMinutes": 30}}. Defaults to 30.
   */
  @PostMapping("/executions/reconcile")
  public ResponseEntity<
          com.navneet.executespec.execution.service.AdminReconciliationService.ReconcileReport>
      reconcileExecutions(@RequestBody(required = false) Map<String, Object> body) {
    long staleMinutes = 30;
    if (body != null && body.get("staleMinutes") instanceof Number n) {
      staleMinutes = Math.max(1, n.longValue());
    }
    return ResponseEntity.ok(adminReconciliationService.reconcileOrphans(staleMinutes));
  }

  // P1.2-followup: backfill historical verified scores

  /**
   * Re-compute {@code validation_report.verified_score} and {@code rubric_score} for existing rows
   * using the current formula. Idempotent. Body is optional; use {@code dryRun=true} first on a
   * large table and {@code runId}/ {@code limit} to narrow the pass.
   *
   * <p>SUPER_ADMIN-gated by SecurityConfig (this whole controller is /api/admin).
   */
  @PostMapping("/validation-reports/backfill-verified-score")
  public ResponseEntity<com.navneet.executespec.report.VerifiedScoreBackfillService.BackfillResult>
      backfillVerifiedScore(
          @RequestBody(required = false)
              com.navneet.executespec.report.VerifiedScoreBackfillService.BackfillRequest request) {
    return ResponseEntity.ok(verifiedScoreBackfillService.backfill(request));
  }

  // Re-review (2026-05-02 — vendored-path exclusion follow-up)

  /**
   * Drop the existing validation report for a terminal V2 run, reset its status to {@code
   * REVIEWING}, and re-run reviewer + accuracy assessment + report generation under the current
   * code. Used after shipping fixes to the reviewer / accuracy assessor / vendored-path exclusion
   * regex so an existing run's score reflects the fix without resubmitting the spec.
   *
   * <p>Returns 202 immediately — the reviewer pass runs async on the V2 pipeline pool, and SSE
   * subscribers see {@code STATUS} (REVIEWING) followed by {@code COMPLETE} (with the new status +
   * reason) when the pass finishes. Audit row written before the async submit so the action is
   * traceable even if the reviewer call itself errors out.
   *
   * <p>409 if the run is still in a non-terminal state (PLANNING / WORKING / REVIEWING /
   * AWAITING_APPROVAL); 404 if the run id is unknown.
   */
  /**
   * 2026-05-04 — operational escape hatch for tasks that are wedged in {@code WORKING} due to a
   * missed status update (e.g. dev run {@code 3b739e94...} task T1 — silent {@code
   * persistTaskStatus} failure left the row in WORKING for 90+ minutes while the executor moved
   * on). The {@code @Retryable} on {@code persistTaskStatus} prevents the original cause, but rows
   * that landed in this state pre-retry need a manual flip so the run can finalize cleanly.
   *
   * <p>Body: {@code {"reason": "..."}} (required, ≥10 chars) — lands in {@code
   * agent_task.last_llm_failure} so the audit trail shows who flipped it manually and why. Activity
   * audit row written so the action is traceable.
   *
   * <p>Status flip is one-way: WORKING/PENDING → FAILED. Refuses to touch tasks already in a
   * terminal status (404-style 409 to prevent accidental data loss on a task that completed
   * legitimately).
   */
  @PostMapping("/runs/{runId}/tasks/{taskId}/force-fail")
  public ResponseEntity<Map<String, Object>> forceFailTask(
      @PathVariable String runId,
      @PathVariable String taskId,
      @RequestBody Map<String, String> body,
      @AuthenticationPrincipal AppUserDetails admin) {
    String reason = body == null ? null : body.get("reason");
    if (reason == null || reason.trim().length() < 10) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Reason is required (>=10 chars) — used for audit + the task's lastLlmFailure field.");
    }
    Map<String, Object> result;
    try {
      result = runLifecycleService.forceFailTask(runId, taskId, reason.trim());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
    }
    auditService.record(
        ActivityAudit.builder()
            .eventType("TASK_FORCE_FAILED")
            .entityType("TASK")
            .entityId(runId + "/" + taskId)
            .actorId(admin == null ? null : admin.getUser().getId())
            .metadata(auditJson(Map.of("reason", reason.trim())))
            .build());
    return ResponseEntity.ok(result);
  }

  @PostMapping("/runs/{runId}/re-review")
  public ResponseEntity<Map<String, Object>> reReviewRun(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails admin) {
    try {
      runLifecycleService.reReview(runId);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
    }
    auditService.record(
        ActivityAudit.builder()
            .eventType("RUN_RE_REVIEWED")
            .entityType("RUN")
            .entityId(runId)
            .actorId(admin == null ? null : admin.getUser().getId())
            .build());
    return ResponseEntity.accepted()
        .body(Map.of("status", "queued", "runId", runId, "message", "re-review submitted"));
  }

  // Audit hash chain verifier (M-019)

  /**
   * Walks the activity_audit chain (V147) and reports the first break, if any. Used by Carmen
   * (compliance persona) to prove no row was tampered or deleted after the fact.
   */
  @GetMapping("/audit-chain/verify")
  public ResponseEntity<ActivityAuditService.ChainVerifyReport> verifyAuditChain() {
    return ResponseEntity.ok(auditService.verifyChain());
  }

  // Halt all active runs (M-010)

  /**
   * Cancels every non-terminal run on the platform — incident-response tool for SUPER_ADMINs
   * (provider outage, runaway cost, planner storm, etc.). Idempotent: a clean platform returns an
   * empty list.
   *
   * <p>Body (optional): {@code {"reason": "OpenAI 5xx storm 2026-04-27"}}. If provided, the
   * rationale is stamped on each run's failureReason field and surfaces on the run detail UI; if
   * omitted the per-run default message ("Cancelled by user from status X") is used.
   */
  @PostMapping("/runs/halt-all")
  public ResponseEntity<Map<String, Object>> haltAllActiveRuns(
      @AuthenticationPrincipal AppUserDetails admin,
      @RequestBody(required = false) Map<String, String> body) {
    String reason = body == null ? null : body.get("reason");
    var halted = runLifecycleService.haltAllActiveRuns(reason);
    auditService.record(
        ActivityAudit.builder()
            .eventType("RUNS_HALTED_ALL")
            .entityType("PLATFORM")
            .entityId("v2-run-orchestrator")
            .actorId(admin.getUser().getId())
            .newValue(
                auditJson(
                    Map.of("haltedCount", halted.size(), "reason", reason == null ? "" : reason)))
            .build());
    return ResponseEntity.ok(
        Map.of(
            "haltedCount",
            halted.size(),
            "haltedRunIds",
            halted,
            "reason",
            reason == null ? "" : reason));
  }

  @PostMapping("/prompts/{name}/rollback")
  public ResponseEntity<Map<String, Object>> rollbackPrompt(@PathVariable String name) {
    // RR-1304 — the endpoint returns 2xx ONLY when the live prompt provably changed. A restore
    // failure (disk/permission/short-write) is a 500, not a falsely-acknowledged 200.
    PromptVersioningService.RollbackOutcome outcome = promptVersioningService.rollback(name);
    return switch (outcome) {
      case ROLLED_BACK -> ResponseEntity.ok(Map.of("rolledBack", name));
      case NO_HISTORY ->
          ResponseEntity.status(HttpStatus.NOT_FOUND)
              .body(Map.of("error", "No audit history found for prompt: " + name));
      case RESTORE_FAILED ->
          ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
              .body(Map.of("error", "Rollback could not be applied for prompt: " + name));
    };
  }

  @GetMapping("/prompts/{name}/history")
  public ResponseEntity<List<com.navneet.executespec.state.entity.PromptAuditEntry>> promptHistory(
      @PathVariable String name) {
    return ResponseEntity.ok(promptAuditService.getHistory(name));
  }

  @PostMapping("/usage-reports/trigger")
  public ResponseEntity<Map<String, Object>> triggerUsageReports(
      @RequestBody Map<String, String> body) {
    String period = body.getOrDefault("period", "WEEKLY").toUpperCase();
    if (!List.of("WEEKLY", "MONTHLY").contains(period)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "period must be WEEKLY or MONTHLY");
    }
    int count = usageReportService.triggerReports(period);
    return ResponseEntity.ok(Map.of("emailsSent", count, "period", period));
  }

  @GetMapping("/users")
  public ResponseEntity<List<UserDto>> listUsers(
      @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
    // RR-012: batched org-membership lookup + optional bounded page (see AdminUserQueryService).
    return ResponseEntity.ok(adminUserQueryService.listUsers(page, size));
  }

  @GetMapping("/users/{id}")
  public ResponseEntity<UserDto> getUser(@PathVariable String id) {
    AppUser user =
        userRepo
            .findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    var member =
        user.getOrgId() != null
            ? orgMemberRepo.findByOrgIdAndUserId(user.getOrgId(), user.getId()).orElse(null)
            : null;
    return ResponseEntity.ok(UserDto.from(user, member));
  }

  @PutMapping("/users/{id}/status")
  public ResponseEntity<UserDto> updateUserStatus(
      @AuthenticationPrincipal AppUserDetails adminDetails,
      @PathVariable String id,
      @RequestBody Map<String, String> body) {
    // RR-009/RR-010: validation, invariants (self / last-super-admin) and structured audit live in
    // AdminUserMutationService, transactional with the write.
    AdminUserMutationService.StatusChange result =
        adminUserMutationService.updateStatus(
            adminDetails.getUser().getId(), id, body == null ? null : body.get("status"));
    if (result.newlySuspended()) {
      mailService.sendSuspensionEmail(result.user().getEmail(), result.user().getFirstName());
    }
    return ResponseEntity.ok(UserDto.from(result.user(), null));
  }

  @PutMapping("/users/{id}/tier")
  public ResponseEntity<UserDto> updateUserTier(
      @AuthenticationPrincipal AppUserDetails adminDetails,
      @PathVariable String id,
      @RequestBody Map<String, String> body) {
    AdminUserMutationService.TierChange result =
        adminUserMutationService.updateTier(
            adminDetails.getUser().getId(), id, body == null ? null : body.get("tier"));
    if (result.changed()) {
      mailService.sendTierUpgradeEmail(
          result.user().getEmail(),
          result.user().getFirstName(),
          result.newTier(),
          result.newTier());
    }
    return ResponseEntity.ok(UserDto.from(result.user(), null));
  }

  @PutMapping("/users/{id}/role")
  public ResponseEntity<UserDto> updateUserRole(
      @AuthenticationPrincipal AppUserDetails adminDetails,
      @PathVariable String id,
      @RequestBody Map<String, String> body) {
    AppUser user =
        adminUserMutationService.updateRole(
            adminDetails.getUser().getId(), id, body == null ? null : body.get("role"));
    return ResponseEntity.ok(UserDto.from(user, null));
  }

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
    // RR-010: reject an out-of-domain status before it is persisted.
    String status = AdminMutationValues.requireOrgStatus(body == null ? null : body.get("status"));
    orgService.changeOrgStatus(id, status, adminDetails.getUser().getId());
    return ResponseEntity.ok(orgService.getOrg(id));
  }

  @PutMapping("/orgs/{id}/tier")
  public ResponseEntity<OrgDto> updateOrgTier(
      @AuthenticationPrincipal AppUserDetails adminDetails,
      @PathVariable String id,
      @RequestBody Map<String, String> body) {
    // RR-010: reject an out-of-domain tier before it is persisted.
    String tier = AdminMutationValues.requireTier(body == null ? null : body.get("tier"));
    orgService.changeOrgTier(id, tier, adminDetails.getUser().getId());
    return ResponseEntity.ok(orgService.getOrg(id));
  }

  /** Override (or clear) the per-org monthly run limit. Null limit resets to tier default. */
  @PutMapping("/orgs/{id}/run-limit")
  public ResponseEntity<OrgDto> updateOrgRunLimit(
      @AuthenticationPrincipal AppUserDetails adminDetails,
      @PathVariable String id,
      @RequestBody Map<String, Object> body) {
    // RR-010: a run-limit override must be null (reset to default) or a non-negative integer, never
    // a negative or non-numeric value.
    Integer limit =
        AdminMutationValues.optionalNonNegativeInt(
            body == null ? null : body.get("limit"), "limit");
    return ResponseEntity.ok(
        orgService.updateRunLimitOverride(id, limit, adminDetails.getUser().getId()));
  }

  /** Toggle the api_keys_provided flag for an enterprise org. */
  @PutMapping("/orgs/{id}/api-keys")
  public ResponseEntity<OrgDto> updateOrgApiKeys(
      @AuthenticationPrincipal AppUserDetails adminDetails,
      @PathVariable String id,
      @RequestBody Map<String, Boolean> body) {
    boolean provided = Boolean.TRUE.equals(body.get("provided"));
    return ResponseEntity.ok(
        orgService.updateApiKeysProvided(id, provided, adminDetails.getUser().getId()));
  }

  @GetMapping("/tier-config")
  public ResponseEntity<List<TierConfig>> listTierConfig() {
    return ResponseEntity.ok(tierConfigRepo.findAll());
  }

  @PutMapping("/tier-config/{tier}")
  public ResponseEntity<TierConfig> updateTierConfig(
      @PathVariable String tier, @RequestBody Map<String, Integer> body) {
    TierConfig config =
        tierConfigRepo
            .findById(tier.toUpperCase())
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tier not found: " + tier));
    config.setMonthlyRunLimit(body.get("monthlyRunLimit"));
    return ResponseEntity.ok(tierConfigRepo.save(config));
  }

  @PostMapping("/invite")
  public ResponseEntity<Map<String, String>> adminInvite(
      @AuthenticationPrincipal AppUserDetails adminDetails, @RequestBody AdminInviteRequest req) {

    String adminId = adminDetails.getUser().getId();

    // RR-013 — an invite must name a target email and a target org (existing or new). The old code
    // silently did NOTHING when both newOrgName and orgId were absent, then returned
    // "Invitation sent" — a false-success that created no invitation and no onboarding action.
    // There is no implemented "free invite with no org" flow, so reject that input instead of
    // claiming success.
    if (req.email() == null || req.email().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "email is required");
    }

    String resolvedOrgId;
    if (req.newOrgName() != null && !req.newOrgName().isBlank()) {
      Org newOrg = orgService.createOrgAsAdmin(req.newOrgName(), adminId);
      resolvedOrgId = newOrg.getId();
    } else if (req.orgId() != null && !req.orgId().isBlank()) {
      resolvedOrgId = req.orgId();
    } else {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Provide an existing orgId or a newOrgName to invite into. A free invite with no target"
              + " org is not supported — direct the user to self-register.");
    }

    invitationService.createAdminInvitation(resolvedOrgId, req.email(), req.orgRole(), adminId);

    // Typed result describing what was created so the caller isn't guessing.
    return ResponseEntity.ok(
        Map.of("message", "Invitation sent to " + req.email(), "orgId", resolvedOrgId));
  }

  @GetMapping("/invitations/escalated")
  public ResponseEntity<List<OrgInvitation>> listEscalated() {
    return ResponseEntity.ok(invitationService.listEscalated());
  }

  @PostMapping("/invitations/{id}/approve")
  public ResponseEntity<Map<String, String>> approveInvitation(
      @AuthenticationPrincipal AppUserDetails adminDetails, @PathVariable String id) {
    invitationService.approveEscalation(id, adminDetails.getUser().getId());
    return ResponseEntity.ok(Map.of("message", "Invitation approved and sent to invitee"));
  }

  @PostMapping("/invitations/{id}/reject")
  public ResponseEntity<Map<String, String>> rejectInvitation(
      @AuthenticationPrincipal AppUserDetails adminDetails, @PathVariable String id) {
    invitationService.rejectEscalation(id, adminDetails.getUser().getId());
    return ResponseEntity.ok(Map.of("message", "Invitation rejected"));
  }

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
    return ResponseEntity.ok(
        auditService.query(
            null, entityType, eventType, actorId, entityId, fromInstant, toInstant, page, size));
  }

  /**
   * Sends a quick test email to verify that Gmail OAuth is configured correctly. Body: {"to":
   * "someone@example.com"}
   */
  @PostMapping("/test-email")
  public ResponseEntity<Map<String, String>> sendTestEmail(@RequestBody Map<String, String> body) {
    String to = body.get("to");
    if (to == null || to.isBlank()) {
      return ResponseEntity.badRequest().body(Map.of("error", "Field 'to' is required"));
    }
    mailService.sendNewUserAlertToAdmin(to, "test-trigger@executespec.local");
    return ResponseEntity.ok(Map.of("message", "Test email dispatched to " + to));
  }

  @PostMapping("/users/{id}/grant-runs")
  public ResponseEntity<RunTopup> grantUserRuns(
      @AuthenticationPrincipal AppUserDetails adminDetails,
      @PathVariable String id,
      @RequestBody Map<String, Object> body) {
    int quantity = AdminMutationValues.requirePositiveInt(body.get("quantity"), "quantity");
    Integer expiryDays =
        AdminMutationValues.optionalNonNegativeInt(body.get("expiryDays"), "expiryDays");
    String reason = (String) body.get("reason");

    RunTopup topup =
        runTopupService.grantBonusRuns(
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
    int quantity = AdminMutationValues.requirePositiveInt(body.get("quantity"), "quantity");
    Integer expiryDays =
        AdminMutationValues.optionalNonNegativeInt(body.get("expiryDays"), "expiryDays");
    String reason = (String) body.get("reason");

    RunTopup topup =
        runTopupService.grantBonusRuns(
            null, id, quantity, expiryDays, reason, adminDetails.getUser().getId());
    return ResponseEntity.ok(topup);
  }

  @GetMapping("/topup-packages")
  public ResponseEntity<List<TopupPackage>> listTopupPackages() {
    return ResponseEntity.ok(topupPackageRepo.findAllByOrderBySortOrder());
  }

  @PostMapping("/topup-packages")
  public ResponseEntity<TopupPackage> createTopupPackage(
      @AuthenticationPrincipal AppUserDetails adminDetails, @RequestBody Map<String, Object> body) {
    // RR-011: validated money/count + audit atomicity live in TopupPackageAdminService.
    return ResponseEntity.ok(topupPackageAdminService.create(adminDetails.getUser().getId(), body));
  }

  @PutMapping("/topup-packages/{id}")
  public ResponseEntity<TopupPackage> updateTopupPackage(
      @AuthenticationPrincipal AppUserDetails adminDetails,
      @PathVariable UUID id,
      @RequestBody Map<String, Object> body) {
    return ResponseEntity.ok(
        topupPackageAdminService.update(adminDetails.getUser().getId(), id, body));
  }

  @DeleteMapping("/topup-packages/{id}")
  public ResponseEntity<Map<String, String>> deleteTopupPackage(
      @AuthenticationPrincipal AppUserDetails adminDetails, @PathVariable UUID id) {
    topupPackageAdminService.deactivate(adminDetails.getUser().getId(), id);
    return ResponseEntity.ok(Map.of("message", "Package deactivated"));
  }

  /**
   * RR-009 — serialize audit metadata with Jackson rather than string concatenation, so a
   * user-controlled value cannot inject unbalanced quotes / control characters into the audit
   * chain.
   */
  private String auditJson(Map<String, Object> fields) {
    try {
      return objectMapper.writeValueAsString(fields);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return "{}";
    }
  }
}

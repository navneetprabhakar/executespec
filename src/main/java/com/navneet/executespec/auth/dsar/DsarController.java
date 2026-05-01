package com.navneet.executespec.auth.dsar;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.PaymentAudit;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.notification.Notification;
import com.navneet.executespec.notification.NotificationRepository;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * M-017 (Wave 3) — Data Subject Access Request export.
 *
 * <p>GDPR / CCPA / DPDP all give end-users the right to request a machine-readable export of every
 * piece of personal data we hold about them, within a defined response window (30 days under GDPR;
 * 45 under CCPA). Operationalising this manually doesn't scale — one endpoint, the user (or an
 * admin acting on their behalf) hits it, gets a JSON dump.
 *
 * <p>Two endpoints:
 *
 * <ul>
 *   <li>{@code GET /api/dsar/me} — caller self-service. Returns their own data; no role required
 *       beyond AUTH.
 *   <li>{@code GET /api/dsar/users/{userId}} — SUPER_ADMIN acts-on-behalf-of for legal-team
 *       requests. Audited.
 * </ul>
 *
 * <p>Returns a single JSON document with sections:
 *
 * <ul>
 *   <li>{@code identity} — AppUser core fields (email, names, tier, status, oauth provider, github
 *       owner, settings keys — but never the actual secrets).
 *   <li>{@code payments} — every PaymentAudit row touching this user or their org (subset of the
 *       unified payment_audit log).
 *   <li>{@code runs} — every PipelineRun the user authored, with the original spec text + the run's
 *       terminal status + cost. Most-recent first, capped at 1000 rows.
 *   <li>{@code notifications} — every notification ever sent to the user.
 * </ul>
 *
 * <p>Out of scope: deletion ("right to be forgotten"). That's a separate workflow ({@code POST
 * /api/dsar/me/delete}) gated on a 30-day delay + cascade audit. Tracked as a follow-up.
 */
@RestController
@RequestMapping("/api/dsar")
@RequiredArgsConstructor
@Slf4j
public class DsarController {

  private final AppUserRepository userRepo;
  private final PipelineRunRepository runRepo;
  private final NotificationRepository notifRepo;
  private final com.navneet.executespec.auth.repository.PaymentAuditRepository paymentRepo;

  @GetMapping(value = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> exportSelf(
      @AuthenticationPrincipal AppUserDetails principal) {
    if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    return ResponseEntity.ok(buildExport(principal.getUser().getId()));
  }

  @GetMapping(value = "/users/{userId}", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  public ResponseEntity<Map<String, Object>> exportFor(
      @AuthenticationPrincipal AppUserDetails admin, @PathVariable String userId) {
    log.warn(
        "[DSAR] act-on-behalf export by admin={} for user={}", admin.getUser().getId(), userId);
    return ResponseEntity.ok(buildExport(userId));
  }

  private Map<String, Object> buildExport(String userId) {
    AppUser user =
        userRepo
            .findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

    Map<String, Object> identity = new LinkedHashMap<>();
    identity.put("id", user.getId());
    identity.put("email", user.getEmail());
    identity.put("firstName", user.getFirstName());
    identity.put("lastName", user.getLastName());
    identity.put("tier", user.getTier());
    identity.put("globalRole", user.getGlobalRole());
    identity.put("status", user.getStatus());
    identity.put("oauthProvider", user.getOauthProvider());
    identity.put("orgId", user.getOrgId());
    identity.put("githubOwner", user.getGithubOwner());
    identity.put("createdAt", user.getCreatedAt());
    identity.put("updatedAt", user.getUpdatedAt());
    identity.put("hasGithubPat", user.getGithubPat() != null && !user.getGithubPat().isBlank());
    identity.put(
        "hasOpenAiKey", user.getOpenAiApiKey() != null && !user.getOpenAiApiKey().isBlank());
    identity.put(
        "hasAnthropicKey",
        user.getAnthropicApiKey() != null && !user.getAnthropicApiKey().isBlank());
    identity.put(
        "hasGeminiKey", user.getGeminiApiKey() != null && !user.getGeminiApiKey().isBlank());
    // Secrets themselves are intentionally OMITTED — DSAR mandates
    // disclosure of WHAT we hold, not handing back live credentials.

    // PaymentAudit rows touching this user or their org.
    List<PaymentAudit> payments = paymentRepo.findByUserIdOrOrgId(user.getId(), user.getOrgId());
    List<Map<String, Object>> paymentRows = payments.stream().map(this::serializePayment).toList();

    // Runs the user authored — capped at 1000 to keep the response sane.
    List<PipelineRun> runs =
        runRepo
            .findByCreatedByUserIdOrderByStartedAtDesc(user.getId(), PageRequest.of(0, 1000))
            .getContent();
    List<Map<String, Object>> runRows = runs.stream().map(this::serializeRun).toList();

    // Notifications.
    List<Notification> notifs =
        notifRepo
            .findByUserIdOrderByCreatedAtDesc(user.getId(), PageRequest.of(0, 1000))
            .getContent();
    List<Map<String, Object>> notifRows = notifs.stream().map(this::serializeNotif).toList();

    Map<String, Object> root = new LinkedHashMap<>();
    root.put("schemaVersion", 1);
    root.put("generatedAt", Instant.now());
    root.put("identity", identity);
    root.put("payments", paymentRows);
    root.put("runs", runRows);
    root.put("notifications", notifRows);
    root.put(
        "note",
        "Per GDPR Art. 15 / CCPA / DPDP this is the machine-readable copy "
            + "of the personal data ExecuteSpec holds about the subject. "
            + "Live credentials (GitHub PAT, AI keys, password hash) are "
            + "intentionally redacted — DSAR mandates disclosure of WHAT "
            + "we hold, not handing back active secrets. To request "
            + "deletion (right to be forgotten), email admin@executespec.ai.");
    return root;
  }

  private Map<String, Object> serializePayment(PaymentAudit p) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", p.getId());
    m.put("createdAt", p.getCreatedAt());
    m.put("eventType", p.getEventType());
    m.put("entityType", p.getEntityType());
    m.put("entityId", p.getEntityId());
    m.put("oldTier", p.getOldTier());
    m.put("newTier", p.getNewTier());
    m.put("gatewayProvider", p.getGatewayProvider());
    m.put("paymentRefId", p.getPaymentRefId());
    m.put("currency", p.getCurrency());
    m.put("amountMinor", p.getAmountMinor());
    return m;
  }

  private Map<String, Object> serializeRun(PipelineRun r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("runId", r.getRunId());
    m.put("startedAt", r.getStartedAt());
    m.put("completedAt", r.getCompletedAt());
    m.put("status", r.getStatus());
    m.put("statusReason", r.getStatusReason());
    m.put("userRequest", r.getUserRequest());
    m.put("aiProvider", r.getAiProvider());
    m.put("actualCostUsd", r.getActualCostUsd());
    m.put("projectId", r.getProjectId());
    m.put("orgId", r.getOrgId());
    return m;
  }

  private Map<String, Object> serializeNotif(Notification n) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", n.getId());
    m.put("createdAt", n.getCreatedAt());
    m.put("type", n.getType());
    m.put("title", n.getTitle());
    m.put("body", n.getBody());
    m.put("read", n.isRead());
    return m;
  }
}

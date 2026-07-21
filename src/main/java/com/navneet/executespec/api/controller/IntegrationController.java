package com.navneet.executespec.api.controller;

import java.util.List;
import java.util.Map;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.integration.InboundAdapter;
import com.navneet.executespec.integration.IntegrationEvent;
import com.navneet.executespec.integration.IntegrationManager;
import com.navneet.executespec.integration.WebhookSignatureException;
import com.navneet.executespec.integration.confluence.ConfluenceAdapter;
import com.navneet.executespec.integration.jira.JiraAdapter;
import com.navneet.executespec.state.entity.IntegrationRun;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/integrations")
@ConditionalOnProperty(name = "agent.integrations.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class IntegrationController {

  private static final String INTERNAL_ERROR = "Integration request could not be completed.";
  private static final String INVALID_REQUEST = "Invalid integration request.";

  private final IntegrationManager manager;
  private final RunAccessGuard guard;

  /**
   * Generic webhook receiver: POST /api/integrations/{source}/webhook Webhooks are authenticated by
   * their own signature mechanism, not JWT.
   */
  @PostMapping("/{source}/webhook")
  public ResponseEntity<Map<String, String>> webhook(
      @PathVariable String source,
      @RequestHeader Map<String, String> headers,
      @RequestBody String rawBody) {
    try {
      String result = manager.handleWebhook(source.toUpperCase(), headers, rawBody);
      return ResponseEntity.ok(Map.of("status", result));
    } catch (WebhookSignatureException e) {
      log.warn("[Webhook] Invalid signature from {}", source);
      return ResponseEntity.status(401).body(Map.of("error", "invalid_signature"));
    } catch (IllegalArgumentException e) {
      log.warn("[Webhook] Unknown source: {}", source);
      return ResponseEntity.badRequest().body(Map.of("error", INVALID_REQUEST));
    } catch (Exception e) {
      log.error(
          "[Webhook] Error processing {} webhook errorType={}",
          source,
          e.getClass().getSimpleName());
      return ResponseEntity.internalServerError().body(Map.of("error", INTERNAL_ERROR));
    }
  }

  /**
   * Manual JIRA trigger: POST /api/integrations/jira/trigger Body: { "issueKey": "KAN-1", "goal":
   * "optional goal override" }
   */
  @PostMapping("/jira/trigger")
  public ResponseEntity<Map<String, String>> jiraTrigger(
      @RequestBody Map<String, String> body, @AuthenticationPrincipal AppUserDetails userDetails) {
    IntegrationManager.IntegrationActor actor = requireTriggerAuthority(userDetails);
    try {
      InboundAdapter adapter = manager.getInboundAdapter("JIRA").orElse(null);
      if (!(adapter instanceof JiraAdapter ja)) {
        return ResponseEntity.status(503).body(Map.of("error", "Jira integration not enabled"));
      }
      String issueKey = body.get("issueKey");
      if (issueKey == null || issueKey.isBlank()) {
        return ResponseEntity.badRequest().body(Map.of("error", "issueKey is required"));
      }
      String goal = body.get("goal");
      IntegrationEvent event = ja.buildFromIssueKey(issueKey, goal);
      String runId = manager.submitEvent(event, actor);
      return ResponseEntity.ok(Map.of("runId", runId));
    } catch (Exception e) {
      log.error("[Webhook] Jira manual trigger errorType={}", e.getClass().getSimpleName());
      return ResponseEntity.internalServerError().body(Map.of("error", INTERNAL_ERROR));
    }
  }

  /**
   * Manual Confluence trigger: POST /api/integrations/confluence/trigger Body: { "pageId": "12345",
   * "goal": "Refactor auth module" }
   */
  @PostMapping("/confluence/trigger")
  public ResponseEntity<Map<String, String>> confluenceTrigger(
      @RequestBody Map<String, String> body, @AuthenticationPrincipal AppUserDetails userDetails) {
    IntegrationManager.IntegrationActor actor = requireTriggerAuthority(userDetails);
    try {
      InboundAdapter adapter = manager.getInboundAdapter("CONFLUENCE").orElse(null);
      if (!(adapter instanceof ConfluenceAdapter ca)) {
        return ResponseEntity.status(503)
            .body(Map.of("error", "Confluence integration not enabled"));
      }
      String pageId = body.get("pageId");
      String goal = body.get("goal");
      if (pageId == null || pageId.isBlank() || goal == null || goal.isBlank()) {
        return ResponseEntity.badRequest().body(Map.of("error", "pageId and goal are required"));
      }
      IntegrationEvent event = ca.buildFromPageId(pageId, goal);
      String runId = manager.submitEvent(event, actor);
      return ResponseEntity.ok(Map.of("runId", runId));
    } catch (Exception e) {
      log.error("[Webhook] Confluence manual trigger errorType={}", e.getClass().getSimpleName());
      return ResponseEntity.internalServerError().body(Map.of("error", INTERNAL_ERROR));
    }
  }

  /** List integration-triggered runs: GET /api/integrations/runs?source=JIRA */
  @GetMapping("/runs")
  public List<IntegrationRunSummary> listIntegrationRuns(
      @RequestParam(required = false) String source,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireAuthenticated(userDetails);
    // RR-339 — scope to the caller's own org. SUPER_ADMIN keeps the global view; everyone else
    // sees only rows their org owns, and an org-less principal sees nothing (fail closed).
    List<IntegrationRun> rows =
        isSuperAdmin(userDetails)
            ? manager.listAllIntegrationRuns(source)
            : manager.listIntegrationRuns(source, userDetails.getUser().getOrgId());
    return rows.stream().map(this::toSummary).toList();
  }

  private boolean isSuperAdmin(AppUserDetails userDetails) {
    return "SUPER_ADMIN".equals(userDetails.getUser().getGlobalRole());
  }

  /**
   * RR-340 — a manual trigger fetches globally configured external content and spends AI budget, so
   * it needs explicit authority AND a bound payer. Requiring org-admin (or super-admin) is the
   * closest available proxy for a per-integration trigger capability while integration credentials
   * remain platform-global YAML config; the returned actor is what the run is charged to.
   */
  private IntegrationManager.IntegrationActor requireTriggerAuthority(AppUserDetails userDetails) {
    guard.requireOrgAdmin(userDetails);
    String orgId = userDetails.getUser().getOrgId();
    if (orgId == null || orgId.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "An owning organization is required to trigger integration runs");
    }
    return new IntegrationManager.IntegrationActor(userDetails.getUser().getId(), orgId);
  }

  // NOTE: GET /api/integrations/status is handled by IntegrationStatusController
  // (always-available, not @ConditionalOnProperty gated) to avoid ambiguous mapping.

  private IntegrationRunSummary toSummary(IntegrationRun ir) {
    return new IntegrationRunSummary(
        ir.getId(),
        ir.getRunId(),
        ir.getSourceType(),
        ir.getSourceId(),
        ir.getSourceUrl(),
        ir.getCreatedAt());
  }

  /** DTO for integration run list responses. */
  public record IntegrationRunSummary(
      Long id,
      String runId,
      String sourceType,
      String sourceId,
      String sourceUrl,
      java.time.Instant createdAt) {}
}

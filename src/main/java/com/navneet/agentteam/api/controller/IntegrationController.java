package com.navneet.agentteam.api.controller;

import com.navneet.agentteam.integration.InboundAdapter;
import com.navneet.agentteam.integration.IntegrationEvent;
import com.navneet.agentteam.integration.IntegrationManager;
import com.navneet.agentteam.integration.WebhookSignatureException;
import com.navneet.agentteam.integration.confluence.ConfluenceAdapter;
import com.navneet.agentteam.state.entity.IntegrationRun;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/integrations")
@ConditionalOnProperty(name = "agent.integrations.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class IntegrationController {

    private final IntegrationManager manager;

    /** Generic webhook receiver: POST /api/integrations/{source}/webhook */
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
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[Webhook] Error processing {} webhook: {}", source, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Manual Confluence trigger: POST /api/integrations/confluence/trigger
     * Body: { "pageId": "12345", "goal": "Refactor auth module" }
     */
    @PostMapping("/confluence/trigger")
    public ResponseEntity<Map<String, String>> confluenceTrigger(
            @RequestBody Map<String, String> body) {
        try {
            InboundAdapter adapter = manager.getInboundAdapter("CONFLUENCE").orElse(null);
            if (!(adapter instanceof ConfluenceAdapter ca)) {
                return ResponseEntity.status(503).body(Map.of("error", "Confluence integration not enabled"));
            }
            String pageId = body.get("pageId");
            String goal = body.get("goal");
            if (pageId == null || pageId.isBlank() || goal == null || goal.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "pageId and goal are required"));
            }
            IntegrationEvent event = ca.buildFromPageId(pageId, goal);
            String runId = manager.submitEvent(event);
            return ResponseEntity.ok(Map.of("runId", runId));
        } catch (Exception e) {
            log.error("[Webhook] Confluence manual trigger error: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** List integration-triggered runs: GET /api/integrations/runs?source=JIRA */
    @GetMapping("/runs")
    public List<IntegrationRunSummary> listIntegrationRuns(
            @RequestParam(required = false) String source) {
        return manager.listIntegrationRuns(source).stream()
                .map(this::toSummary)
                .toList();
    }

    /** Returns which integrations are currently enabled: GET /api/integrations/status */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("enabledSources", manager.getEnabledSources());
    }

    private IntegrationRunSummary toSummary(IntegrationRun ir) {
        return new IntegrationRunSummary(
                ir.getId(), ir.getRunId(), ir.getSourceType(),
                ir.getSourceId(), ir.getSourceUrl(), ir.getCreatedAt());
    }

    /** DTO for integration run list responses. */
    public record IntegrationRunSummary(
            Long id, String runId, String sourceType,
            String sourceId, String sourceUrl, java.time.Instant createdAt) {}
}

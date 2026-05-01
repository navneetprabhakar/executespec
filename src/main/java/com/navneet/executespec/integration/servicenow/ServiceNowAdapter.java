package com.navneet.executespec.integration.servicenow;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.integration.*;
import com.navneet.executespec.reviewer.model.ReviewReportV2;
import com.navneet.executespec.state.entity.IntegrationRun;
import com.navneet.executespec.state.entity.PipelineRun;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ServiceNowAdapter implements InboundAdapter, OutboundAdapter {

  private final ServiceNowClient client;
  private final IntegrationProperties.ServiceNowConfig config;
  private final ObjectMapper mapper;

  public ServiceNowAdapter(
      ServiceNowClient client, IntegrationProperties.ServiceNowConfig config, ObjectMapper mapper) {
    this.client = client;
    this.config = config;
    this.mapper = mapper;
  }

  @Override
  public String sourceType() {
    return "SERVICENOW";
  }

  // ── Inbound ───────────────────────────────────────────────────────────────

  @Override
  public void verifySignature(Map<String, String> headers, String rawBody) {
    String signatureHeader = headers.get("x-servicenow-signature");
    if (signatureHeader == null && config.getWebhookSecret() == null) return;

    if (config.getWebhookSecret() == null || config.getWebhookSecret().isBlank()) {
      throw new WebhookSignatureException(
          "ServiceNow webhook secret not configured but signature header present");
    }
    if (signatureHeader == null || signatureHeader.isBlank()) {
      throw new WebhookSignatureException("Missing x-servicenow-signature header");
    }

    try {
      javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
      mac.init(
          new javax.crypto.spec.SecretKeySpec(
              config.getWebhookSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8),
              "HmacSHA256"));
      String computed =
          java.util.HexFormat.of()
              .formatHex(mac.doFinal(rawBody.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

      // Header format: "sha256=<hex_hash>" or plain hex
      String expected =
          signatureHeader.startsWith("sha256=") ? signatureHeader.substring(7) : signatureHeader;

      if (!java.security.MessageDigest.isEqual(computed.getBytes(), expected.getBytes())) {
        throw new WebhookSignatureException("ServiceNow HMAC signature mismatch");
      }
    } catch (java.security.NoSuchAlgorithmException | java.security.InvalidKeyException e) {
      throw new WebhookSignatureException("ServiceNow HMAC computation failed: " + e.getMessage());
    }
  }

  @Override
  public String extractEventId(String rawBody) {
    try {
      ServiceNowWebhookPayload payload = mapper.readValue(rawBody, ServiceNowWebhookPayload.class);
      if (payload.eventId() != null) return payload.eventId();
      if (payload.record() != null && payload.record().sysId() != null) {
        return "snow-" + payload.record().sysId() + "-" + System.currentTimeMillis();
      }
    } catch (Exception ignored) {
    }
    return UUID.randomUUID().toString();
  }

  @Override
  public Optional<IntegrationEvent> parseEvent(String rawBody) {
    try {
      ServiceNowWebhookPayload payload = mapper.readValue(rawBody, ServiceNowWebhookPayload.class);
      ServiceNowWebhookPayload.ServiceNowRecord inc = payload.record();

      // Only trigger for configured categories
      if (inc.category() == null || !config.getTriggerCategories().contains(inc.category())) {
        return Optional.empty();
      }

      String goal =
          String.format(
              "Investigate and resolve ServiceNow incident %s: %s",
              inc.number(), inc.shortDescription());

      StringBuilder context = new StringBuilder();
      context.append("## Incident: ").append(inc.number()).append("\n");
      context
          .append("**Description**: ")
          .append(inc.description() != null ? inc.description() : "")
          .append("\n");

      // Fetch and inject runbook if KB article is linked
      if (inc.kbArticle() != null && !inc.kbArticle().isBlank()) {
        String runbook = client.fetchKbArticle(inc.kbArticle());
        if (!runbook.isBlank()) {
          context.append("\n## Linked Runbook\n").append(runbook);
        }
      }

      String ac =
          "Root cause identified and documented. "
              + "Resolution steps applied or recommended. "
              + "Work note added to incident with findings.";

      return Optional.of(
          new IntegrationEvent(
              "SERVICENOW",
              inc.sysId(),
              config.getBaseUrl() + "/nav_to.do?uri=incident.do?sys_id=" + inc.sysId(),
              goal,
              context.toString(),
              ac,
              config.isSkipApproval()));
    } catch (Exception e) {
      log.error("[ServiceNow] Failed to parse event: {}", e.getMessage(), e);
      return Optional.empty();
    }
  }

  // ── Outbound ──────────────────────────────────────────────────────────────

  @Override
  public void onRunStarted(IntegrationRun ir, PipelineRun run) {
    String note =
        String.format(
            "🤖 Automated analysis started (Agent Run: %s). Monitor at: %s/runs/%s",
            run.getRunId(), config.getDashboardUrl(), run.getRunId());
    client.addWorkNote(ir.getSourceId(), note);
  }

  @Override
  public void onRunComplete(IntegrationRun ir, PipelineRun run, ReviewReportV2 review) {
    String verdict = review.getRecommendationCompat();
    String quality = review.getOverallConfidence() != null ? review.getOverallConfidence() : "N/A";
    String recommendedActions =
        review.getPostMergeSuggestions() != null
            ? String.join("\n", review.getPostMergeSuggestions())
            : "None";

    String note =
        String.format(
            """
            🤖 Automated Analysis Complete (Agent Run: %s)

            Verdict: %s | Quality: %s

            Summary:
            %s

            Recommended actions:
            %s

            Full details: %s/runs/%s""",
            run.getRunId(),
            verdict,
            quality,
            review.getSummary() != null ? review.getSummary() : "",
            recommendedActions,
            config.getDashboardUrl(),
            run.getRunId());

    client.addWorkNote(ir.getSourceId(), note);

    if (config.isAutoResolve() && "MERGE".equals(review.getRecommendationCompat())) {
      try {
        client.resolveIncident(
            ir.getSourceId(), "Resolved by automated agent run " + run.getRunId());
      } catch (Exception e) {
        log.warn(
            "[ServiceNow] Auto-resolve failed for incident {}: {}",
            ir.getSourceId(),
            e.getMessage());
      }
    }
  }

  @Override
  public void onRunFailed(IntegrationRun ir, PipelineRun run, String reason) {
    String note =
        String.format(
            "❌ Automated run failed (Agent Run: %s)\n"
                + "Reason: %s\n"
                + "Manual intervention required.\n"
                + "Dashboard: %s/runs/%s",
            run.getRunId(), reason, config.getDashboardUrl(), run.getRunId());
    client.addWorkNote(ir.getSourceId(), note);
  }
}

package com.navneet.executespec.integration.pagerduty;

import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.integration.*;
import com.navneet.executespec.reviewer.model.ReviewReport;
import com.navneet.executespec.state.entity.IntegrationRun;
import com.navneet.executespec.state.entity.PipelineRun;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PagerDutyAdapter implements InboundAdapter, OutboundAdapter {

  private final PagerDutyClient client;
  private final IntegrationProperties.PagerDutyConfig config;
  private final ObjectMapper mapper;

  public PagerDutyAdapter(
      PagerDutyClient client, IntegrationProperties.PagerDutyConfig config, ObjectMapper mapper) {
    this.client = client;
    this.config = config;
    this.mapper = mapper;
  }

  @Override
  public String sourceType() {
    return "PAGERDUTY";
  }

  @Override
  public void verifySignature(Map<String, String> headers, String rawBody) {
    // Fail CLOSED: a missing secret OR a missing signature header must NOT bypass verification.
    // The old `signatureHeader == null || secret == null) return;` let an attacker simply omit
    // the x-pagerduty-signature header to skip the HMAC check entirely. C8 — audit 2026-05-29.
    if (config.getWebhookSecret() == null || config.getWebhookSecret().isBlank()) {
      throw new WebhookSignatureException(
          "PagerDuty webhook: configure agent.integrations.pagerduty.webhook-secret to enable"
              + " inbound webhooks");
    }
    String signatureHeader = headers.get("x-pagerduty-signature");
    if (signatureHeader == null || signatureHeader.isBlank()) {
      throw new WebhookSignatureException("PagerDuty webhook signature header missing");
    }

    // Header format: "v1=<hex_hash>"
    String expected = signatureHeader.replace("v1=", "");
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(config.getWebhookSecret().getBytes(), "HmacSHA256"));
      String computed = HexFormat.of().formatHex(mac.doFinal(rawBody.getBytes()));
      if (!MessageDigest.isEqual(computed.getBytes(), expected.getBytes())) {
        throw new WebhookSignatureException("PagerDuty HMAC mismatch");
      }
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new WebhookSignatureException("PagerDuty HMAC error: " + e.getMessage());
    }
  }

  @Override
  public String extractEventId(String rawBody) {
    try {
      PagerDutyWebhookPayload payload = mapper.readValue(rawBody, PagerDutyWebhookPayload.class);
      // RR-639 — dedupe on the message this adapter will actually act on (the first
      // trigger-matching
      // one), not blindly on messages[0]. When no message triggers, fall back to messages[0].id so
      // an ignored batch still dedupes on retry.
      PagerDutyWebhookPayload.PdMessage selected = firstTriggerMessage(payload);
      if (selected != null && selected.id() != null) {
        return selected.id();
      }
      if (payload.messages() != null && !payload.messages().isEmpty()) {
        return payload.messages().get(0).id();
      }
    } catch (Exception ignored) {
    }
    return UUID.randomUUID().toString();
  }

  /**
   * RR-639 — a single PagerDuty webhook delivery can carry multiple messages. The old code
   * inspected only {@code messages[0]}, so a batch whose first message was an ignored type silently
   * discarded later trigger-matching incidents, and a trigger message in position 2+ was lost.
   * Select the first message whose type is configured to trigger. (One delivery still produces at
   * most one run — the idempotency model is one event id per delivery — but a trigger message is no
   * longer buried behind a non-trigger first message; additional trigger-matching messages are
   * logged as dropped.)
   */
  private PagerDutyWebhookPayload.PdMessage firstTriggerMessage(PagerDutyWebhookPayload payload) {
    if (payload == null || payload.messages() == null) return null;
    PagerDutyWebhookPayload.PdMessage first = null;
    int additionalTriggers = 0;
    for (PagerDutyWebhookPayload.PdMessage m : payload.messages()) {
      if (m != null && m.type() != null && config.getTriggerTypes().contains(m.type())) {
        if (first == null) {
          first = m;
        } else {
          additionalTriggers++;
        }
      }
    }
    if (additionalTriggers > 0) {
      log.warn(
          "[PagerDuty] Batch delivery carried {} additional trigger-matching message(s) beyond the"
              + " one processed; only the first is run (one run per delivery).",
          additionalTriggers);
    }
    return first;
  }

  @Override
  public Optional<IntegrationEvent> parseEvent(String rawBody) {
    try {
      PagerDutyWebhookPayload payload = mapper.readValue(rawBody, PagerDutyWebhookPayload.class);
      if (payload.messages() == null || payload.messages().isEmpty()) return Optional.empty();

      // RR-639 — find the first trigger-matching message across the whole batch, not just index 0.
      PagerDutyWebhookPayload.PdMessage msg = firstTriggerMessage(payload);
      if (msg == null) return Optional.empty();

      PagerDutyWebhookPayload.PdIncident inc = msg.incident();
      String goal =
          String.format(
              "Investigate PagerDuty incident #%s: %s", inc.incidentNumber(), inc.title());

      String serviceName = inc.service() != null ? inc.service().name() : "Unknown";
      String serviceDesc =
          inc.service() != null && inc.service().description() != null
              ? inc.service().description()
              : "";

      String context =
          String.format(
              """
              ## PagerDuty Incident #%s
              **Title**: %s
              **Service**: %s
              **Service Description**: %s
              **Status**: %s
              **URL**: %s
              """,
              inc.incidentNumber(),
              inc.title(),
              serviceName,
              serviceDesc,
              inc.status(),
              inc.htmlUrl());

      String ac =
          "Root cause identified. Post-incident report (timeline, impact, remediation) drafted. "
              + "Resolution steps applied or recommended.";

      return Optional.of(
          new IntegrationEvent(
              "PAGERDUTY", inc.id(), inc.htmlUrl(), goal, context, ac, config.isSkipApproval()));
    } catch (Exception e) {
      log.error("[PagerDuty] Failed to parse event: {}", e.getMessage(), e);
      return Optional.empty();
    }
  }

  @Override
  public void onRunStarted(IntegrationRun ir, PipelineRun run) {
    String note =
        String.format(
            "🤖 Agent investigation started (run ID: %s). Monitor at: %s/runs/%s",
            run.getRunId(), config.getDashboardUrl(), run.getRunId());
    client.addNote(ir.getSourceId(), note);
  }

  @Override
  public void onRunComplete(IntegrationRun ir, PipelineRun run, ReviewReport review) {
    String verdict = review.getRecommendationCompat();
    String quality = review.getOverallConfidence() != null ? review.getOverallConfidence() : "N/A";
    String recommendedActions =
        review.getPostMergeSuggestions() != null
            ? String.join("\n", review.getPostMergeSuggestions())
            : "None";

    String report =
        String.format(
            """
            🤖 Post-Incident Report (Agent Run: %s)

            ## Findings
            %s

            ## Quality Rating: %s | Verdict: %s

            ## Recommended Actions
            %s

            Dashboard: %s/runs/%s""",
            run.getRunId(),
            review.getSummary() != null ? review.getSummary() : "",
            quality,
            verdict,
            recommendedActions,
            config.getDashboardUrl(),
            run.getRunId());

    client.addNote(ir.getSourceId(), report);

    if (config.isAutoResolve() && "MERGE".equals(review.getRecommendationCompat())) {
      try {
        client.resolveIncident(ir.getSourceId());
      } catch (Exception e) {
        log.warn(
            "[PagerDuty] Auto-resolve failed for incident {}: {}",
            ir.getSourceId(),
            e.getMessage());
      }
    }
  }

  @Override
  public void onRunFailed(IntegrationRun ir, PipelineRun run, String reason) {
    String note =
        String.format(
            "❌ Agent investigation failed (run ID: %s)\nReason: %s\nDashboard: %s/runs/%s",
            run.getRunId(), reason, config.getDashboardUrl(), run.getRunId());
    client.addNote(ir.getSourceId(), note);
  }
}

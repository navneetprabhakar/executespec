package com.navneet.executespec.integration.bitbucket;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.integration.*;
import com.navneet.executespec.reviewer.model.ReviewReport;
import com.navneet.executespec.state.entity.IntegrationRun;
import com.navneet.executespec.state.entity.PipelineRun;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class BitbucketAdapter implements InboundAdapter, OutboundAdapter {

  /** Extracts workspace and repo slug from a Bitbucket PR URL. */
  private static final Pattern PR_URL_PATTERN =
      Pattern.compile("bitbucket\\.org/([^/]+)/([^/]+)/pull-requests/(\\d+)");

  private final BitbucketClient client;
  private final IntegrationProperties.BitbucketConfig config;
  private final ObjectMapper mapper;

  @Override
  public String sourceType() {
    return "BITBUCKET";
  }

  // -- Inbound ---------------------------------------------------------------

  @Override
  public void verifySignature(Map<String, String> headers, String rawBody) {
    // RR-637 — fail CLOSED. "No secret configured" must mean "reject every request", never "skip
    // verification": an unsigned POST would otherwise submit attacker-controlled PR text as a
    // pipeline run under the configured runner identity, spending platform LLM budget. Matches the
    // PagerDuty adapter's posture.
    if (config.getWebhookSecret() == null || config.getWebhookSecret().isBlank()) {
      throw new WebhookSignatureException(
          "Bitbucket webhook: configure agent.integrations.bitbucket.webhook-secret to enable"
              + " inbound webhooks");
    }

    String signatureHeader = headers.get("x-hub-signature");
    if (signatureHeader == null || signatureHeader.isBlank()) {
      throw new WebhookSignatureException("Missing x-hub-signature header for Bitbucket webhook");
    }

    try {
      javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
      mac.init(
          new javax.crypto.spec.SecretKeySpec(
              config.getWebhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      String computed =
          java.util.HexFormat.of().formatHex(mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8)));

      // Header format: "sha256=<hex_hash>" or plain hex
      String expected =
          signatureHeader.startsWith("sha256=") ? signatureHeader.substring(7) : signatureHeader;

      if (!MessageDigest.isEqual(computed.getBytes(), expected.getBytes())) {
        throw new WebhookSignatureException("Bitbucket HMAC signature mismatch");
      }
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new WebhookSignatureException("Bitbucket HMAC computation failed: " + e.getMessage());
    }
  }

  /** The only Bitbucket PR event key that triggers a run — a newly opened PR to implement. */
  private static final String TRIGGER_EVENT_KEY = "pullrequest:created";

  @Override
  public String extractEventId(String rawBody) {
    return extractEventId(Map.of(), rawBody);
  }

  @Override
  public String extractEventId(Map<String, String> headers, String rawBody) {
    // RR-638 — prefer Bitbucket's stable per-delivery id (X-Request-UUID) so distinct PR lifecycle
    // events (created / updated / approved / merged) are NOT collapsed onto one id, which the old
    // "bb-pr-<id>" scheme did — permanently discarding every event after the first for a PR.
    // Retries
    // of the SAME delivery reuse the UUID and dedupe correctly.
    String deliveryId = headers == null ? null : headers.get("x-request-uuid");
    if (deliveryId != null && !deliveryId.isBlank()) {
      return "bb-" + deliveryId.trim();
    }
    try {
      JsonNode root = mapper.readTree(rawBody);
      JsonNode pr = root.path("pullrequest");
      String eventKey = headers == null ? "" : headers.getOrDefault("x-event-key", "");
      if (pr.has("id")) {
        // No delivery UUID available: qualify by event key so at least distinct lifecycle events
        // for one PR are distinguishable rather than all sharing the PR id.
        return "bb-pr-" + pr.get("id").asText() + (eventKey.isBlank() ? "" : "-" + eventKey);
      }
      return UUID.randomUUID().toString();
    } catch (Exception e) {
      return UUID.randomUUID().toString();
    }
  }

  @Override
  public Optional<IntegrationEvent> parseEvent(String rawBody) {
    return parseEvent(Map.of(), rawBody);
  }

  @Override
  public Optional<IntegrationEvent> parseEvent(Map<String, String> headers, String rawBody) {
    try {
      JsonNode root = mapper.readTree(rawBody);

      // RR-638 — honour the authoritative X-Event-Key header. The old code accepted ANY payload
      // containing a pullrequest node, so an update / approval / merge webhook could trigger a run.
      // When the header is present it must be the exact trigger key; when it is absent (older proxy
      // that strips it) we fall back to the legacy structural acceptance below.
      String eventKey = headers == null ? "" : headers.getOrDefault("x-event-key", "");
      if (!eventKey.isBlank() && !TRIGGER_EVENT_KEY.equalsIgnoreCase(eventKey.trim())) {
        log.debug("[Bitbucket] Ignoring non-trigger event key '{}'", eventKey);
        return Optional.empty();
      }

      JsonNode pr = root.path("pullrequest");
      if (pr.isMissingNode() || !pr.has("id")) {
        log.debug("[Bitbucket] Ignoring event — no pullrequest in payload");
        return Optional.empty();
      }

      String title = pr.path("title").asText("");
      String description = pr.path("description").asText("");
      int prId = pr.path("id").asInt();

      // Build source URL from PR links
      String sourceUrl = pr.path("links").path("html").path("href").asText("");

      String goal = String.format("Implement Bitbucket PR #%d: %s", prId, title);
      String context =
          String.format(
              """
              ## Bitbucket Pull Request #%d
              **Title**: %s
              **Description**:
              %s
              """,
              prId, title, description);

      return Optional.of(
          new IntegrationEvent(
              "BITBUCKET",
              String.valueOf(prId),
              sourceUrl,
              goal,
              context,
              "",
              config.isSkipApproval()));
    } catch (Exception e) {
      log.error("[Bitbucket] Failed to parse event: {}", e.getMessage(), e);
      return Optional.empty();
    }
  }

  // -- Outbound --------------------------------------------------------------

  @Override
  public void onRunStarted(IntegrationRun ir, PipelineRun run) {
    String msg =
        String.format(
            "\uD83D\uDE80 ExecuteSpec run started (run ID: %s). Monitor at: %s/runs/%s",
            run.getRunId(), config.getDashboardUrl(), run.getRunId());
    postComment(ir, msg);
  }

  @Override
  public void onRunComplete(IntegrationRun ir, PipelineRun run, ReviewReport review) {
    String msg;
    if (review != null) {
      String verdict = review.getRecommendationCompat();
      String quality =
          review.getOverallConfidence() != null ? review.getOverallConfidence() : "N/A";
      String suggestions =
          review.getPostMergeSuggestions() != null
              ? String.join(", ", review.getPostMergeSuggestions())
              : "";

      msg =
          String.format(
              """
              \u2705 ExecuteSpec run complete (run ID: %s)
              **Verdict**: %s | **Quality**: %s
              **Post-merge suggestions**: %s
              **Review summary**: %s
              Dashboard: %s/runs/%s""",
              run.getRunId(),
              verdict,
              quality,
              suggestions,
              review.getSummary() != null ? review.getSummary() : "",
              config.getDashboardUrl(),
              run.getRunId());
    } else {
      msg =
          String.format(
              """
              \u2705 ExecuteSpec run complete (run ID: %s)
              Dashboard: %s/runs/%s""",
              run.getRunId(), config.getDashboardUrl(), run.getRunId());
    }
    postComment(ir, msg);
  }

  @Override
  public void onRunFailed(IntegrationRun ir, PipelineRun run, String reason) {
    String msg =
        String.format(
            """
            \u274C ExecuteSpec run failed (run ID: %s)
            **Reason**: %s
            Dashboard: %s/runs/%s""",
            run.getRunId(), reason, config.getDashboardUrl(), run.getRunId());
    postComment(ir, msg);
  }

  // -- Helpers ---------------------------------------------------------------

  private void postComment(IntegrationRun ir, String message) {
    try {
      int prId = Integer.parseInt(ir.getSourceId());
      String sourceUrl = ir.getSourceUrl();

      String workspace = config.getWorkspace();
      String repoSlug = config.getRepoSlug();

      // Try to extract workspace/repo from the source URL if not in config
      if ((workspace == null || repoSlug == null) && sourceUrl != null) {
        Matcher m = PR_URL_PATTERN.matcher(sourceUrl);
        if (m.find()) {
          workspace = m.group(1);
          repoSlug = m.group(2);
        }
      }

      if (workspace == null || repoSlug == null) {
        log.warn(
            "[Bitbucket] Cannot post comment — workspace/repoSlug not resolvable for PR {}",
            ir.getSourceId());
        return;
      }

      client.postPrComment(
          workspace, repoSlug, prId, message, config.getUsername(), config.getAppPassword());
    } catch (Exception e) {
      log.warn("[Bitbucket] Failed to post comment on PR {}: {}", ir.getSourceId(), e.getMessage());
    }
  }
}

package com.navneet.executespec.integration.confluence;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.integration.InboundAdapter;
import com.navneet.executespec.integration.IntegrationEvent;
import com.navneet.executespec.integration.IntegrationProperties;
import com.navneet.executespec.integration.OutboundAdapter;
import com.navneet.executespec.reviewer.model.ReviewReport;
import com.navneet.executespec.state.entity.IntegrationRun;
import com.navneet.executespec.state.entity.PipelineRun;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ConfluenceAdapter implements InboundAdapter, OutboundAdapter {

  private final ConfluenceClient client;
  private final IntegrationProperties.ConfluenceConfig config;
  private final ObjectMapper mapper;

  public ConfluenceAdapter(
      ConfluenceClient client, IntegrationProperties.ConfluenceConfig config, ObjectMapper mapper) {
    this.client = client;
    this.config = config;
    this.mapper = mapper;
  }

  @Override
  public String sourceType() {
    return "CONFLUENCE";
  }

  // ── Inbound ───────────────────────────────────────────────────────────────

  @Override
  public void verifySignature(Map<String, String> headers, String rawBody) {
    // Audit 2026-05-12 review item C #21 — was a no-op accepting any POST. Confluence Cloud
    // sends an HMAC-SHA256 hex digest in X-Hub-Signature when a webhook secret is configured;
    // verify it constant-time. If no secret is configured, refuse the webhook (prod posture)
    // unless the caller can also present the bearer apiToken in Authorization (legacy dev
    // posture).
    String webhookSecret = config.getWebhookSecret();
    if (webhookSecret != null && !webhookSecret.isBlank()) {
      String header = headers.getOrDefault("x-hub-signature", "");
      // Accept both "sha256=<hex>" and bare hex forms.
      String received = header.startsWith("sha256=") ? header.substring(7) : header;
      String expected =
          com.navneet.executespec.util.HmacUtil.hexSha256(webhookSecret, rawBody == null ? "" : rawBody);
      if (!java.security.MessageDigest.isEqual(
          expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
          received.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
        throw new com.navneet.executespec.integration.WebhookSignatureException(
            "Confluence webhook HMAC mismatch");
      }
      return;
    }
    // Legacy fallback: bearer apiToken in Authorization header (dev-friendly path).
    if (config.getApiToken() != null && !config.getApiToken().isBlank()) {
      String authHeader = headers.getOrDefault("authorization", "");
      String expected = "Bearer " + config.getApiToken();
      if (!java.security.MessageDigest.isEqual(
          expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
          authHeader.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
        throw new com.navneet.executespec.integration.WebhookSignatureException(
            "Confluence bearer token mismatch");
      }
      return;
    }
    throw new com.navneet.executespec.integration.WebhookSignatureException(
        "Confluence webhook: configure integration.confluence.webhook-secret to enable inbound"
            + " webhooks");
  }

  @Override
  public String extractEventId(String rawBody) {
    try {
      ConfluenceWebhookPayload payload = mapper.readValue(rawBody, ConfluenceWebhookPayload.class);
      if (payload.event() != null && payload.page() != null && payload.page().id() != null) {
        return payload.event() + "-" + payload.page().id();
      }
    } catch (Exception ignored) {
    }
    return UUID.randomUUID().toString();
  }

  @Override
  public Optional<IntegrationEvent> parseEvent(String rawBody) {
    try {
      ConfluenceWebhookPayload payload = mapper.readValue(rawBody, ConfluenceWebhookPayload.class);

      // Only fire on page_created or page_updated
      if (!"page_created".equals(payload.event()) && !"page_updated".equals(payload.event())) {
        return Optional.empty();
      }

      // Only pages with the configured trigger label
      boolean hasLabel =
          payload.page() != null
              && payload.page().labels() != null
              && payload.page().labels().stream()
                  .anyMatch(l -> config.getTriggerLabel().equals(l.name()));
      if (!hasLabel) return Optional.empty();

      String pageId = payload.page().id();
      String title = payload.page().title();
      String content = client.fetchPageContent(pageId);
      String ac = extractSection(content, "Acceptance Criteria");

      return Optional.of(
          new IntegrationEvent(
              "CONFLUENCE",
              pageId,
              config.getBaseUrl() + "/wiki/pages/viewpage.action?pageId=" + pageId,
              title,
              content,
              ac,
              config.isSkipApproval()));
    } catch (Exception e) {
      log.error("[Confluence] Failed to parse event: {}", e.getMessage(), e);
      return Optional.empty();
    }
  }

  /**
   * Manual trigger: build an IntegrationEvent from a page ID + explicit goal. Called by
   * IntegrationController.confluenceTrigger().
   */
  public IntegrationEvent buildFromPageId(String pageId, String goal) {
    String content = client.fetchPageContent(pageId);
    String ac = extractSection(content, "Acceptance Criteria");
    return new IntegrationEvent(
        "CONFLUENCE",
        pageId,
        config.getBaseUrl() + "/wiki/pages/viewpage.action?pageId=" + pageId,
        goal,
        content,
        ac,
        config.isSkipApproval());
  }

  // ── Outbound ──────────────────────────────────────────────────────────────

  @Override
  public void onRunStarted(IntegrationRun ir, PipelineRun run) {
    // Confluence doesn't need a "started" notification — child page is created on complete
    log.info("[Confluence] Run {} started for page {}", run.getRunId(), ir.getSourceId());
  }

  @Override
  public void onRunComplete(IntegrationRun ir, PipelineRun run, ReviewReport review) {
    String childTitle =
        (run.getUserRequest() != null
                ? run.getUserRequest().lines().findFirst().orElse("Agent run")
                : "Agent run")
            + " — Agent Output";
    String content =
        review != null
            ? buildOutputPage(run, review)
            : String.format(
                """
                # Agent Run Output
                **Run ID**: %s  |  **Status**: COMPLETE

                [View Dashboard](%s/runs/%s)
                """,
                run.getRunId(), config.getDashboardUrl(), run.getRunId());
    String spaceKey = config.getSpaceKey() != null ? config.getSpaceKey() : "ENG";
    try {
      client.createChildPage(ir.getSourceId(), spaceKey, childTitle, content);
    } catch (Exception e) {
      log.warn(
          "[Confluence] Could not create completion page for run {}: {}",
          run.getRunId(),
          e.getMessage());
    }
  }

  @Override
  public void onRunFailed(IntegrationRun ir, PipelineRun run, String reason) {
    String childTitle = "Agent Run Failed — " + run.getRunId().substring(0, 8);
    String content =
        String.format(
            """
            # Agent Run Failed

            **Run ID**: %s
            **Reason**: %s

            [View Dashboard](%s/runs/%s)
            """,
            run.getRunId(), reason, config.getDashboardUrl(), run.getRunId());
    String spaceKey = config.getSpaceKey() != null ? config.getSpaceKey() : "ENG";
    try {
      client.createChildPage(ir.getSourceId(), spaceKey, childTitle, content);
    } catch (Exception e) {
      log.warn(
          "[Confluence] Could not create failure page for run {}: {}",
          run.getRunId(),
          e.getMessage());
    }
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private String buildOutputPage(PipelineRun run, ReviewReport review) {
    String verdict = review.getRecommendationCompat();
    String quality = review.getOverallConfidence() != null ? review.getOverallConfidence() : "N/A";

    String issueBreakdown =
        review.getIssues() != null && !review.getIssues().isEmpty()
            ? review.getIssues().stream()
                .map(
                    i ->
                        "- **"
                            + i.getSeverity()
                            + "** ["
                            + i.getCategory()
                            + "] "
                            + i.getDescription()
                            + (i.isAutoFixed()
                                ? (i.isFixSucceeded() ? " ✅ Fixed" : " ❌ Fix failed")
                                : ""))
                .collect(Collectors.joining("\n"))
            : "No issues found";

    String suggestions =
        review.getPostMergeSuggestions() != null
            ? String.join("\n", review.getPostMergeSuggestions())
            : "None";

    return String.format(
        """
        # Agent Run Output
        **Run ID**: %s  |  **Status**: %s  |  **Quality**: %s

        ## Review Summary
        %s

        ## Issues Found
        %s

        ## Post-merge Suggestions
        %s

        [View Dashboard](%s/runs/%s)
        """,
        run.getRunId(),
        run.getStatus(),
        quality,
        review.getSummary() != null ? review.getSummary() : "",
        issueBreakdown,
        suggestions,
        config.getDashboardUrl(),
        run.getRunId());
  }

  /** Extract text under a specific heading from plain-text page content. */
  private String extractSection(String content, String heading) {
    if (content == null || heading == null) return "";
    int idx = content.indexOf(heading);
    if (idx < 0) return "";
    String after = content.substring(idx + heading.length());
    int nextHeading = after.indexOf("\n#");
    return nextHeading > 0 ? after.substring(0, nextHeading).trim() : after.trim();
  }
}

package com.navneet.executespec.integration.jira;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.integration.*;
import com.navneet.executespec.reviewer.model.ReviewReport;
import com.navneet.executespec.state.entity.IntegrationRun;
import com.navneet.executespec.state.entity.PipelineRun;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JiraAdapter implements InboundAdapter, OutboundAdapter {

  private final JiraClient client;
  private final IntegrationProperties.JiraConfig config;
  private final ObjectMapper mapper;

  public JiraAdapter(
      JiraClient client, IntegrationProperties.JiraConfig config, ObjectMapper mapper) {
    this.client = client;
    this.config = config;
    this.mapper = mapper;
  }

  @Override
  public String sourceType() {
    return "JIRA";
  }

  // ── Inbound ───────────────────────────────────────────────────────────────

  @Override
  public void verifySignature(Map<String, String> headers, String rawBody) {
    if (config.getWebhookSecret() == null || config.getWebhookSecret().isBlank()) return;
    // Audit 2026-05-12 review items C #22, #23:
    //  - JWT path previously trusted "Authorization: JWT ..." without signature verification.
    //    The shared-secret fallback below is the only currently-supported posture; if Atlassian
    //    JWT mode is needed, integrate atlassian-connect-spring-boot's QSH validator instead of
    //    a `startsWith("JWT ")` no-op.
    //  - Shared-secret comparison now uses MessageDigest.isEqual (constant-time) to close the
    //    timing-attack side channel that String.equals exposes on early-mismatch returns.
    String authHeader = headers.getOrDefault("authorization", "");
    if (authHeader.startsWith("JWT ")) {
      throw new WebhookSignatureException(
          "Jira JWT signature verification is not implemented; configure shared-secret"
              + " (x-hub-signature) instead");
    }
    String received = headers.getOrDefault("x-hub-signature", "");
    byte[] expected =
        config.getWebhookSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] actual = received.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    if (!java.security.MessageDigest.isEqual(expected, actual)) {
      throw new WebhookSignatureException("Jira webhook signature mismatch");
    }
  }

  @Override
  public String extractEventId(String rawBody) {
    try {
      JsonNode root = mapper.readTree(rawBody);
      return root.path("timestamp").asText() + "-" + root.path("issue").path("key").asText();
    } catch (Exception e) {
      return UUID.randomUUID().toString();
    }
  }

  @Override
  public Optional<IntegrationEvent> parseEvent(String rawBody) {
    try {
      JiraWebhookPayload payload = mapper.readValue(rawBody, JiraWebhookPayload.class);

      // Only act on status field changes to the configured trigger status
      if (payload.changelog() == null || payload.changelog().items() == null) {
        return Optional.empty();
      }
      boolean isMatchingStatusChange =
          payload.changelog().items().stream()
              .anyMatch(
                  i ->
                      "status".equals(i.field())
                          && config.getTriggerStatus().equalsIgnoreCase(i.toStringValue()));
      if (!isMatchingStatusChange) return Optional.empty();

      JiraWebhookPayload.JiraIssue issue = payload.issue();
      String summary = issue.fields().summary();

      // description may be ADF JSON object or plain string
      String description = "";
      if (issue.fields().description() instanceof String s) {
        description = s;
      } else if (issue.fields().description() != null) {
        description = mapper.writeValueAsString(issue.fields().description());
      }

      String ac =
          issue.fields().acceptanceCriteria() != null ? issue.fields().acceptanceCriteria() : "";

      String goal = String.format("Implement Jira issue %s: %s", issue.key(), summary);
      String context =
          String.format(
              """
              ## Jira Issue: %s
              **Summary**: %s
              **Description**:
              %s
              """,
              issue.key(), summary, description);

      return Optional.of(
          new IntegrationEvent(
              "JIRA",
              issue.key(),
              config.getBaseUrl() + "/browse/" + issue.key(),
              goal,
              context,
              ac,
              config.isSkipApproval()));
    } catch (Exception e) {
      log.error("[Jira] Failed to parse event: {}", e.getMessage(), e);
      return Optional.empty();
    }
  }

  // ── Manual trigger ─────────────────────────────────────────────────────────

  /**
   * Build an IntegrationEvent by fetching a Jira issue directly via REST API. Mirrors
   * ConfluenceAdapter.buildFromPageId() — used by the manual trigger endpoint.
   */
  @SuppressWarnings("unchecked")
  public IntegrationEvent buildFromIssueKey(String issueKey, String goalOverride) {
    Map<String, Object> issue = client.fetchIssue(issueKey);
    Map<String, Object> fields = (Map<String, Object>) issue.get("fields");

    String summary = String.valueOf(fields.getOrDefault("summary", ""));
    String description = "";
    Object descObj = fields.get("description");
    if (descObj instanceof String s) {
      description = s;
    } else if (descObj != null) {
      try {
        description = mapper.writeValueAsString(descObj);
      } catch (Exception ignored) {
      }
    }

    String ac =
        fields.get("customfield_10034") != null
            ? String.valueOf(fields.get("customfield_10034"))
            : "";

    String goal =
        (goalOverride != null && !goalOverride.isBlank())
            ? goalOverride
            : "Implement Jira issue " + issueKey + ": " + summary;

    String context =
        String.format(
            """
            ## Jira Issue: %s
            **Summary**: %s
            **Description**:
            %s
            """,
            issueKey, summary, description);

    return new IntegrationEvent(
        "JIRA",
        issueKey,
        config.getBaseUrl() + "/browse/" + issueKey,
        goal,
        context,
        ac,
        config.isSkipApproval());
  }

  // ── Outbound ──────────────────────────────────────────────────────────────

  @Override
  public void onRunStarted(IntegrationRun ir, PipelineRun run) {
    String msg =
        String.format(
            "🤖 Agent run started (run ID: %s). Monitor at: %s/runs/%s",
            run.getRunId(), config.getDashboardUrl(), run.getRunId());
    client.addComment(ir.getSourceId(), msg);
    if (config.getInProgressStatus() != null && !config.getInProgressStatus().isBlank()) {
      try {
        client.transitionIssue(ir.getSourceId(), config.getInProgressStatus());
      } catch (Exception e) {
        log.warn(
            "[Jira] Could not transition {} to '{}': {}",
            ir.getSourceId(),
            config.getInProgressStatus(),
            e.getMessage());
      }
    }
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
              ✅ Agent run complete (run ID: %s)
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
              ✅ Agent run complete (run ID: %s)
              Dashboard: %s/runs/%s""",
              run.getRunId(), config.getDashboardUrl(), run.getRunId());
    }
    client.addComment(ir.getSourceId(), msg);

    if (config.getInReviewStatus() != null && !config.getInReviewStatus().isBlank()) {
      try {
        client.transitionIssue(ir.getSourceId(), config.getInReviewStatus());
      } catch (Exception e) {
        log.warn(
            "[Jira] Could not transition {} to '{}': {}",
            ir.getSourceId(),
            config.getInReviewStatus(),
            e.getMessage());
      }
    }
  }

  @Override
  public void onRunFailed(IntegrationRun ir, PipelineRun run, String reason) {
    String msg =
        String.format(
            """
            ❌ Agent run failed (run ID: %s)
            **Reason**: %s
            Dashboard: %s/runs/%s""",
            run.getRunId(), reason, config.getDashboardUrl(), run.getRunId());
    client.addComment(ir.getSourceId(), msg);

    if (config.getReopenStatus() != null && !config.getReopenStatus().isBlank()) {
      try {
        client.transitionIssue(ir.getSourceId(), config.getReopenStatus());
      } catch (Exception e) {
        log.warn(
            "[Jira] Could not reopen {} to '{}': {}",
            ir.getSourceId(),
            config.getReopenStatus(),
            e.getMessage());
      }
    }
  }
}

package com.navneet.agentteam.integration.confluence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.agentteam.agent.model.ReviewReport;
import com.navneet.agentteam.integration.InboundAdapter;
import com.navneet.agentteam.integration.IntegrationEvent;
import com.navneet.agentteam.integration.IntegrationProperties;
import com.navneet.agentteam.integration.OutboundAdapter;
import com.navneet.agentteam.integration.WebhookSignatureException;
import com.navneet.agentteam.state.entity.IntegrationRun;
import com.navneet.agentteam.state.entity.PipelineRun;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
public class ConfluenceAdapter implements InboundAdapter, OutboundAdapter {

    private final ConfluenceClient client;
    private final IntegrationProperties.ConfluenceConfig config;
    private final ObjectMapper mapper;

    public ConfluenceAdapter(ConfluenceClient client, IntegrationProperties.ConfluenceConfig config, ObjectMapper mapper) {
        this.client = client;
        this.config = config;
        this.mapper = mapper;
    }

    @Override
    public String sourceType() { return "CONFLUENCE"; }

    // ── Inbound ───────────────────────────────────────────────────────────────

    @Override
    public void verifySignature(Map<String, String> headers, String rawBody) {
        // Confluence webhooks use shared secret in Authorization header
        if (config.getApiToken() == null) return;
        // No standard signature scheme; skip verification for now (dev mode)
    }

    @Override
    public String extractEventId(String rawBody) {
        try {
            ConfluenceWebhookPayload payload = mapper.readValue(rawBody, ConfluenceWebhookPayload.class);
            if (payload.event() != null && payload.page() != null && payload.page().id() != null) {
                return payload.event() + "-" + payload.page().id();
            }
        } catch (Exception ignored) {}
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
            boolean hasLabel = payload.page() != null &&
                    payload.page().labels() != null &&
                    payload.page().labels().stream()
                            .anyMatch(l -> config.getTriggerLabel().equals(l.name()));
            if (!hasLabel) return Optional.empty();

            String pageId = payload.page().id();
            String title = payload.page().title();
            String content = client.fetchPageContent(pageId);
            String ac = extractSection(content, "Acceptance Criteria");

            return Optional.of(new IntegrationEvent(
                    "CONFLUENCE", pageId,
                    config.getBaseUrl() + "/wiki/pages/viewpage.action?pageId=" + pageId,
                    title, content, ac, config.isSkipApproval()
            ));
        } catch (Exception e) {
            log.error("[Confluence] Failed to parse event: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Manual trigger: build an IntegrationEvent from a page ID + explicit goal.
     * Called by IntegrationController.confluenceTrigger().
     */
    public IntegrationEvent buildFromPageId(String pageId, String goal) {
        String content = client.fetchPageContent(pageId);
        String ac = extractSection(content, "Acceptance Criteria");
        return new IntegrationEvent(
                "CONFLUENCE", pageId,
                config.getBaseUrl() + "/wiki/pages/viewpage.action?pageId=" + pageId,
                goal, content, ac, config.isSkipApproval()
        );
    }

    // ── Outbound ──────────────────────────────────────────────────────────────

    @Override
    public void onRunStarted(IntegrationRun ir, PipelineRun run) {
        // Confluence doesn't need a "started" notification — child page is created on complete
        log.info("[Confluence] Run {} started for page {}", run.getRunId(), ir.getSourceId());
    }

    @Override
    public void onRunComplete(IntegrationRun ir, PipelineRun run, ReviewReport review) {
        String childTitle = (run.getUserRequest() != null
                ? run.getUserRequest().lines().findFirst().orElse("Agent run")
                : "Agent run") + " — Agent Output";
        String content = buildOutputPage(run, review);
        String spaceKey = config.getSpaceKey() != null ? config.getSpaceKey() : "ENG";
        client.createChildPage(ir.getSourceId(), spaceKey, childTitle, content);
    }

    @Override
    public void onRunFailed(IntegrationRun ir, PipelineRun run, String reason) {
        String childTitle = "Agent Run Failed — " + run.getRunId().substring(0, 8);
        String content = String.format("""
                # Agent Run Failed

                **Run ID**: %s
                **Reason**: %s

                [View Dashboard](%s/runs/%s)
                """, run.getRunId(), reason, config.getDashboardUrl(), run.getRunId());
        String spaceKey = config.getSpaceKey() != null ? config.getSpaceKey() : "ENG";
        try {
            client.createChildPage(ir.getSourceId(), spaceKey, childTitle, content);
        } catch (Exception e) {
            log.warn("[Confluence] Could not create failure page for run {}: {}", run.getRunId(), e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildOutputPage(PipelineRun run, ReviewReport review) {
        String verdict = review.getRecommendation() != null ? review.getRecommendation() : "N/A";
        String quality = review.getOverallConfidence() != null ? review.getOverallConfidence() : "N/A";

        String taskBreakdown = review.getTaskReviews() != null
                ? review.getTaskReviews().stream()
                        .map(t -> "- **" + t.getTaskId() + "**: " +
                                  (t.isAcceptanceCriteriaMet() ? "✅ PASS" : "❌ FAIL"))
                        .collect(Collectors.joining("\n"))
                : "No task reviews available";

        String suggestions = review.getPostMergeSuggestions() != null
                ? String.join("\n", review.getPostMergeSuggestions()) : "None";

        return String.format("""
                # Agent Run Output
                **Run ID**: %s  |  **Status**: %s  |  **Quality**: %s

                ## Review Summary
                %s

                ## Task Breakdown
                %s

                ## Post-merge Suggestions
                %s

                [View Dashboard](%s/runs/%s)
                """,
                run.getRunId(), run.getStatus(), quality,
                review.getSummary() != null ? review.getSummary() : "",
                taskBreakdown,
                suggestions,
                config.getDashboardUrl(), run.getRunId());
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

package com.navneet.executespec.integration.slack;

import com.navneet.executespec.integration.slack.model.SlackEventType;
import com.navneet.executespec.state.entity.PipelineRun;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Builds Slack Block Kit message payloads for pipeline events.
 * Each method returns a map with "blocks" and optionally "attachments" keys.
 */
@Slf4j
public class SlackMessageBuilder {

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final String dashboardUrl;

    public SlackMessageBuilder(String dashboardUrl) {
        this.dashboardUrl = dashboardUrl.replaceAll("/+$", "");
    }

    // ── Public builders ───────────────────────────────────────────────────────

    public Map<String, Object> build(SlackEventType eventType, PipelineRun run, String payload) {
        return switch (eventType) {
            case RUN_STARTED       -> runStarted(run);
            case RUN_COMPLETE      -> runComplete(run);
            case RUN_FAILED        -> runFailed(run, payload);
            case PLAN_APPROVAL_NEEDED -> planApprovalNeeded(run);
            case PLAN_APPROVED     -> planApproved(run);
            case PLAN_REJECTED     -> planRejected(run);
            case TASK_COMPLETED    -> taskEvent(run, payload, true);
            case TASK_FAILED       -> taskEvent(run, payload, false);
            case TASK_STARTED      -> taskStarted(run, payload);
            case REVIEW_COMPLETE   -> reviewComplete(run, payload);
            case REVIEW_STARTED    -> reviewStarted(run, payload);
            case MERGE_GATE_OPENED -> mergeGateOpened(run);
            case RUN_PARTIAL       -> runPartial(run);
            case MODEL_ESCALATED   -> modelEscalated(run, payload);
            case STUCK_RUN         -> stuckRun(run, payload);
            case VALIDATION_FAILED -> validationFailed(run, payload);
        };
    }

    // ── MINIMAL events ────────────────────────────────────────────────────────

    private Map<String, Object> runStarted(PipelineRun run) {
        return simpleMessage("\uD83D\uDE80 Run Started", run,
                fields(
                        field("*Project:*", projectName(run)),
                        field("*Run:*", runLink(run)),
                        field("*Triggered by:*", run.getCreatedByUserId() != null ? run.getCreatedByUserId() : "system"),
                        field("*Spec:*", truncate(run.getUserRequest(), 80))
                ));
    }

    private Map<String, Object> runComplete(PipelineRun run) {
        return simpleMessage("\u2705 Run Complete", run,
                fields(
                        field("*Run:*", runLink(run)),
                        field("*Project:*", projectName(run)),
                        field("*Status:*", "COMPLETE"),
                        field("*Total tokens:*", fmtTokens(run))
                ));
    }

    private Map<String, Object> runFailed(PipelineRun run, String reason) {
        String failReason = run.getFailureReason() != null ? run.getFailureReason()
                : (reason != null ? reason : "Unknown failure");
        return attachmentMessage("\u274C Run Failed", "#E74C3C", run,
                fields(
                        field("*Run:*", runLink(run)),
                        field("*Project:*", projectName(run)),
                        field("*Reason:*", truncate(failReason, 200))
                ));
    }

    // ── STANDARD events ───────────────────────────────────────────────────────

    private Map<String, Object> planApprovalNeeded(PipelineRun run) {
        return attachmentMessage("\u23F3 Plan Approval Required", "#F39C12", run,
                List.of(
                        section(mrkdwn("A plan has been generated and is waiting for approval.")),
                        section(mrkdwn("<" + runUrl(run) + "|Review and Approve in ExecuteSpec \u2192>"))
                ));
    }

    private Map<String, Object> planApproved(PipelineRun run) {
        return simpleMessage("\u2705 Plan Approved", run,
                fields(field("*Run:*", runLink(run)), field("*Project:*", projectName(run))));
    }

    private Map<String, Object> planRejected(PipelineRun run) {
        return attachmentMessage("\u274C Plan Rejected", "#E74C3C", run,
                fields(field("*Run:*", runLink(run)), field("*Project:*", projectName(run))));
    }

    private Map<String, Object> taskEvent(PipelineRun run, String payload, boolean success) {
        String taskId = payload != null ? payload : "unknown";
        String emoji = success ? "\u2705" : "\u274C";
        String status = success ? "COMPLETE" : "FAILED";
        return simpleMessage(emoji + " Task " + status, run,
                fields(
                        field("*Run:*", runLink(run)),
                        field("*Task:*", taskId),
                        field("*Status:*", status)
                ));
    }

    private Map<String, Object> reviewComplete(PipelineRun run, String payload) {
        String summary = payload != null ? payload : "";
        // payload format: "verdict|summary"
        String[] parts = summary.split("\\|", 2);
        String verdict = parts.length > 0 ? parts[0] : "";
        String details = parts.length > 1 ? parts[1] : "";
        return simpleMessage("\uD83D\uDD0D Review Complete", run,
                fields(
                        field("*Run:*", runLink(run)),
                        field("*Verdict:*", verdict),
                        field("*Summary:*", truncate(details, 150))
                ));
    }

    private Map<String, Object> mergeGateOpened(PipelineRun run) {
        return attachmentMessage("\uD83D\uDD12 Merge Approval Required", "#F39C12", run,
                List.of(
                        section(mrkdwn("Code review is complete. The run is awaiting merge approval.")),
                        section(mrkdwn("<" + runUrl(run) + "|Review and Merge in ExecuteSpec \u2192>"))
                ));
    }

    private Map<String, Object> runPartial(PipelineRun run) {
        return attachmentMessage("\u26A0\uFE0F Run Partial", "#F39C12", run,
                fields(
                        field("*Run:*", runLink(run)),
                        field("*Project:*", projectName(run)),
                        field("*Status:*", "PARTIAL — some tasks incomplete")
                ));
    }

    private Map<String, Object> stuckRun(PipelineRun run, String payload) {
        return attachmentMessage("\u26A0\uFE0F Stuck Run Recovery", "#E67E22", run,
                fields(
                        field("*Run:*", runLink(run)),
                        field("*Details:*", truncate(payload, 200))
                ));
    }

    // ── VERBOSE events ────────────────────────────────────────────────────────

    private Map<String, Object> taskStarted(PipelineRun run, String payload) {
        return simpleMessage("\u25B6\uFE0F Task Started", run,
                fields(field("*Run:*", runLink(run)), field("*Task:*", payload != null ? payload : "unknown")));
    }

    private Map<String, Object> reviewStarted(PipelineRun run, String payload) {
        return simpleMessage("\uD83D\uDD0D Review Started", run,
                fields(field("*Run:*", runLink(run)), field("*Model:*", payload != null ? payload : "")));
    }

    private Map<String, Object> modelEscalated(PipelineRun run, String payload) {
        // payload: "fromModel|toModel"
        String[] parts = (payload != null ? payload : "|").split("\\|", 2);
        return simpleMessage("\u2B06\uFE0F Model Escalated", run,
                fields(
                        field("*Run:*", runLink(run)),
                        field("*From:*", parts[0]),
                        field("*To:*", parts.length > 1 ? parts[1] : "")
                ));
    }

    private Map<String, Object> validationFailed(PipelineRun run, String payload) {
        return attachmentMessage("\u274C Validation Failed", "#E74C3C", run,
                fields(
                        field("*Run:*", runLink(run)),
                        field("*Details:*", truncate(payload, 200))
                ));
    }

    // ── Block Kit construction helpers ────────────────────────────────────────

    private Map<String, Object> simpleMessage(String title, PipelineRun run,
                                               List<Map<String, Object>> contentBlocks) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(header(title));
        blocks.addAll(contentBlocks);
        blocks.add(actionButton("View Run \u2192", runUrl(run)));
        blocks.add(context("ExecuteSpec | " + TS_FMT.format(Instant.now())));
        return Map.of("blocks", blocks);
    }

    private Map<String, Object> attachmentMessage(String title, String color, PipelineRun run,
                                                    List<Map<String, Object>> contentBlocks) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(header(title));
        blocks.addAll(contentBlocks);
        blocks.add(actionButton("View Run \u2192", runUrl(run)));
        blocks.add(context("ExecuteSpec | " + TS_FMT.format(Instant.now())));
        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("color", color);
        attachment.put("blocks", blocks);
        return Map.of("attachments", List.of(attachment));
    }

    private Map<String, Object> header(String text) {
        return Map.of("type", "header", "text",
                Map.of("type", "plain_text", "text", text, "emoji", true));
    }

    private Map<String, Object> section(Map<String, Object> textObj) {
        return Map.of("type", "section", "text", textObj);
    }

    private List<Map<String, Object>> fields(Map<String, Object>... fieldObjs) {
        return List.of(Map.of("type", "section", "fields", List.of(fieldObjs)));
    }

    private Map<String, Object> field(String label, String value) {
        return mrkdwn(label + "\n" + (value != null ? value : "—"));
    }

    private Map<String, Object> mrkdwn(String text) {
        return Map.of("type", "mrkdwn", "text", text);
    }

    private Map<String, Object> actionButton(String label, String url) {
        return Map.of("type", "actions", "elements", List.of(
                Map.of("type", "button",
                        "text", Map.of("type", "plain_text", "text", label, "emoji", true),
                        "url", url)));
    }

    private Map<String, Object> context(String text) {
        return Map.of("type", "context", "elements", List.of(mrkdwn(text)));
    }

    // ── Formatting helpers ────────────────────────────────────────────────────

    private String runUrl(PipelineRun run) {
        return dashboardUrl + "/runs/" + run.getRunId();
    }

    private String runLink(PipelineRun run) {
        return "<" + runUrl(run) + "|" + shortId(run.getRunId()) + ">";
    }

    private String projectName(PipelineRun run) {
        return run.getProjectId() != null ? run.getProjectId() : "—";
    }

    private String shortId(String runId) {
        return runId != null && runId.length() > 8 ? runId.substring(0, 8) : (runId != null ? runId : "—");
    }

    private String truncate(String s, int max) {
        if (s == null) return "—";
        return s.length() > max ? s.substring(0, max) + "\u2026" : s;
    }

    private String fmtTokens(PipelineRun run) {
        long total = (run.getTotalInputTokens() != null ? run.getTotalInputTokens() : 0)
                + (run.getTotalOutputTokens() != null ? run.getTotalOutputTokens() : 0);
        if (total > 1_000_000) return String.format("%.1fM", total / 1_000_000.0);
        if (total > 1_000) return String.format("%.1fK", total / 1_000.0);
        return String.valueOf(total);
    }
}

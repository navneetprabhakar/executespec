package com.navneet.executespec.integration.slack.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Maps {@link com.navneet.executespec.sse.RunEvent} types to Slack notification
 * events with a minimum verbosity threshold.
 */
@Getter
@RequiredArgsConstructor
public enum SlackEventType {

    // ── MINIMAL — always sent ─────────────────────────────────────────────
    RUN_STARTED("RUN_STATUS", "PLANNING", SlackVerbosity.MINIMAL),
    RUN_COMPLETE("COMPLETE", "COMPLETE", SlackVerbosity.MINIMAL),
    RUN_FAILED("COMPLETE", "FAILED", SlackVerbosity.MINIMAL),

    // ── STANDARD — stage transitions + approvals ──────────────────────────
    PLAN_APPROVAL_NEEDED("GATE_OPENED", "PLAN_APPROVAL", SlackVerbosity.STANDARD),
    PLAN_APPROVED("GATE_RESOLVED", "APPROVED", SlackVerbosity.STANDARD),
    PLAN_REJECTED("GATE_RESOLVED", "REJECTED", SlackVerbosity.STANDARD),
    TASK_COMPLETED("TASK_STATUS", "COMPLETE", SlackVerbosity.STANDARD),
    TASK_FAILED("TASK_STATUS", "FAILED", SlackVerbosity.STANDARD),
    REVIEW_COMPLETE("REVIEW_COMPLETE", null, SlackVerbosity.STANDARD),
    MERGE_GATE_OPENED("GATE_OPENED", "MERGE_APPROVAL", SlackVerbosity.STANDARD),
    RUN_PARTIAL("COMPLETE", "PARTIAL", SlackVerbosity.STANDARD),
    STUCK_RUN("RECOVERY_ATTEMPTED", null, SlackVerbosity.STANDARD),

    // ── VERBOSE — everything ──────────────────────────────────────────────
    TASK_STARTED("TASK_STATUS", "PENDING", SlackVerbosity.VERBOSE),
    REVIEW_STARTED("REVIEW_STARTED", null, SlackVerbosity.VERBOSE),
    MODEL_ESCALATED("MODEL_ESCALATED", null, SlackVerbosity.VERBOSE),
    VALIDATION_FAILED("VALIDATION_FAILED", null, SlackVerbosity.VERBOSE);

    private final String runEventType;
    private final String payloadMatch;
    private final SlackVerbosity minimumVerbosity;

    /**
     * Resolve a {@code RunEvent.type()} + {@code RunEvent.payload()} into a Slack event type.
     * Returns {@code null} if the event is not relevant for Slack notifications.
     */
    public static SlackEventType resolve(String type, String payload) {
        if (type == null) return null;
        for (SlackEventType e : values()) {
            if (e.runEventType.equals(type)) {
                if (e.payloadMatch == null) return e;
                if (payload != null && payload.startsWith(e.payloadMatch)) return e;
            }
        }
        return null;
    }
}

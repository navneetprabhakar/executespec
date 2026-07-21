package com.navneet.executespec.api.dto;

/**
 * Body for plan-approval and merge-approval gate endpoints.
 *
 * @param creditsConfirmed If non-null, must match run's creditsCharged to prevent stale approvals.
 *     If pricing changed between plan and approval, returns 409 Conflict.
 * @param executionMode "REALTIME" (default) or "BATCH" — user's execution choice at approval. Null
 *     treated as REALTIME for backward compatibility.
 * @param planFingerprint RR-1459 — identity of the plan the approver actually reviewed (SHA-256 of
 *     the run's persisted plan JSON, hex). {@code creditsConfirmed} is NOT a plan identity: two
 *     different plans that happen to cost the same number of credits pass that check identically,
 *     which is precisely what lets a client still displaying run A's plan submit an approval that
 *     lands on run B. When supplied, this must match the plan currently persisted on the run or the
 *     approval is rejected with 409. Optional only so existing clients keep working; any client
 *     that renders a plan should echo the fingerprint it rendered.
 */
public record ApprovalRequest(
    boolean approved,
    String comment,
    Integer creditsConfirmed,
    String executionMode,
    String planFingerprint) {}

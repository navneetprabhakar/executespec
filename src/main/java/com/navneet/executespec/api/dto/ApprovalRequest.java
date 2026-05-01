package com.navneet.executespec.api.dto;

/**
 * Body for plan-approval and merge-approval gate endpoints.
 *
 * @param creditsConfirmed If non-null, must match run's creditsCharged to prevent stale approvals.
 *     If pricing changed between plan and approval, returns 409 Conflict.
 * @param executionMode "REALTIME" (default) or "BATCH" — user's execution choice at approval. Null
 *     treated as REALTIME for backward compatibility.
 */
public record ApprovalRequest(
    boolean approved, String comment, Integer creditsConfirmed, String executionMode) {}

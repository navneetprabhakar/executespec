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
 * @param breakingChangeAck RR-819 — the literal text {@code BREAKING}, typed by the approver, when
 *     this plan intends to modify a registered contract that other repositories declare themselves
 *     consumers of. Required only when the server composed a gate report with {@code
 *     requiresTypedAck}; supplying it when no gate is pending is ignored. Server-verified: exact
 *     text, bound to {@code breakingChangeReportDigest}, recorded with the acting user.
 * @param breakingChangeReportDigest RR-819 — the {@code digest} of the gate report the approver was
 *     actually shown ({@code GET /api/runs/{id}/plan-contract-gate}). Binds the acknowledgement to
 *     that exact evidence so consent collected against one set of contracts cannot be replayed
 *     against a different one. Mismatch is 409.
 */
public record ApprovalRequest(
    boolean approved,
    String comment,
    Integer creditsConfirmed,
    String executionMode,
    String planFingerprint,
    String breakingChangeAck,
    String breakingChangeReportDigest) {}

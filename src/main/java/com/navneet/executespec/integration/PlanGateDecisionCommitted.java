package com.navneet.executespec.integration;

/**
 * Published inside the atomic V348 plan-gate decision transaction immediately after a FIRST-TIME
 * customer APPROVE or REJECT receipt is persisted. Exact replays return the stored receipt before
 * the publish site is reached, so a replayed decision never re-publishes this event and cannot
 * produce a duplicate audit/activity entry.
 *
 * <p>Consumers MUST listen with {@code @TransactionalEventListener(phase = AFTER_COMMIT)} (see
 * {@code activity/PlanGateDecisionActivityListener}): the V348 decision transaction is sacred, so
 * side effects run only after the durable receipt actually committed and can never join, delay, or
 * roll back the decision itself. A rolled-back decision publishes nothing observable.
 *
 * <p>Actor fields carry the REAL human decider (APPROVE/REJECT always require a human actor —
 * {@code PlanGateDecisionEvidence.actorEvidence} rejects a system principal for those kinds), so
 * downstream audit surfaces stop rendering the approval as {@code SYSTEM}.
 *
 * @param runId the decided run
 * @param projectId the run's host project, or null for standalone runs
 * @param decision APPROVE or REJECT (CANCEL is never published as a customer decision event)
 * @param actorUserId the deciding human's user id (UUID string)
 * @param actorEmail the deciding human's email, for display in audit surfaces
 * @param actorDisplayName optional "First Last" display name; null when the profile has no name
 * @param runTitle optional run title for the feed summary
 */
public record PlanGateDecisionCommitted(
    String runId,
    String projectId,
    PlanGateDecisionService.DecisionKind decision,
    String actorUserId,
    String actorEmail,
    String actorDisplayName,
    String runTitle) {}

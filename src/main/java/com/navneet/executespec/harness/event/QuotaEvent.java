package com.navneet.executespec.harness.event;

/**
 * Quota/limit lifecycle events. Decoupled from {@link PipelineEvent} because
 * they are user-scoped, not run-scoped (a quota warning has no runId).
 *
 * <p>Listeners use {@code @EventListener} (optionally with {@code @Async}) to
 * react without being wired directly into {@link com.navneet.executespec.auth.service.TierEnforcementService}.
 */
public sealed interface QuotaEvent {

    /**
     * Fired the first time a quota crosses 80% utilisation within a billing cycle.
     * Used by the notification pipeline to nudge the user before they hit 429.
     *
     * @param userId      the user who owns the quota (always non-null)
     * @param orgId       the org that holds the quota when org-scoped, otherwise null
     * @param metric      {@code RUNS} today; reserved for future metrics
     * @param current     usage AFTER the increment that crossed the threshold
     * @param limit       effective monthly limit (may be -1 for unlimited — not emitted in that case)
     * @param percentUsed integer percent (80..100+), rounded down
     */
    record QuotaWarning(
            String userId,
            String orgId,
            String metric,
            int current,
            int limit,
            int percentUsed
    ) implements QuotaEvent {}
}

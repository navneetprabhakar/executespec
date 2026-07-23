package com.navneet.executespec.billing.exception;

/**
 * RR-055 — a signed, well-formed webhook whose local dependency (the subscription / checkout row it
 * refers to) is not visible yet.
 *
 * <p>This is deliberately distinct from an IGNORED event (an event type or entity that is simply
 * not ours to act on) and from a processing ERROR (a bug). An unresolved dependency is expected to
 * resolve later — the local row is created by a separate, possibly slower, path — so the webhook
 * MUST NOT be marked processed. Marking it processed is the RR-055 defect: a legitimate event that
 * arrived a moment early is then permanently discarded and cannot self-heal.
 *
 * <p>The recorder leaves the {@code billing_webhook_event} row {@code processed=false} with its raw
 * payload retained, so the event stays truthfully un-acknowledged and available for reprocessing. A
 * bounded-retry / dead-letter drain that automatically re-runs these is intentionally out of scope
 * for the current fix (it needs its own scheduler) and is tracked separately.
 */
public class UnresolvedWebhookDependencyException extends RuntimeException {
  public UnresolvedWebhookDependencyException(String message) {
    super(message);
  }
}

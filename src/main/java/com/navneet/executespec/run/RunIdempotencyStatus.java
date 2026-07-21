package com.navneet.executespec.run;

/** Outcome of a durable run-submission idempotency claim (V303). */
public enum RunIdempotencyStatus {

  /** This caller owns the claim and must proceed with quota reservation and run creation. */
  CLAIMED,

  /**
   * Another caller already created the run for this key; {@code runId} carries the winner's run.
   */
  DUPLICATE,

  /** A live claim is still in flight and did not settle within the bounded wait. Retry later. */
  IN_PROGRESS,

  /** The key was reused with a different request body. Never resolve this to the first run. */
  CONFLICT,

  /** The key already failed deterministically; retrying the same body would fail identically. */
  FAILED_FINAL,

  /** No idempotency key was supplied, so no dedupe applies and the submit proceeds unguarded. */
  DISABLED
}

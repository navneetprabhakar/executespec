package com.navneet.executespec.run;

/**
 * The result of a durable claim attempt on {@code (actorId, idempotencyKey)}.
 *
 * <p>Only a {@link RunIdempotencyStatus#CLAIMED} claim carries a usable {@code owner} / {@code
 * fencingToken} pair; those two values are what let the holder publish a terminal state and what
 * stop a superseded holder from publishing over its replacement.
 */
public record RunIdempotencyClaim(
    RunIdempotencyStatus status,
    String actorId,
    String idempotencyKey,
    String owner,
    long fencingToken,
    String runId,
    String detail) {

  /** True when this caller owns the claim and must perform the submission side effects. */
  public boolean owned() {
    return status == RunIdempotencyStatus.CLAIMED;
  }

  public static RunIdempotencyClaim disabled() {
    return new RunIdempotencyClaim(RunIdempotencyStatus.DISABLED, null, null, null, 0L, null, null);
  }

  public static RunIdempotencyClaim claimed(String actorId, String key, String owner, long token) {
    return new RunIdempotencyClaim(
        RunIdempotencyStatus.CLAIMED, actorId, key, owner, token, null, null);
  }

  public static RunIdempotencyClaim resolved(
      RunIdempotencyStatus status, String actorId, String key, String runId, String detail) {
    return new RunIdempotencyClaim(status, actorId, key, null, 0L, runId, detail);
  }
}

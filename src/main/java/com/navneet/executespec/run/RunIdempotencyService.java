package com.navneet.executespec.run;

import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Durable, distributed idempotency for run submission (V303).
 *
 * <p><b>What this replaces.</b> The previous implementation performed a Redis {@code lookup} and a
 * later Redis {@code record} as two separate operations, and documented that "two genuinely
 * simultaneous identical submits could still both miss the lookup and create two runs". That is
 * exactly the defect: two parallel {@code POST /api/runs} calls with one {@code Idempotency-Key}
 * could create two runs and reserve quota twice. Redis was also fail-open, so the guarantee
 * evaporated whenever the cache was unavailable.
 *
 * <p><b>The contract now.</b> Callers must {@link #claim} BEFORE reserving quota or creating a run,
 * then publish exactly one terminal state via {@link #complete}, {@link #releaseForRetry} or {@link
 * #failFinal}. The claim is a single atomic SQL statement guarded by a unique constraint, so
 * exactly one caller can own a key at a time across any number of JVMs sharing one PostgreSQL.
 *
 * <p><b>What a loser does.</b> A caller that loses the race waits — on the database, never on a
 * JVM-local lock — for the winner to settle, and then returns the winner's run. If the winner has
 * not settled within {@code waitMillis} the loser is told to retry rather than being allowed to
 * create a second run. If the winner died, its lease elapses and the key becomes claimable again,
 * so a retry can never deadlock forever on a dead claimant.
 */
@Service
@Slf4j
public class RunIdempotencyService {

  private static final long POLL_INTERVAL_MILLIS = 100L;

  private final RunSubmissionClaimStore store;
  private final long leaseMillis;
  private final long waitMillis;
  private final long ttlMillis;
  private final String owner = "es-" + UUID.randomUUID();

  public RunIdempotencyService(
      RunSubmissionClaimStore store,
      @Value("${run.idempotency.lease-millis:120000}") long leaseMillis,
      @Value("${run.idempotency.wait-millis:15000}") long waitMillis,
      @Value("${run.idempotency.ttl-millis:86400000}") long ttlMillis) {
    this.store = store;
    this.leaseMillis = leaseMillis;
    this.waitMillis = waitMillis;
    this.ttlMillis = ttlMillis;
  }

  /**
   * Atomically claim {@code (actorId, idempotencyKey)} for this submission.
   *
   * <p>Must be called before the quota/usage increment. A {@link RunIdempotencyStatus#CLAIMED}
   * result obliges the caller to publish a terminal state.
   *
   * @param requestFingerprint canonical hash of the request body; a different body for the same key
   *     yields {@link RunIdempotencyStatus#CONFLICT} and must never resolve to the first run
   */
  public RunIdempotencyClaim claim(
      String actorId, String idempotencyKey, String requestFingerprint) {
    if (isBlank(actorId) || isBlank(idempotencyKey)) return RunIdempotencyClaim.disabled();
    String hash = requireFingerprint(requestFingerprint);
    long deadline = System.currentTimeMillis() + waitMillis;

    while (true) {
      Optional<Long> token =
          store.tryClaim(actorId, idempotencyKey, hash, owner, leaseMillis, ttlMillis);
      if (token.isPresent()) {
        return RunIdempotencyClaim.claimed(actorId, idempotencyKey, owner, token.get());
      }

      // The claim was not takeable. Read the row once to explain why — this read is diagnostic and
      // never itself grants ownership, so it cannot reintroduce the lookup-then-record race.
      var settled = resolveLostClaim(actorId, idempotencyKey, hash);
      if (settled.isPresent()) return settled.get();

      if (System.currentTimeMillis() >= deadline) {
        return RunIdempotencyClaim.resolved(
            RunIdempotencyStatus.IN_PROGRESS,
            actorId,
            idempotencyKey,
            null,
            "another submission with this Idempotency-Key is still in flight");
      }
      sleep();
    }
  }

  /** Resolve a lost claim, or empty when the winner is still in flight and should be waited on. */
  private Optional<RunIdempotencyClaim> resolveLostClaim(String actorId, String key, String hash) {
    var record = store.find(actorId, key);
    if (record.isEmpty()) return Optional.empty(); // raced with a delete/retention sweep; loop.
    var row = record.get();

    if (!hash.equals(row.requestHash())) {
      return Optional.of(
          RunIdempotencyClaim.resolved(
              RunIdempotencyStatus.CONFLICT,
              actorId,
              key,
              null,
              "this Idempotency-Key was already used with a different request body"));
    }
    return switch (row.state()) {
      case RunSubmissionClaimStore.STATE_COMPLETED ->
          Optional.of(
              RunIdempotencyClaim.resolved(
                  RunIdempotencyStatus.DUPLICATE, actorId, key, row.runId(), null));
      case RunSubmissionClaimStore.STATE_FAILED_FINAL ->
          Optional.of(
              RunIdempotencyClaim.resolved(
                  RunIdempotencyStatus.FAILED_FINAL, actorId, key, null, row.failureReason()));
      default -> Optional.empty(); // CLAIMED with a live lease, or a just-released retryable row.
    };
  }

  /** Publish the run created under this claim. */
  public void complete(RunIdempotencyClaim claim, String runId) {
    if (!claim.owned() || isBlank(runId)) return;
    boolean published =
        store.complete(
            claim.actorId(), claim.idempotencyKey(), claim.owner(), claim.fencingToken(), runId);
    if (!published) {
      // Fenced out: this claim's lease elapsed and another JVM legitimately took over. The run we
      // created is now an orphan. Surfaced loudly rather than silently ignored.
      log.warn(
          "Run idempotency claim for key {} was superseded before completion; run {} is orphaned."
              + " Increase run.idempotency.lease-millis if this recurs.",
          claim.idempotencyKey(),
          runId);
    }
  }

  /** Release the claim so a later retry of the same body may proceed (transient failure). */
  public void releaseForRetry(RunIdempotencyClaim claim, String reason) {
    publishFailure(claim, RunSubmissionClaimStore.STATE_FAILED_RETRYABLE, reason);
  }

  /** Mark the claim permanently failed; the same body must not be retried into the same wall. */
  public void failFinal(RunIdempotencyClaim claim, String reason) {
    publishFailure(claim, RunSubmissionClaimStore.STATE_FAILED_FINAL, reason);
  }

  private void publishFailure(RunIdempotencyClaim claim, String state, String reason) {
    if (!claim.owned()) return;
    boolean published =
        store.fail(
            claim.actorId(),
            claim.idempotencyKey(),
            claim.owner(),
            claim.fencingToken(),
            state,
            truncate(reason));
    if (!published) {
      log.warn(
          "Run idempotency claim for key {} was superseded before its {} state could be published",
          claim.idempotencyKey(),
          state);
    }
  }

  /** Retention sweep for settled records past their TTL. */
  public int purgeExpired(int limit) {
    return store.deleteExpired(limit);
  }

  private void sleep() {
    try {
      Thread.sleep(POLL_INTERVAL_MILLIS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while awaiting an in-flight run submission", e);
    }
  }

  private static String requireFingerprint(String fingerprint) {
    if (isBlank(fingerprint)) {
      throw new IllegalArgumentException(
          "a request fingerprint is required to claim an idempotency key");
    }
    return fingerprint;
  }

  private static String truncate(String reason) {
    if (reason == null) return null;
    return reason.length() <= 500 ? reason : reason.substring(0, 500);
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}

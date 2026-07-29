package com.navneet.executespec.project.pr;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * Database-authoritative admission for one immutable GitHub {@code check_run} failure observation.
 *
 * <p>The finding key is the primary key. A failed submission can be reclaimed with a new fencing
 * token; an in-flight or dispatched claim can never be acquired by a second delivery.
 */
@Component
@RequiredArgsConstructor
class CiFailureDeliveryClaimStore {

  enum Outcome {
    ACQUIRED,
    IN_PROGRESS,
    DISPATCHED,
    ATTEMPT_LIMIT,
    PR_NOT_OPEN
  }

  record Claim(String findingKey, long token, Outcome outcome, String runId) {
    boolean acquired() {
      return outcome == Outcome.ACQUIRED;
    }
  }

  private final JdbcTemplate jdbc;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  Claim claim(
      String findingKey,
      UUID pullRequestRecordId,
      String checkRunId,
      String headSha,
      int maxAttempts) {
    PrState pr =
        jdbc.queryForObject(
            "SELECT ci_auto_fix_attempts,status FROM pull_request_record WHERE id=? FOR UPDATE",
            (rs, ignored) -> new PrState(rs.getInt("ci_auto_fix_attempts"), rs.getString("status")),
            pullRequestRecordId);
    if (pr == null || !"OPEN".equals(pr.status())) {
      return new Claim(findingKey, 0, Outcome.PR_NOT_OPEN, null);
    }
    int attempts = pr.attempts();
    List<Claim> existing =
        jdbc.query(
            """
            SELECT claim_token, status, run_id,
                   lease_expires_at <= clock_timestamp() AS lease_expired
            FROM ci_failure_delivery_claim WHERE finding_key=?
            """,
            (rs, ignored) ->
                new Claim(
                    findingKey,
                    rs.getLong("claim_token"),
                    "DISPATCHED".equals(rs.getString("status"))
                        ? Outcome.DISPATCHED
                        : Outcome.IN_PROGRESS,
                    rs.getString("run_id")),
            findingKey);
    if (!existing.isEmpty()) {
      Claim current = existing.getFirst();
      if (current.outcome() == Outcome.DISPATCHED) return current;

      String status =
          jdbc.queryForObject(
              "SELECT status FROM ci_failure_delivery_claim WHERE finding_key=?",
              String.class,
              findingKey);
      Boolean expired =
          jdbc.queryForObject(
              """
              SELECT lease_expires_at <= clock_timestamp()
              FROM ci_failure_delivery_claim WHERE finding_key=?
              """,
              Boolean.class,
              findingKey);
      if ("CLAIMED".equals(status) && !Boolean.TRUE.equals(expired)) return current;

      // A previous process may have committed its run after claiming but before binding the claim.
      // The run's initial INSERT carries triggered_by_finding, so recovery can close that crash
      // window without creating a second PATCH run.
      List<String> recoveredRuns = findRunByFinding(findingKey);
      if (!recoveredRuns.isEmpty()) {
        String recoveredRun = recoveredRuns.getFirst();
        if ("FAILED".equals(status)) {
          // fail() released this observation's reservation. The already-created run still counts
          // against the cap, so recovery restores the reservation before publishing DISPATCHED.
          jdbc.update(
              "UPDATE pull_request_record SET ci_auto_fix_attempts=ci_auto_fix_attempts+1,"
                  + " updated_at=clock_timestamp() WHERE id=?",
              pullRequestRecordId);
        }
        jdbc.update(
            """
            UPDATE ci_failure_delivery_claim
            SET status='DISPATCHED', run_id=?, lease_expires_at=NULL, error_code=NULL,
                completed_at=clock_timestamp(), updated_at=clock_timestamp()
            WHERE finding_key=?
            """,
            recoveredRun,
            findingKey);
        return new Claim(findingKey, current.token(), Outcome.DISPATCHED, recoveredRun);
      }

      if ("FAILED".equals(status)) {
        if (attempts >= maxAttempts) {
          return new Claim(findingKey, current.token(), Outcome.ATTEMPT_LIMIT, null);
        }
        jdbc.update(
            "UPDATE pull_request_record SET ci_auto_fix_attempts=ci_auto_fix_attempts+1,"
                + " updated_at=clock_timestamp() WHERE id=?",
            pullRequestRecordId);
      }
      List<Long> reclaimed =
          jdbc.query(
              """
              UPDATE ci_failure_delivery_claim
              SET status='CLAIMED', claim_token=claim_token+1, run_id=NULL, error_code=NULL,
                  lease_expires_at=clock_timestamp()+INTERVAL '10 minutes',
                  completed_at=NULL, updated_at=clock_timestamp()
              WHERE finding_key=? AND
                (status='FAILED' OR (status='CLAIMED' AND lease_expires_at<=clock_timestamp()))
              RETURNING claim_token
              """,
              (rs, ignored) -> rs.getLong(1),
              findingKey);
      if (!reclaimed.isEmpty()) {
        return new Claim(findingKey, reclaimed.getFirst(), Outcome.ACQUIRED, null);
      }
      return current;
    }

    // Upgrade reconciliation: V359 starts with no claim rows, while V183 runs already carry the
    // immutable finding marker and have already consumed their historical PR attempt. Materialize
    // DISPATCHED without reserving that attempt a second time.
    List<String> historicalRuns = findRunByFinding(findingKey);
    if (!historicalRuns.isEmpty()) {
      String historicalRun = historicalRuns.getFirst();
      jdbc.update(
          """
          INSERT INTO ci_failure_delivery_claim
            (finding_key, pull_request_record_id, check_run_id, head_sha, status, run_id,
             completed_at)
          VALUES (?, ?, ?, ?, 'DISPATCHED', ?, clock_timestamp())
          """,
          findingKey,
          pullRequestRecordId,
          bounded(checkRunId, 128, "unknown"),
          bounded(headSha, 64, "unknown"),
          historicalRun);
      return new Claim(findingKey, 1, Outcome.DISPATCHED, historicalRun);
    }

    if (attempts >= maxAttempts) {
      return new Claim(findingKey, 0, Outcome.ATTEMPT_LIMIT, null);
    }
    List<Long> inserted =
        jdbc.query(
            """
            INSERT INTO ci_failure_delivery_claim
              (finding_key, pull_request_record_id, check_run_id, head_sha, status,
               lease_expires_at)
            VALUES (?, ?, ?, ?, 'CLAIMED', clock_timestamp()+INTERVAL '10 minutes')
            ON CONFLICT DO NOTHING
            RETURNING claim_token
            """,
            (rs, ignored) -> rs.getLong(1),
            findingKey,
            pullRequestRecordId,
            bounded(checkRunId, 128, "unknown"),
            bounded(headSha, 64, "unknown"));
    if (!inserted.isEmpty()) {
      jdbc.update(
          "UPDATE pull_request_record SET ci_auto_fix_attempts=ci_auto_fix_attempts+1,"
              + " updated_at=clock_timestamp() WHERE id=?",
          pullRequestRecordId);
      return new Claim(findingKey, inserted.getFirst(), Outcome.ACQUIRED, null);
    }
    throw new IllegalStateException("CI delivery claim conflict after locked admission");
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  boolean complete(Claim claim, String runId) {
    return jdbc.update(
            """
            UPDATE ci_failure_delivery_claim
            SET status='DISPATCHED', run_id=?, lease_expires_at=NULL, error_code=NULL,
                completed_at=clock_timestamp(), updated_at=clock_timestamp()
            WHERE finding_key=? AND status='CLAIMED' AND claim_token=?
            """,
            runId,
            claim.findingKey(),
            claim.token())
        == 1;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  boolean fail(Claim claim, String errorCode) {
    List<UUID> prIds =
        jdbc.query(
            """
            SELECT pull_request_record_id FROM ci_failure_delivery_claim
            WHERE finding_key=? AND status='CLAIMED' AND claim_token=?
            """,
            (rs, ignored) -> rs.getObject(1, UUID.class),
            claim.findingKey(),
            claim.token());
    if (prIds.isEmpty()) return false;
    UUID prId = prIds.getFirst();
    jdbc.queryForObject(
        "SELECT id FROM pull_request_record WHERE id=? FOR UPDATE", UUID.class, prId);
    int changed =
        jdbc.update(
            """
            UPDATE ci_failure_delivery_claim
            SET status='FAILED', run_id=NULL, lease_expires_at=NULL, error_code=?,
                completed_at=clock_timestamp(), updated_at=clock_timestamp()
            WHERE finding_key=? AND status='CLAIMED' AND claim_token=?
            """,
            bounded(errorCode, 96, "SUBMISSION_FAILED"),
            claim.findingKey(),
            claim.token());
    if (changed == 1) {
      jdbc.update(
          """
          UPDATE pull_request_record
          SET ci_auto_fix_attempts=GREATEST(0, ci_auto_fix_attempts-1),
              updated_at=clock_timestamp()
          WHERE id=?
          """,
          prId);
    }
    return changed == 1;
  }

  private static String bounded(String value, int max, String fallback) {
    String normalized = value == null || value.isBlank() ? fallback : value;
    return normalized.length() <= max ? normalized : normalized.substring(0, max);
  }

  private List<String> findRunByFinding(String findingKey) {
    return jdbc.query(
        """
        SELECT run_id FROM pipeline_run
        WHERE triggered_by_finding=?
        ORDER BY started_at DESC NULLS LAST, run_id DESC LIMIT 1
        """,
        (rs, ignored) -> rs.getString(1),
        findingKey);
  }

  private record PrState(int attempts, String status) {}
}

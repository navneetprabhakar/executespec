package com.navneet.executespec.sandbox;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** Durable ADMITTED -> DISPATCHED -> terminal journal for physical verification commands. */
@Component
@RequiredArgsConstructor
public class SandboxCommandJournalStore {

  private static final String SHA_256 = "[0-9a-f]{64}";

  private final JdbcTemplate jdbc;

  /** Writes command intent while the exact verification sandbox fence is still live. */
  @Transactional
  public Admission admit(
      AdmissionRequest request, RunSandbox sandbox, RunSandbox.Lease lease, long runtimeEpoch) {
    require(request, sandbox, lease, runtimeEpoch);
    UUID id = UUID.randomUUID();
    int inserted =
        jdbc.update(
            """
            INSERT INTO sandbox_command_execution(
              id, run_id, attempt_id, sandbox_id, state, candidate_generation, sandbox_purpose,
              runtime_owner_digest, lease_token, runtime_epoch, image_digest, config_digest,
              mount_digest, command_class, argv_digest, timeout_ms, idempotency_key,
              disposable_snapshot_before_sha256)
            SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?
              FROM run_sandbox s
             WHERE s.id=? AND s.run_id=? AND s.purpose='VERIFICATION' AND s.state='READY'
               AND s.runtime_owner=? AND s.lease_token=? AND s.runtime_epoch=?
               AND s.image_digest=? AND s.config_digest=?
               AND s.lease_expires_at>NOW()
            ON CONFLICT (idempotency_key) DO NOTHING
            """,
            id,
            request.runId(),
            request.attemptId(),
            sandbox.id(),
            CommandState.ADMITTED.name(),
            request.candidateGeneration(),
            SandboxPurpose.VERIFICATION.name(),
            sha256(lease.owner()),
            lease.fencingToken(),
            runtimeEpoch,
            sandbox.imageDigest(),
            sandbox.configDigest(),
            request.mountDigest(),
            request.commandClass(),
            request.argvDigest(),
            request.timeout().toMillis(),
            request.idempotencyKey(),
            request.disposableSnapshotBeforeSha256(),
            sandbox.id(),
            request.runId(),
            lease.owner(),
            lease.fencingToken(),
            runtimeEpoch,
            sandbox.imageDigest(),
            sandbox.configDigest());
    if (inserted == 1) {
      return new Admission(id, sandbox.id(), lease.fencingToken(), runtimeEpoch);
    }
    Admission existing = findAdmission(request.idempotencyKey());
    if (existing == null) {
      throw new IllegalStateException("SANDBOX_COMMAND_ADMISSION_FENCE_REJECTED");
    }
    assertIdempotent(existing.id(), request, sandbox, lease, runtimeEpoch);
    return existing;
  }

  /** Records the last durable point before the external runtime call. */
  @Transactional
  public void markDispatched(Admission admission, RunSandbox.Lease lease) {
    int updated =
        jdbc.update(
            """
            UPDATE sandbox_command_execution c
               SET state='DISPATCHED', dispatched_at=NOW()
             WHERE c.id=? AND c.state='ADMITTED' AND c.sandbox_id=?
               AND c.lease_token=? AND c.runtime_epoch=?
               AND EXISTS (
                 SELECT 1 FROM run_sandbox s
                  WHERE s.id=c.sandbox_id AND s.purpose='VERIFICATION' AND s.state='READY'
                    AND s.runtime_owner=? AND s.lease_token=?
                    AND s.runtime_epoch=c.runtime_epoch AND s.lease_expires_at>NOW()
               )
            """,
            admission.id(),
            admission.sandboxId(),
            admission.leaseToken(),
            admission.runtimeEpoch(),
            lease.owner(),
            lease.fencingToken());
    if (updated != 1) throw new IllegalStateException("SANDBOX_COMMAND_DISPATCH_FENCE_REJECTED");
  }

  /**
   * Atomically settles an owner-reported result and inserts its immutable terminal receipt.
   *
   * <p>The physical command can outlive the JVM-side docker client. A result is therefore not
   * authoritative merely because it carries the admission id: the exact sandbox lease and runtime
   * epoch must still be live when a definitive result is published. Recovery owns the separate
   * stale-command path below and can only publish conservative UNAVAILABLE/INDETERMINATE outcomes.
   */
  @Transactional
  public UUID settle(
      Admission admission,
      RunSandbox.Lease lease,
      TerminalOutcome outcome,
      SandboxExecResult result,
      String disposableSnapshotAfterSha256,
      String detail) {
    Objects.requireNonNull(admission, "admission");
    Objects.requireNonNull(lease, "lease");
    Objects.requireNonNull(outcome, "outcome");
    if (!admission.sandboxId().equals(lease.sandboxId())
        || admission.leaseToken() != lease.fencingToken()) {
      throw new IllegalArgumentException("SANDBOX_COMMAND_SETTLEMENT_AUTHORITY_MISMATCH");
    }
    if (outcome != TerminalOutcome.INDETERMINATE) {
      requireHash(disposableSnapshotAfterSha256, "SANDBOX_COMMAND_AFTER_SNAPSHOT_DIGEST_INVALID");
      Objects.requireNonNull(result, "result");
    }
    Integer exitCode =
        outcome == TerminalOutcome.SUCCEEDED || outcome == TerminalOutcome.FAILED
            ? result.exitCode()
            : null;
    String outputDigest = result == null ? null : outputDigest(result);
    Boolean truncated = result == null ? null : result.outputTruncated();
    Long durationMs = result == null ? null : Math.max(0L, result.duration().toMillis());
    String detailDigest = detail == null || detail.isBlank() ? null : sha256(detail);
    int updated =
        jdbc.update(
            """
            UPDATE sandbox_command_execution SET
              state=?, disposable_snapshot_after_sha256=?, exit_code=?, output_digest=?,
              output_truncated=?, duration_ms=?, detail_digest=?, settled_at=NOW()
            WHERE id=? AND (
              state='DISPATCHED'
              OR (state='ADMITTED' AND ? IN ('REFUSED','UNAVAILABLE'))
            )
              AND EXISTS (
                SELECT 1 FROM run_sandbox s
                 WHERE s.id=sandbox_command_execution.sandbox_id
                   AND s.purpose='VERIFICATION' AND s.state='READY'
                   AND s.runtime_owner=? AND s.lease_token=?
                   AND s.runtime_epoch=sandbox_command_execution.runtime_epoch
                   AND s.lease_expires_at>NOW()
              )
            """,
            outcome.name(),
            disposableSnapshotAfterSha256,
            exitCode,
            outputDigest,
            truncated,
            durationMs,
            detailDigest,
            admission.id(),
            outcome.name(),
            lease.owner(),
            lease.fencingToken());
    if (updated != 1) {
      throw new IllegalStateException("SANDBOX_COMMAND_SETTLEMENT_FENCE_REJECTED");
    }
    insertReceipt(admission.id());
    return admission.id();
  }

  /**
   * Settles commands whose physical owner can no longer report an outcome.
   *
   * <p>An abandoned ADMITTED row is provably pre-dispatch and becomes UNAVAILABLE. An abandoned
   * DISPATCHED row has an unknowable physical outcome and becomes INDETERMINATE. Neither path can
   * produce success; a later verifier must use a new disposable sandbox and command id.
   */
  @Transactional
  public RecoveryResult recoverStaleCommands(int limit) {
    String admissionDetail = "STALE_ADMISSION_NOT_DISPATCHED";
    SandboxExecResult admissionResult = SandboxExecResult.unavailable(admissionDetail);
    List<RecoveredCommand> commands =
        jdbc.query(
            """
            WITH candidates AS (
              SELECT c.id, c.state
                FROM sandbox_command_execution c
                JOIN run_sandbox s ON s.id=c.sandbox_id
               WHERE c.state IN ('ADMITTED','DISPATCHED')
                 AND (s.lease_expires_at IS NULL OR s.lease_expires_at<NOW()
                      OR s.state IN ('TERMINATED','CANCELLED','EXPIRED','FAILED'))
               ORDER BY COALESCE(c.dispatched_at,c.admitted_at), c.id
               FOR UPDATE OF c SKIP LOCKED
               LIMIT ?
            )
            UPDATE sandbox_command_execution c SET
              state=CASE WHEN x.state='ADMITTED'
                         THEN 'UNAVAILABLE' ELSE 'INDETERMINATE' END,
              disposable_snapshot_after_sha256=
                CASE WHEN x.state='ADMITTED'
                     THEN c.disposable_snapshot_before_sha256 ELSE NULL END,
              output_digest=CASE WHEN x.state='ADMITTED' THEN ? ELSE NULL END,
              output_truncated=CASE WHEN x.state='ADMITTED' THEN FALSE ELSE NULL END,
              duration_ms=CASE WHEN x.state='ADMITTED' THEN 0 ELSE NULL END,
              detail_digest=CASE WHEN x.state='ADMITTED' THEN ? ELSE ? END,
              settled_at=NOW()
              FROM candidates x
             WHERE c.id=x.id AND c.state=x.state
            RETURNING c.id,c.state
            """,
            (rs, ignored) ->
                new RecoveredCommand(
                    UUID.fromString(rs.getString("id")),
                    CommandState.valueOf(rs.getString("state"))),
            Math.max(1, Math.min(limit, 500)),
            outputDigest(admissionResult),
            sha256(admissionDetail),
            sha256("STALE_DISPATCH_OUTCOME_UNKNOWN"));
    int unavailable = 0;
    int indeterminate = 0;
    for (RecoveredCommand command : commands) {
      insertReceipt(command.id());
      if (command.state() == CommandState.UNAVAILABLE) unavailable++;
      if (command.state() == CommandState.INDETERMINATE) indeterminate++;
    }
    return new RecoveryResult(unavailable, indeterminate);
  }

  /** Database proof used before a higher-level workspace receipt may claim PASSED. */
  @Transactional(readOnly = true)
  public void requireSuccessfulReceipts(
      UUID attemptId, String candidateGeneration, List<UUID> receiptIds) {
    if (attemptId == null || receiptIds == null || receiptIds.isEmpty()) {
      throw new IllegalArgumentException("WORKSPACE_VERIFICATION_COMMAND_RECEIPTS_REQUIRED");
    }
    if (new HashSet<>(receiptIds).size() != receiptIds.size()) {
      throw new IllegalArgumentException("WORKSPACE_VERIFICATION_COMMAND_RECEIPTS_DUPLICATE");
    }
    String placeholders = String.join(",", java.util.Collections.nCopies(receiptIds.size(), "?"));
    List<Object> args = new ArrayList<>();
    args.add(attemptId);
    args.add(candidateGeneration);
    args.addAll(receiptIds);
    Integer count =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)
              FROM sandbox_command_receipt r
              JOIN sandbox_command_execution c ON c.id=r.command_id
             WHERE c.attempt_id=? AND c.candidate_generation=?
               AND c.sandbox_purpose='VERIFICATION'
               AND c.state='SUCCEEDED' AND r.outcome='SUCCEEDED'
               AND r.id IN (%s)
            """
                .formatted(placeholders),
            Integer.class,
            args.toArray());
    if (count == null || count != receiptIds.size()) {
      throw new IllegalStateException("WORKSPACE_VERIFICATION_COMMAND_RECEIPTS_NOT_SUCCESSFUL");
    }
  }

  private void insertReceipt(UUID commandId) {
    int inserted =
        jdbc.update(
            """
INSERT INTO sandbox_command_receipt(
  id,command_id,receipt_version,run_id,attempt_id,sandbox_id,candidate_generation,
  sandbox_purpose,runtime_owner_digest,lease_token,runtime_epoch,image_digest,
  config_digest,mount_digest,command_class,argv_digest,timeout_ms,outcome,exit_code,
  output_digest,output_truncated,duration_ms,detail_digest,
  disposable_snapshot_before_sha256,disposable_snapshot_after_sha256,
  admitted_at,dispatched_at,settled_at)
SELECT id,id,1,run_id,attempt_id,sandbox_id,candidate_generation,sandbox_purpose,
  runtime_owner_digest,lease_token,runtime_epoch,image_digest,config_digest,mount_digest,
  command_class,argv_digest,timeout_ms,state,exit_code,output_digest,output_truncated,
  duration_ms,detail_digest,disposable_snapshot_before_sha256,
  disposable_snapshot_after_sha256,admitted_at,dispatched_at,settled_at
  FROM sandbox_command_execution
 WHERE id=? AND state IN ('SUCCEEDED','FAILED','REFUSED','UNAVAILABLE','TIMED_OUT',
                          'INDETERMINATE')
ON CONFLICT (command_id) DO NOTHING
""",
            commandId);
    if (inserted != 1) {
      Integer count =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM sandbox_command_receipt WHERE command_id=?",
              Integer.class,
              commandId);
      if (count == null || count != 1) {
        throw new IllegalStateException("SANDBOX_COMMAND_RECEIPT_INSERT_FAILED");
      }
    }
  }

  private Admission findAdmission(String idempotencyKey) {
    List<Admission> values =
        jdbc.query(
            "SELECT id,sandbox_id,lease_token,runtime_epoch FROM sandbox_command_execution WHERE"
                + " idempotency_key=?",
            (rs, ignored) ->
                new Admission(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("sandbox_id")),
                    rs.getLong("lease_token"),
                    rs.getLong("runtime_epoch")),
            idempotencyKey);
    return values.isEmpty() ? null : values.getFirst();
  }

  private void assertIdempotent(
      UUID id,
      AdmissionRequest request,
      RunSandbox sandbox,
      RunSandbox.Lease lease,
      long runtimeEpoch) {
    Integer count =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM sandbox_command_execution
             WHERE id=? AND run_id=? AND attempt_id IS NOT DISTINCT FROM ? AND sandbox_id=?
               AND candidate_generation=? AND runtime_owner_digest=? AND lease_token=?
               AND runtime_epoch=? AND image_digest=? AND config_digest=? AND mount_digest=?
               AND command_class=? AND argv_digest=? AND timeout_ms=?
               AND disposable_snapshot_before_sha256=?
            """,
            Integer.class,
            id,
            request.runId(),
            request.attemptId(),
            sandbox.id(),
            request.candidateGeneration(),
            sha256(lease.owner()),
            lease.fencingToken(),
            runtimeEpoch,
            sandbox.imageDigest(),
            sandbox.configDigest(),
            request.mountDigest(),
            request.commandClass(),
            request.argvDigest(),
            request.timeout().toMillis(),
            request.disposableSnapshotBeforeSha256());
    if (count == null || count != 1) {
      throw new SandboxIdempotencyConflictException(
          "sandbox command idempotency key names different immutable evidence");
    }
  }

  private static void require(
      AdmissionRequest request, RunSandbox sandbox, RunSandbox.Lease lease, long runtimeEpoch) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(sandbox, "sandbox");
    Objects.requireNonNull(lease, "lease");
    if (sandbox.purpose() != SandboxPurpose.VERIFICATION
        || !sandbox.id().equals(lease.sandboxId())
        || runtimeEpoch <= 0
        || request.timeout() == null
        || request.timeout().isNegative()
        || request.timeout().isZero()) {
      throw new IllegalArgumentException("SANDBOX_COMMAND_ADMISSION_INVALID");
    }
    requireHash(request.candidateGeneration(), "SANDBOX_COMMAND_GENERATION_INVALID");
    requireHash(request.mountDigest(), "SANDBOX_COMMAND_MOUNT_DIGEST_INVALID");
    requireHash(request.argvDigest(), "SANDBOX_COMMAND_ARGV_DIGEST_INVALID");
    requireHash(
        request.disposableSnapshotBeforeSha256(), "SANDBOX_COMMAND_BEFORE_SNAPSHOT_INVALID");
    requireText(request.runId(), "SANDBOX_COMMAND_RUN_REQUIRED");
    requireText(request.commandClass(), "SANDBOX_COMMAND_CLASS_REQUIRED");
    requireText(request.idempotencyKey(), "SANDBOX_COMMAND_IDEMPOTENCY_REQUIRED");
    requireText(sandbox.imageDigest(), "SANDBOX_COMMAND_IMAGE_DIGEST_REQUIRED");
    requireText(sandbox.configDigest(), "SANDBOX_COMMAND_CONFIG_DIGEST_REQUIRED");
  }

  public static String argvDigest(List<String> argv) {
    if (argv == null || argv.isEmpty()) throw new IllegalArgumentException("command required");
    StringBuilder canonical = new StringBuilder("argv-v1\n");
    for (String value : argv) {
      byte[] bytes = Objects.requireNonNull(value, "argv value").getBytes(StandardCharsets.UTF_8);
      canonical.append(bytes.length).append(':').append(value).append('\n');
    }
    return sha256(canonical.toString());
  }

  public static String mountDigest(String hostRoot, String containerRoot) {
    return sha256("mount-v1\n" + hostRoot + "\n" + containerRoot);
  }

  private static String outputDigest(SandboxExecResult result) {
    String canonical =
        "output-v1\n"
            + result.status()
            + "\n"
            + result.exitCode()
            + "\n"
            + Objects.toString(result.stdout(), "")
            + "\n"
            + Objects.toString(result.stderr(), "")
            + "\n"
            + result.outputTruncated()
            + "\n"
            + Objects.toString(result.detail(), "");
    return sha256(canonical);
  }

  private static String sha256(String value) {
    return DigestUtils.sha256Hex(value.getBytes(StandardCharsets.UTF_8));
  }

  private static void requireHash(String value, String message) {
    if (value == null || !value.matches(SHA_256)) throw new IllegalArgumentException(message);
  }

  private static void requireText(String value, String message) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
  }

  public enum CommandState {
    ADMITTED,
    DISPATCHED,
    SUCCEEDED,
    FAILED,
    REFUSED,
    UNAVAILABLE,
    TIMED_OUT,
    INDETERMINATE
  }

  public enum TerminalOutcome {
    SUCCEEDED,
    FAILED,
    REFUSED,
    UNAVAILABLE,
    TIMED_OUT,
    INDETERMINATE
  }

  public record Admission(UUID id, UUID sandboxId, long leaseToken, long runtimeEpoch) {}

  public record RecoveryResult(int admittedUnavailable, int dispatchedIndeterminate) {
    public int total() {
      return admittedUnavailable + dispatchedIndeterminate;
    }
  }

  private record RecoveredCommand(UUID id, CommandState state) {}

  public record AdmissionRequest(
      String runId,
      UUID attemptId,
      String candidateGeneration,
      String mountDigest,
      String commandClass,
      String argvDigest,
      Duration timeout,
      String idempotencyKey,
      String disposableSnapshotBeforeSha256) {}
}

package com.navneet.executespec.sandbox;

import static com.navneet.executespec.sandbox.RunSandboxPersistenceCodec.bounded;
import static com.navneet.executespec.sandbox.RunSandboxPersistenceCodec.requireBoundedJson;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * V307 fenced database state machine for run sandboxes.
 *
 * <p>Two independent defences on every transition:
 *
 * <ol>
 *   <li>{@link SandboxState#requireTransitionTo} rejects an undefined transition before any SQL
 *       runs, so an illegal transition fails at the store rather than by convention; and
 *   <li>the UPDATE carries both the legal-predecessor set and the caller's exact {@code
 *       (runtime_owner, lease_token)} fence, so a concurrent racer or a stale JVM that lost a
 *       takeover cannot slip an otherwise-legal transition through.
 * </ol>
 *
 * <p>Cancellation, orphan reconciliation and expiry deliberately do not consult any feature flag.
 * Turning off {@code sandbox.runtime.dispatch.enabled} must still let live sandboxes be cancelled
 * and cleaned up.
 */
@Component
@RequiredArgsConstructor
public class RunSandboxStore {

  private static final String ALL = "SELECT * FROM run_sandbox ";

  private final JdbcTemplate jdbc;

  /** Insert once. A duplicate idempotency key returns the canonical existing sandbox. */
  @Transactional
  public RunSandbox createOrGet(RunSandbox.CreateRequest request) {
    requireBoundedJson(request.repositorySet());
    if (request.networkPolicy() != SandboxNetworkPolicy.DENY_ALL) {
      throw new IllegalArgumentException(
          "only DENY_ALL network policy is implemented; no controlled egress proxy exists");
    }
    int inserted =
        jdbc.update(
            """
            INSERT INTO run_sandbox
              (id, run_id, project_id, session_id, requested_by_user_id, org_id,
               source_revision, repository_set, isolation_profile,
               isolation_profile_version, config_digest, network_policy,
               secret_policy, expires_at, idempotency_key)
            VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?, ?, ?, ?, ?, ?, ?)
            -- Scoped to the idempotency key deliberately. An unscoped DO NOTHING would also
            -- swallow the uq_run_sandbox_active_run conflict, turning "this run already has a
            -- live sandbox" into an indistinguishable silent no-op. That conflict must surface
            -- to the caller as a duplicate-key failure.
            ON CONFLICT (idempotency_key) DO NOTHING
            """,
            request.id(),
            request.runId(),
            request.projectId(),
            request.sessionId(),
            request.requestedByUserId(),
            request.orgId(),
            request.sourceRevision(),
            request.repositorySet(),
            request.isolationProfile().name(),
            request.isolationProfile().version(),
            request.configDigest(),
            request.networkPolicy().name(),
            request.secretPolicy().name(),
            Timestamp.from(request.expiresAt()),
            request.idempotencyKey());
    if (inserted == 1) {
      return get(request.id()).orElseThrow();
    }
    // RR-329 — the insert lost the idempotency-key conflict, so a row already owns this key. The
    // key is globally unique, so returning it blind would hand THIS request another request's
    // sandbox (and its runtime identity), and would treat a changed source revision or isolation
    // policy as an idempotent retry. Return the existing row only when its accountable identity
    // and immutable source/isolation policy match exactly; otherwise fail closed with a typed
    // conflict.
    RunSandbox existing =
        findByIdempotency(request.idempotencyKey())
            .orElseThrow(() -> new IllegalStateException("run_sandbox insert conflict was lost"));
    requireSameRequest(existing, request);
    return existing;
  }

  /**
   * RR-329 — proves a reused idempotency key names the SAME admission. Compares the accountable
   * identity (org, run, project, actor, session) and the immutable source/isolation policy
   * (revision, config digest, isolation profile + version, network and secret policy). The raw
   * {@code repository_set} JSON is deliberately not string-compared here because it is stored as
   * JSONB and read back re-serialized, so a whitespace-only difference is not a real conflict; the
   * repository identity it encodes is already bound into {@code config_digest} and {@code
   * source_revision}.
   */
  static void requireSameRequest(RunSandbox existing, RunSandbox.CreateRequest request) {
    if (!java.util.Objects.equals(existing.orgId(), request.orgId())
        || !java.util.Objects.equals(existing.runId(), request.runId())
        || !java.util.Objects.equals(existing.projectId(), request.projectId())
        || !java.util.Objects.equals(existing.requestedByUserId(), request.requestedByUserId())
        || !java.util.Objects.equals(existing.sessionId(), request.sessionId())
        || !java.util.Objects.equals(existing.sourceRevision(), request.sourceRevision())
        || !java.util.Objects.equals(existing.configDigest(), request.configDigest())
        || existing.isolationProfile() != request.isolationProfile()
        || existing.networkPolicy() != request.networkPolicy()
        || existing.secretPolicy() != request.secretPolicy()) {
      throw new SandboxIdempotencyConflictException(
          "idempotency key "
              + request.idempotencyKey()
              + " already names a sandbox with a different identity or source/isolation policy");
    }
  }

  public Optional<RunSandbox> get(UUID id) {
    return query(ALL + "WHERE id=?", id).stream().findFirst();
  }

  public Optional<RunSandbox> findByIdempotency(String idempotencyKey) {
    return query(ALL + "WHERE idempotency_key=?", idempotencyKey).stream().findFirst();
  }

  public Optional<RunSandbox> findActiveForRun(String runId) {
    return query(ALL + "WHERE run_id=? AND state NOT IN (" + terminalList() + ")", runId).stream()
        .findFirst();
  }

  public List<RunSandbox> listForOrg(String orgId, int limit) {
    return query(ALL + "WHERE org_id=? ORDER BY created_at DESC LIMIT ?", orgId, limit);
  }

  // ---------------------------------------------------------------- ownership

  /**
   * Claims a REQUESTED sandbox, or takes over any non-terminal sandbox whose lease has expired. The
   * fencing token increments on every acquisition, which is what invalidates the previous owner.
   * Exactly one of two racing JVMs can win, because the UPDATE's predicate re-reads the row under
   * the row lock.
   */
  @Transactional
  public Optional<RunSandbox.Lease> tryAcquire(UUID id, String owner, Duration leaseDuration) {
    Instant expiresAt = Instant.now().plus(leaseDuration);
    List<RunSandbox> claimed =
        jdbc.query(
            """
            UPDATE run_sandbox SET
              state = CASE WHEN state='REQUESTED' THEN 'PROVISIONING' ELSE state END,
              runtime_owner=?, lease_token=lease_token+1, lease_expires_at=?,
              runtime_epoch = CASE WHEN state='REQUESTED' THEN runtime_epoch+1
                                   ELSE runtime_epoch END,
              heartbeat_at=NOW(),
              started_at=COALESCE(started_at, NOW())
            WHERE id=? AND (
              state='REQUESTED'
              OR (state IN ('PROVISIONING','READY','CANCEL_REQUESTED','RELEASING')
                  AND lease_expires_at < NOW())
            )
            RETURNING *
            """,
            RunSandboxPersistenceCodec::map,
            owner,
            Timestamp.from(expiresAt),
            id);
    return claimed.stream()
        .findFirst()
        .map(row -> new RunSandbox.Lease(row.id(), owner, row.leaseToken(), expiresAt));
  }

  /** Extends a live lease. Returns false when this JVM no longer owns the sandbox. */
  public boolean heartbeat(RunSandbox.Lease lease, Duration leaseDuration) {
    return jdbc.update(
            """
            UPDATE run_sandbox SET heartbeat_at=NOW(), lease_expires_at=?
            WHERE id=? AND runtime_owner=? AND lease_token=? AND lease_expires_at>NOW()
              AND state IN ('PROVISIONING','READY','CANCEL_REQUESTED','RELEASING')
            """,
            Timestamp.from(Instant.now().plus(leaseDuration)),
            lease.sandboxId(),
            lease.owner(),
            lease.fencingToken())
        == 1;
  }

  public boolean holdsLiveLease(RunSandbox.Lease lease) {
    Integer count =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM run_sandbox
            WHERE id=? AND runtime_owner=? AND lease_token=? AND lease_expires_at>NOW()
            """,
            Integer.class,
            lease.sandboxId(),
            lease.owner(),
            lease.fencingToken());
    return count != null && count == 1;
  }

  /**
   * Whether the holder of {@code lease} may execute a command in this sandbox right now.
   *
   * <p>Read in one statement so state and fence are judged against the same row version. The two
   * refusals are kept distinct on purpose: losing the lease and finding the sandbox not READY are
   * different facts, and collapsing them would report a takeover as an ordinary "not ready".
   *
   * <p><strong>Honest bound.</strong> This is a check, not a hold. Nothing prevents the lease from
   * expiring between this call and the command starting; that window is inherent to a lease and is
   * closed in practice by two things rather than by this query — the takeover path destroys the
   * container before it does anything else, so a command issued into a taken-over sandbox fails
   * against a container that no longer exists, and the lease duration is long relative to the
   * check. A caller that needs a stronger guarantee must heartbeat, not re-check.
   */
  public SandboxExecAuthorization authorizeExec(RunSandbox.Lease lease) {
    List<SandboxExecAuthorization> rows =
        jdbc.query(
            """
            SELECT state,
                   (runtime_owner = ? AND lease_token = ? AND lease_expires_at > NOW()) AS fenced
            FROM run_sandbox WHERE id = ?
            """,
            (rs, ignored) -> {
              if (!rs.getBoolean("fenced")) {
                return SandboxExecAuthorization.FENCED;
              }
              return SandboxState.valueOf(rs.getString("state")) == SandboxState.READY
                  ? SandboxExecAuthorization.AUTHORIZED
                  : SandboxExecAuthorization.NOT_READY;
            },
            lease.owner(),
            lease.fencingToken(),
            lease.sandboxId());
    // A missing row is a lost sandbox, never an authorization.
    return rows.isEmpty() ? SandboxExecAuthorization.FENCED : rows.getFirst();
  }

  /** The three answers to "may this lease holder execute here". */
  public enum SandboxExecAuthorization {
    /** The lease is live and the sandbox is READY. */
    AUTHORIZED,
    /** The sandbox exists but is provisioning, cancelling or terminal. */
    NOT_READY,
    /** This lease is not the current owner, has expired, or the sandbox is gone. */
    FENCED
  }

  // --------------------------------------------------------------- lifecycle

  /** PROVISIONING -> READY, under the caller's fence. */
  public RunSandbox markReady(RunSandbox.Lease lease, String runtimeHandle, String imageDigest) {
    return fencedTransition(
        lease,
        SandboxState.READY,
        "runtime_handle=?, image_digest=?, ready_at=NOW(), heartbeat_at=NOW()",
        List.of(runtimeHandle, imageDigest));
  }

  /**
   * Persists the terminal outcome before teardown starts.
   *
   * <p>Idempotent for the same live fence and intent. This matters when completion and the durable
   * terminal sweep race: both may observe the same READY row, but neither may overwrite the other's
   * cleanup target or turn an already-terminal row back into live work.
   */
  public RunSandbox beginRelease(
      RunSandbox.Lease lease, SandboxState target, String failureClass, String failureDetail) {
    requireCleanupIntent(target, failureClass);
    RunSandbox current =
        get(lease.sandboxId())
            .orElseThrow(() -> new SandboxLeaseFencedException(lease.sandboxId()));
    if (sameCleanupIntent(current, target, failureClass)) {
      if (current.state() == SandboxState.RELEASING && holdsLiveLease(lease)) {
        return current;
      }
      if (current.state() == target) {
        return current;
      }
    }
    try {
      return fencedTransition(
          lease,
          SandboxState.RELEASING,
          "released_at=COALESCE(released_at,NOW()), cleanup_target=?,"
              + " cleanup_failure_class=?, cleanup_failure_detail=?, cleanup_last_error=NULL",
          java.util.Arrays.asList(
              target.name(), bounded(failureClass, 48), bounded(failureDetail, 2000)));
    } catch (IllegalSandboxTransitionException | SandboxLeaseFencedException raced) {
      RunSandbox winner =
          get(lease.sandboxId())
              .orElseThrow(() -> new SandboxLeaseFencedException(lease.sandboxId()));
      if (sameCleanupIntent(winner, target, failureClass)
          && ((winner.state() == SandboxState.RELEASING && holdsLiveLease(lease))
              || winner.state() == target)) {
        return winner;
      }
      throw raced;
    }
  }

  /**
   * Publishes the persisted cleanup target after runtime absence was verified.
   *
   * <p>Idempotent when another cleanup racer already published the same target.
   */
  public RunSandbox completeRelease(RunSandbox.Lease lease) {
    RunSandbox current =
        get(lease.sandboxId())
            .orElseThrow(() -> new SandboxLeaseFencedException(lease.sandboxId()));
    SandboxState target = current.cleanupTarget();
    if (target == null || !target.terminal()) {
      throw new IllegalStateException("sandbox has no durable cleanup target");
    }
    if (current.state() == target) {
      return current;
    }
    if (current.state() != SandboxState.RELEASING) {
      throw new IllegalSandboxTransitionException(current.state(), target);
    }
    try {
      if (target == SandboxState.FAILED) {
        return fail(lease, current.cleanupFailureClass(), current.cleanupFailureDetail());
      }
      return fencedTransition(
          lease,
          target,
          "terminal_at=NOW(), runtime_owner=NULL, lease_expires_at=NULL, heartbeat_at=NOW()",
          List.of());
    } catch (IllegalSandboxTransitionException | SandboxLeaseFencedException raced) {
      RunSandbox winner =
          get(lease.sandboxId())
              .orElseThrow(() -> new SandboxLeaseFencedException(lease.sandboxId()));
      if (winner.state() == target) {
        return winner;
      }
      throw raced;
    }
  }

  /**
   * Records an unsuccessful destroy without claiming the runtime is gone.
   *
   * <p>The lease is expired in the same fenced write so another JVM or the next local sweep can
   * reclaim the RELEASING row immediately. The runtime owner remains populated to satisfy the V307
   * live-state invariant and the fencing token still protects against stale writers.
   */
  public RunSandbox recordCleanupFailure(RunSandbox.Lease lease, String detail) {
    List<RunSandbox> updated =
        jdbc.query(
            """
            UPDATE run_sandbox SET
              cleanup_attempts=cleanup_attempts+1,
              cleanup_last_error=?,
              heartbeat_at=NOW(),
              lease_expires_at=NOW() - INTERVAL '1 microsecond'
            WHERE id=? AND runtime_owner=? AND lease_token=? AND lease_expires_at>NOW()
              AND state='RELEASING' AND cleanup_target IS NOT NULL
            RETURNING *
            """,
            RunSandboxPersistenceCodec::map,
            bounded(detail, 2000),
            lease.sandboxId(),
            lease.owner(),
            lease.fencingToken());
    if (updated.isEmpty()) {
      throw new SandboxLeaseFencedException(lease.sandboxId());
    }
    return updated.getFirst();
  }

  /**
   * Terminal failure with a mandatory classification. The V307 constraint refuses a FAILED row
   * without a {@code failure_class}, so an unexplained failure cannot be persisted as an ordinary
   * stop.
   */
  public RunSandbox fail(RunSandbox.Lease lease, String failureClass, String detail) {
    if (failureClass == null || failureClass.isBlank()) {
      throw new IllegalArgumentException("failure class is required");
    }
    return fencedTransition(
        lease,
        SandboxState.FAILED,
        "terminal_at=NOW(), failure_class=?, failure_detail=?, runtime_owner=NULL,"
            + " lease_expires_at=NULL",
        List.of(bounded(failureClass, 48), bounded(detail, 2000)));
  }

  /**
   * Requests cancellation without needing a lease or a dispatchable runtime. A sandbox nothing has
   * claimed goes straight to CANCELLED; one a runtime owns is marked CANCEL_REQUESTED for that
   * runtime to observe, and is swept by reconciliation if the runtime never comes back.
   */
  @Transactional
  public Optional<RunSandbox> requestCancellation(UUID id) {
    List<RunSandbox> changed =
        jdbc.query(
            """
            UPDATE run_sandbox SET
              state = CASE WHEN state='REQUESTED' THEN 'CANCELLED' ELSE 'CANCEL_REQUESTED' END,
              terminal_at = CASE WHEN state='REQUESTED' THEN NOW() ELSE terminal_at END,
              runtime_owner = CASE WHEN state='REQUESTED' THEN NULL ELSE runtime_owner END,
              lease_expires_at = CASE WHEN state='REQUESTED' THEN NULL
                                      ELSE lease_expires_at END
            WHERE id=? AND state IN ('REQUESTED','PROVISIONING','READY')
            RETURNING *
            """,
            RunSandboxPersistenceCodec::map,
            id);
    return changed.stream().findFirst().or(() -> get(id));
  }

  public boolean cancellationRequested(RunSandbox.Lease lease) {
    List<Boolean> rows =
        jdbc.query(
            "SELECT state='CANCEL_REQUESTED' FROM run_sandbox WHERE id=? AND runtime_owner=?"
                + " AND lease_token=?",
            (rs, ignored) -> rs.getBoolean(1),
            lease.sandboxId(),
            lease.owner(),
            lease.fencingToken());
    return !rows.isEmpty() && Boolean.TRUE.equals(rows.getFirst());
  }

  // ----------------------------------------------------------- reconciliation

  /** Non-terminal sandboxes whose owning JVM is gone. Drives startup orphan reconciliation. */
  public List<RunSandbox> orphanCandidates(int limit) {
    return query(
        ALL
            + "WHERE state IN ('PROVISIONING','READY','CANCEL_REQUESTED','RELEASING')"
            + " AND lease_expires_at < NOW() ORDER BY lease_expires_at LIMIT ?",
        limit);
  }

  /** Non-terminal sandboxes past their TTL. */
  public List<RunSandbox> expiryCandidates(int limit) {
    return query(
        ALL
            + "WHERE state NOT IN ("
            + terminalList()
            + ") AND expires_at < NOW() ORDER BY expires_at LIMIT ?",
        limit);
  }

  /**
   * Live sandboxes whose owning pipeline run is already terminal.
   *
   * <p>This is the durable fallback for a missed in-process completion event and for direct user
   * cancellation, which persists {@code pipeline_run.status=CANCELLED} before interrupting the
   * worker. Returning sandbox rows rather than run ids preserves the exact lifecycle identity the
   * caller must fence before teardown.
   */
  public List<RunSandbox> terminalRunCandidates(int limit) {
    return query(
        """
        SELECT rs.* FROM run_sandbox rs
        JOIN pipeline_run pr ON pr.run_id=rs.run_id
        WHERE rs.state NOT IN ('TERMINATED','CANCELLED','EXPIRED','FAILED')
          AND UPPER(pr.status) IN ('COMPLETE','PARTIAL','FAILED','CANCELLED')
        ORDER BY rs.created_at
        LIMIT ?
        """,
        limit);
  }

  /**
   * Settles an unclaimable sandbox without a lease. Used by reconciliation once teardown has been
   * attempted, and by expiry for a sandbox no runtime ever claimed. Requires the lease to be
   * genuinely dead, so this cannot steal a sandbox from a live owner.
   */
  @Transactional
  public Optional<RunSandbox> forceTerminal(UUID id, SandboxState terminal, String failureClass) {
    if (!terminal.terminal()) {
      throw new IllegalArgumentException("forceTerminal requires a terminal state");
    }
    if ((terminal == SandboxState.FAILED) == (failureClass == null || failureClass.isBlank())) {
      throw new IllegalArgumentException("FAILED requires a failure class; others forbid one");
    }
    String from = predecessorList(terminal);
    List<RunSandbox> changed =
        jdbc.query(
            """
            UPDATE run_sandbox SET state=?, terminal_at=NOW(), runtime_owner=NULL,
              lease_expires_at=NULL, failure_class=?,
              failure_detail = COALESCE(failure_detail, ?)
            WHERE id=? AND state IN (%s)
              AND (lease_expires_at IS NULL OR lease_expires_at < NOW())
            RETURNING *
            """
                .formatted(from),
            RunSandboxPersistenceCodec::map,
            terminal.name(),
            bounded(failureClass, 48),
            failureClass == null ? null : "settled by reconciliation after lease expiry",
            id);
    return changed.stream().findFirst();
  }

  // ------------------------------------------------------------- retention

  public boolean markRetention(UUID id, SandboxRetentionState retention, String artifactUri) {
    return jdbc.update(
            "UPDATE run_sandbox SET retention_state=?, artifact_uri=COALESCE(?, artifact_uri)"
                + " WHERE id=?",
            retention.name(),
            artifactUri,
            id)
        == 1;
  }

  // ------------------------------------------------------------------ internal

  /**
   * The one fenced write path. Validates the transition in Java, then re-validates it in SQL
   * against the legal predecessor set and the caller's exact live fence.
   */
  private RunSandbox fencedTransition(
      RunSandbox.Lease lease, SandboxState next, String extraSet, List<Object> extraArgs) {
    SandboxState current =
        get(lease.sandboxId())
            .orElseThrow(() -> new SandboxLeaseFencedException(lease.sandboxId()))
            .state();
    current.requireTransitionTo(next);

    Object[] args = new Object[extraArgs.size() + 4];
    args[0] = next.name();
    for (int i = 0; i < extraArgs.size(); i++) {
      args[i + 1] = extraArgs.get(i);
    }
    args[extraArgs.size() + 1] = lease.sandboxId();
    args[extraArgs.size() + 2] = lease.owner();
    args[extraArgs.size() + 3] = lease.fencingToken();

    List<RunSandbox> updated =
        jdbc.query(
            """
            UPDATE run_sandbox SET state=?%s
            WHERE id=? AND runtime_owner=? AND lease_token=? AND lease_expires_at>NOW()
              AND state IN (%s)
            RETURNING *
            """
                .formatted(extraSet.isEmpty() ? "" : ", " + extraSet, predecessorList(next)),
            RunSandboxPersistenceCodec::map,
            args);
    if (updated.isEmpty()) {
      throw new SandboxLeaseFencedException(lease.sandboxId());
    }
    return updated.getFirst();
  }

  private static String predecessorList(SandboxState next) {
    return SandboxState.predecessorsOf(next).stream()
        .map(state -> "'" + state.name() + "'")
        .collect(Collectors.joining(","));
  }

  private static String terminalList() {
    return "'TERMINATED','CANCELLED','EXPIRED','FAILED'";
  }

  private static void requireCleanupIntent(SandboxState target, String failureClass) {
    if (target == null || !target.terminal()) {
      throw new IllegalArgumentException("cleanup target must be terminal");
    }
    if ((target == SandboxState.FAILED) != (failureClass != null && !failureClass.isBlank())) {
      throw new IllegalArgumentException(
          "FAILED cleanup requires a failure class; others forbid it");
    }
  }

  private static boolean sameCleanupIntent(
      RunSandbox sandbox, SandboxState target, String failureClass) {
    return sandbox.cleanupTarget() == target
        && java.util.Objects.equals(sandbox.cleanupFailureClass(), bounded(failureClass, 48));
  }

  private List<RunSandbox> query(String sql, Object... args) {
    return jdbc.query(sql, RunSandboxPersistenceCodec::map, args);
  }
}

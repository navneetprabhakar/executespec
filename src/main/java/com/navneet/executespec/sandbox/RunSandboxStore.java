package com.navneet.executespec.sandbox;

import java.sql.ResultSet;
import java.sql.SQLException;
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

  private static final int MAX_REPOSITORY_SET_CHARS = 200_000;
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
    return findByIdempotency(request.idempotencyKey())
        .orElseThrow(() -> new IllegalStateException("run_sandbox insert conflict was lost"));
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
            RunSandboxStore::map,
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

  // --------------------------------------------------------------- lifecycle

  /** PROVISIONING -> READY, under the caller's fence. */
  public RunSandbox markReady(RunSandbox.Lease lease, String runtimeHandle, String imageDigest) {
    return fencedTransition(
        lease,
        SandboxState.READY,
        "runtime_handle=?, image_digest=?, ready_at=NOW(), heartbeat_at=NOW()",
        List.of(runtimeHandle, imageDigest));
  }

  /** Begins teardown. Legal from PROVISIONING, READY or CANCEL_REQUESTED. */
  public RunSandbox beginRelease(RunSandbox.Lease lease) {
    return fencedTransition(lease, SandboxState.RELEASING, "released_at=NOW()", List.of());
  }

  /** RELEASING -> a terminal non-failure state. Drops the lease. */
  public RunSandbox finishRelease(RunSandbox.Lease lease, SandboxState terminal) {
    if (terminal == SandboxState.FAILED || !terminal.terminal()) {
      throw new IllegalArgumentException(
          "finishRelease expects a non-failure terminal state, got " + terminal);
    }
    return fencedTransition(
        lease,
        terminal,
        "terminal_at=NOW(), runtime_owner=NULL, lease_expires_at=NULL, heartbeat_at=NOW()",
        List.of());
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
            RunSandboxStore::map,
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
            RunSandboxStore::map,
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
            RunSandboxStore::map,
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

  private List<RunSandbox> query(String sql, Object... args) {
    return jdbc.query(sql, RunSandboxStore::map, args);
  }

  private static RunSandbox map(ResultSet rs, int ignored) throws SQLException {
    return new RunSandbox(
        rs.getObject("id", UUID.class),
        rs.getString("run_id"),
        rs.getString("project_id"),
        rs.getObject("session_id", UUID.class),
        rs.getString("requested_by_user_id"),
        rs.getString("org_id"),
        rs.getString("source_revision"),
        rs.getString("repository_set"),
        SandboxState.valueOf(rs.getString("state")),
        SandboxIsolationProfile.valueOf(rs.getString("isolation_profile")),
        rs.getInt("isolation_profile_version"),
        rs.getString("runtime_owner"),
        rs.getLong("lease_token"),
        instant(rs, "lease_expires_at"),
        rs.getLong("runtime_epoch"),
        instant(rs, "heartbeat_at"),
        rs.getString("runtime_handle"),
        rs.getString("image_digest"),
        rs.getString("config_digest"),
        SandboxNetworkPolicy.valueOf(rs.getString("network_policy")),
        SandboxSecretPolicy.valueOf(rs.getString("secret_policy")),
        instant(rs, "expires_at"),
        rs.getString("failure_class"),
        rs.getString("failure_detail"),
        SandboxRetentionState.valueOf(rs.getString("retention_state")),
        rs.getString("artifact_uri"),
        rs.getString("idempotency_key"),
        instant(rs, "created_at"),
        instant(rs, "started_at"),
        instant(rs, "ready_at"),
        instant(rs, "released_at"),
        instant(rs, "terminal_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static String bounded(String value, int max) {
    if (value == null) {
      return null;
    }
    String clean =
        value.replaceAll("(?i)(api[-_ ]?key|token|secret|bearer)\\s*[:=]\\s*\\S+", "$1=[REDACTED]");
    return clean.length() <= max ? clean : clean.substring(0, max);
  }

  private static void requireBoundedJson(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("sandbox repository set is required");
    }
    if (value.length() > MAX_REPOSITORY_SET_CHARS) {
      throw new IllegalArgumentException(
          "sandbox repository set exceeds " + MAX_REPOSITORY_SET_CHARS + " characters");
    }
  }
}

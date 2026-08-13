package com.navneet.executespec.integration;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationTelemetry;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Cross-JVM mutually exclusive authority over one run workspace. */
@Component
@RequiredArgsConstructor
public class RunWorkspaceAuthorityService {

  private static final Duration LEASE_TTL = Duration.ofMinutes(3);
  private static final ThreadLocal<Lease> CORRECTION_TOOL_LEASE = new ThreadLocal<>();

  private final JdbcTemplate jdbc;
  private final AgentProperties agentProperties;
  private final WorkspaceGenerationTelemetry telemetry;
  private final Map<UUID, Lease> localLeases = new ConcurrentHashMap<>();

  public Lease acquire(String runId, Authority authority) {
    return tryAcquire(runId, authority)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "RUN_WORKSPACE_AUTHORITY_BUSY: workspace is owned by another operation"));
  }

  public Optional<Lease> tryAcquire(String runId, Authority authority) {
    LocalFileAuthority fileAuthority = tryAcquireLocalFile(runId);
    if (fileAuthority == null) {
      telemetry.authority("BUSY", authority.name());
      return Optional.empty();
    }
    UUID token = UUID.randomUUID();
    int acquired;
    try {
      acquired =
          jdbc.update(
              """
              INSERT INTO run_workspace_authority
                (run_id, authority_type, owner_token, lease_expires_at, heartbeat_at, updated_at)
              VALUES (?, ?, ?, NOW() + (? * INTERVAL '1 millisecond'), NOW(), NOW())
              ON CONFLICT (run_id) DO UPDATE
                SET authority_type = EXCLUDED.authority_type,
                    owner_token = EXCLUDED.owner_token,
                    lease_expires_at = EXCLUDED.lease_expires_at,
                    heartbeat_at = NOW(), updated_at = NOW()
              WHERE run_workspace_authority.lease_expires_at < NOW()
              """,
              runId,
              authority.name(),
              token,
              LEASE_TTL.toMillis());
    } catch (RuntimeException failure) {
      fileAuthority.close();
      throw failure;
    }
    if (acquired != 1) {
      fileAuthority.close();
      telemetry.authority("BUSY", authority.name());
      return Optional.empty();
    }
    Lease lease = new Lease(runId, authority, token, this, fileAuthority);
    localLeases.put(token, lease);
    telemetry.authority("ACQUIRED", authority.name());
    return Optional.of(lease);
  }

  /**
   * Per-tool fencing hook for correction workers.
   *
   * <p>This performs a fresh database ownership/expiry check, so an old worker resuming after a GC
   * pause or lease takeover cannot dispatch another local, remote, shell, build, test, finalizer,
   * or direct tool call. Ordinary workers have no registered correction lease and are unchanged.
   */
  public static void assertCorrectionToolDispatch(String runId) {
    Lease correction = CORRECTION_TOOL_LEASE.get();
    assertToolLease(runId, correction, "CORRECTION_TOOL_LEASE_RUN_MISMATCH");
  }

  public static Lease currentCorrectionToolLease() {
    return CORRECTION_TOOL_LEASE.get();
  }

  /** Binds the exact attempt lease, never the latest per-run lease, to a worker dispatch thread. */
  public static <T> T withCorrectionToolLease(Lease lease, Supplier<T> work) {
    Lease prior = CORRECTION_TOOL_LEASE.get();
    if (lease == null) CORRECTION_TOOL_LEASE.remove();
    else CORRECTION_TOOL_LEASE.set(lease);
    try {
      return work.get();
    } finally {
      if (prior == null) CORRECTION_TOOL_LEASE.remove();
      else CORRECTION_TOOL_LEASE.set(prior);
    }
  }

  private static void assertToolLease(String runId, Lease lease, String mismatchCode) {
    if (lease == null) return;
    if (!lease.runId().equals(runId)) throw new IllegalStateException(mismatchCode);
    lease.assertOwned();
  }

  @Scheduled(fixedDelayString = "${execution.workspace-authority.heartbeat-ms:30000}")
  void heartbeatLocalLeases() {
    for (Lease lease : localLeases.values()) {
      int renewed =
          jdbc.update(
              """
              UPDATE run_workspace_authority
                 SET lease_expires_at = NOW() + (? * INTERVAL '1 millisecond'),
                     heartbeat_at = NOW(), updated_at = NOW()
               WHERE run_id = ? AND owner_token = ? AND authority_type = ?
                 AND lease_expires_at >= NOW()
              """,
              LEASE_TTL.toMillis(),
              lease.runId(),
              lease.ownerToken(),
              lease.authority().name());
      if (renewed != 1) {
        localLeases.remove(lease.ownerToken());
        lease.lost = true;
        telemetry.authority("LOST", lease.authority().name());
      }
    }
  }

  public void assertOwned(Lease lease) {
    int renewed =
        jdbc.update(
            """
            UPDATE run_workspace_authority
               SET lease_expires_at = NOW() + (? * INTERVAL '1 millisecond'),
                   heartbeat_at = NOW(), updated_at = NOW()
             WHERE run_id = ? AND owner_token = ? AND authority_type = ?
               AND lease_expires_at >= NOW()
            """,
            LEASE_TTL.toMillis(),
            lease.runId(),
            lease.ownerToken(),
            lease.authority().name());
    if (lease.lost || renewed != 1) {
      lease.lost = true;
      localLeases.remove(lease.ownerToken());
      throw new IllegalStateException("RUN_WORKSPACE_AUTHORITY_LOST");
    }
  }

  /**
   * Releases only the exact BUILD authority whose durable terminal generation, task receipt, and
   * physical LLM operation have converged. This is restart-safe and cannot delete a newer or
   * non-generation authority.
   */
  public boolean releaseConvergedGeneration(String runId) {
    int deleted =
        jdbc.update(
            """
            DELETE FROM run_workspace_authority w
             WHERE w.run_id=? AND w.authority_type='BUILD_GENERATION'
               AND EXISTS (
                 SELECT 1
                   FROM workspace_generation_attempt a
                   JOIN llm_execution_operation o ON o.id=a.llm_operation_id
                  WHERE a.run_id=w.run_id AND a.workspace_lease_token=w.owner_token
                    AND a.state IN ('ACCEPTED','ROLLED_BACK')
                    AND a.task_transition_state='COMPLETED'
                    AND a.operation_transition_state='COMPLETED'
                    AND ((a.state='ACCEPTED' AND o.status='SUCCEEDED')
                      OR (a.state='ROLLED_BACK' AND o.status='FAILED'))
               )
            """,
            runId);
    if (deleted > 1) {
      throw new IllegalStateException("RUN_WORKSPACE_TERMINAL_AUTHORITY_DELETE_INVALID");
    }
    return deleted == 1;
  }

  private void release(Lease lease) {
    localLeases.remove(lease.ownerToken());
    try {
      jdbc.update(
          "DELETE FROM run_workspace_authority WHERE run_id = ? AND owner_token = ?",
          lease.runId(),
          lease.ownerToken());
    } finally {
      lease.fileAuthority.close();
      telemetry.authority("RELEASED", lease.authority().name());
    }
  }

  private LocalFileAuthority tryAcquireLocalFile(String runId) {
    FileChannel channel = null;
    try {
      Path directory =
          Files.createDirectories(
              Path.of(agentProperties.getWorkspace()).resolve(".workspace-authority"));
      Path lockPath = directory.resolve(runId.replaceAll("[^A-Za-z0-9._-]", "_") + ".lock");
      channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      FileLock lock = channel.tryLock();
      if (lock == null) {
        channel.close();
        return null;
      }
      return new LocalFileAuthority(channel, lock);
    } catch (java.nio.channels.OverlappingFileLockException busy) {
      closeQuietly(channel);
      return null;
    } catch (java.io.IOException failure) {
      closeQuietly(channel);
      throw new IllegalStateException("RUN_WORKSPACE_FILE_AUTHORITY_UNAVAILABLE", failure);
    }
  }

  private static void closeQuietly(FileChannel channel) {
    if (channel == null) return;
    try {
      channel.close();
    } catch (java.io.IOException ignored) {
      // Best effort after failed acquisition; no lock authority was returned.
    }
  }

  private record LocalFileAuthority(FileChannel channel, FileLock lock) implements AutoCloseable {
    @Override
    public synchronized void close() {
      try {
        lock.release();
      } catch (java.io.IOException ignored) {
        // Closing the channel below releases the process-local lock as the final fallback.
      } finally {
        closeQuietly(channel);
      }
    }
  }

  public enum Authority {
    BUILD_GENERATION,
    CORRECTION,
    RECOVERY,
    PUBLICATION_PREPARE,
    PUBLICATION
  }

  public static class Lease implements AutoCloseable {
    private final String runId;
    private final Authority authority;
    private final UUID ownerToken;
    private final RunWorkspaceAuthorityService owner;
    private final LocalFileAuthority fileAuthority;
    private volatile boolean lost;
    private volatile boolean closed;

    /**
     * Package-visible construction seam for isolated tests; production leases use the owner path.
     */
    Lease() {
      this.runId = null;
      this.authority = null;
      this.ownerToken = null;
      this.owner = null;
      this.fileAuthority = null;
    }

    private Lease(
        String runId,
        Authority authority,
        UUID ownerToken,
        RunWorkspaceAuthorityService owner,
        LocalFileAuthority fileAuthority) {
      this.runId = runId;
      this.authority = authority;
      this.ownerToken = ownerToken;
      this.owner = owner;
      this.fileAuthority = fileAuthority;
    }

    public String runId() {
      return runId;
    }

    public Authority authority() {
      return authority;
    }

    public UUID ownerToken() {
      return ownerToken;
    }

    public void assertOwned() {
      owner.assertOwned(this);
    }

    @Override
    public synchronized void close() {
      if (!closed) {
        closed = true;
        owner.release(this);
      }
    }
  }
}

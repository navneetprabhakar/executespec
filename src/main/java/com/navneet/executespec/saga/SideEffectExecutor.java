package com.navneet.executespec.saga;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PreDestroy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Processes {@link SideEffectOutboxEntry} records through a committed cross-JVM lease.
 *
 * <p>For each due entry:
 *
 * <ol>
 *   <li>Claims it in a separate committed transaction (PENDING/stale PROCESSING → PROCESSING)
 *   <li>Dispatches to the appropriate handler by {@code effectType}
 *   <li>Heartbeats during the handler and fences every terminal/retry update by lease token
 * </ol>
 *
 * <p>Delivery is at-least-once, not exactly-once: a process can die after an external system
 * accepts a request. {@link SideEffectHandlerContract} therefore admits only handlers whose replay
 * behavior is explicitly idempotent. Backoff is 1 min → 5 min → 30 min, capped by {@code
 * max_attempts}.
 */
@Service
@Slf4j
public class SideEffectExecutor {

  private static final Duration[] BACKOFF = {
    Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30)
  };

  private final SideEffectClaimStore claimStore;
  private final ObjectMapper mapper;
  private final MeterRegistry meterRegistry;
  private final com.navneet.executespec.deployment.WorkspaceExportService exportService;
  private final com.navneet.executespec.deployment.GcsWorkspaceStore gcsStore;
  private final com.navneet.executespec.config.AgentProperties agentProps;
  private final com.navneet.executespec.state.StateStore stateStore;
  // RL-2026-05-041 (audit #16) — real branch-delete compensation.
  private final com.navneet.executespec.repo.GitHubRepoService gitHubRepoService;
  private final com.navneet.executespec.auth.repository.AppUserRepository appUserRepository;
  private final SideEffectOutboxProperties properties;
  private final String workerId = hostLabel() + ":" + UUID.randomUUID();
  private final ScheduledExecutorService heartbeatExecutor =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "side-effect-outbox-heartbeat");
            thread.setDaemon(true);
            return thread;
          });

  public SideEffectExecutor(
      SideEffectClaimStore claimStore,
      ObjectMapper mapper,
      MeterRegistry meterRegistry,
      com.navneet.executespec.deployment.WorkspaceExportService exportService,
      com.navneet.executespec.deployment.GcsWorkspaceStore gcsStore,
      com.navneet.executespec.config.AgentProperties agentProps,
      com.navneet.executespec.state.StateStore stateStore,
      com.navneet.executespec.repo.GitHubRepoService gitHubRepoService,
      com.navneet.executespec.auth.repository.AppUserRepository appUserRepository,
      SideEffectOutboxProperties properties) {
    this.claimStore = claimStore;
    this.mapper = mapper;
    this.meterRegistry = meterRegistry;
    this.exportService = exportService;
    this.gcsStore = gcsStore;
    this.agentProps = agentProps;
    this.stateStore = stateStore;
    this.gitHubRepoService = gitHubRepoService;
    this.appUserRepository = appUserRepository;
    this.properties = properties;
  }

  @Scheduled(fixedDelayString = "${side-effect-outbox.poll-interval-millis:30000}")
  public void processDue() {
    int recoveredFailures = claimStore.failExpiredExhausted(properties.getBatchSize());
    if (recoveredFailures > 0) {
      log.error(
          "[SideEffectExecutor] Marked {} exhausted crashed deliveries FAILED", recoveredFailures);
    }
    // This worker executes handlers sequentially, so claim one immediately before dispatch. A
    // pre-claimed batch could expire behind one slow handler and consume attempts without running.
    for (int processed = 0; processed < properties.getBatchSize(); processed++) {
      var claimed =
          claimStore.claimDue(workerId, 1, Duration.ofSeconds(properties.getLeaseSeconds()));
      if (claimed.isEmpty()) {
        break;
      }
      processLease(claimed.getFirst());
    }
  }

  /** Backward-compatible single-entry trigger; the durable store still owns the committed claim. */
  public void processEntry(SideEffectOutboxEntry entry) {
    if (entry == null || entry.getId() == null) {
      return;
    }
    var claimed =
        claimStore.tryClaim(
            entry.getId(),
            workerId,
            Duration.ofSeconds(properties.getLeaseSeconds()),
            entry.getMaxAttempts());
    if (claimed.isEmpty()) {
      log.debug("[SideEffectExecutor] Entry {} already claimed — skipping", entry.getId());
      return;
    }
    processLease(claimed.get());
  }

  void processLease(SideEffectClaimStore.Lease lease) {
    AtomicBoolean leaseValid = new AtomicBoolean(true);
    ScheduledFuture<?> heartbeat = startHeartbeat(lease, leaseValid);

    try {
      ensureLease(lease, leaseValid);
      SideEffectHandlerContract contract = verifiedContract(lease);
      Map<String, Object> payload = parsePayload(lease.payload());
      String handlerResult = dispatch(contract, lease.runId(), payload);
      ensureLease(lease, leaseValid);
      if (!claimStore.complete(lease, handlerResult)) {
        throw new LeaseLostException();
      }

      Counter.builder("executespec.side_effect.completed")
          .tag("type", lease.effectType())
          .register(meterRegistry)
          .increment();
      log.info(
          "[SideEffectExecutor] Completed {} for run {} attempt={} token={} key={}",
          lease.effectType(),
          lease.runId(),
          lease.attempt(),
          lease.fencingToken(),
          lease.idempotencyKey());
    } catch (LeaseLostException lost) {
      Counter.builder("executespec.side_effect.lease_lost")
          .tag("type", lease.effectType())
          .register(meterRegistry)
          .increment();
      log.warn(
          "[SideEffectExecutor] Lease lost for {} run={} token={}; stale outcome discarded",
          lease.effectType(),
          lease.runId(),
          lease.fencingToken());
    } catch (NonRetryableSideEffectException nonRetryable) {
      SideEffectClaimStore.DeliveryResult result = claimStore.failPermanently(lease, nonRetryable);
      recordFailureMetric(lease.effectType(), result);
      log.error(
          "[SideEffectExecutor] Permanently FAILED non-retryable {} run={} attempt={}: {}",
          lease.effectType(),
          lease.runId(),
          lease.attempt(),
          nonRetryable.getMessage());
    } catch (Exception e) {
      Duration backoff = BACKOFF[Math.min(lease.attempt() - 1, BACKOFF.length - 1)];
      SideEffectClaimStore.DeliveryResult result = claimStore.failOrRetry(lease, e, backoff);
      recordFailureMetric(lease.effectType(), result);
      log.warn(
          "[SideEffectExecutor] {} {} run={} attempt={}/{} token={} — {}",
          result,
          lease.effectType(),
          lease.runId(),
          lease.attempt(),
          lease.maxAttempts(),
          lease.fencingToken(),
          e.getMessage());
    } finally {
      heartbeat.cancel(false);
    }
  }

  private String dispatch(
      SideEffectHandlerContract contract, String runId, Map<String, Object> payload) {
    return switch (contract) {
      case COMPENSATE_GITHUB_BRANCH -> compensateGitHubBranch(runId, payload);
      case GCS_ARCHIVE -> retryGcsArchive(runId, payload);
    };
  }

  private String retryGcsArchive(String runId, Map<String, Object> payload) {
    // Idempotent: skip if already uploaded
    var run = stateStore.findRun(runId).orElse(null);
    if (run != null && run.getGcsWorkspaceKey() != null) {
      log.info(
          "[SideEffectExecutor] GCS archive already complete for run {} — skipping retry", runId);
      return "ALREADY_ARCHIVED";
    }

    Path workspace = Path.of(agentProps.getWorkspace(), runId);
    if (!Files.isDirectory(workspace)) {
      throw new NonRetryableSideEffectException(
          "Local workspace absent; archive cannot be reconstructed for run " + runId);
    }

    try {
      Path zipPath = exportService.exportWorkspace(runId);
      try {
        String gcsKey = gcsStore.uploadZip(runId, zipPath);
        if (gcsKey == null || gcsKey.isBlank()) {
          throw new IllegalStateException("GCS archive returned no durable object key");
        }
        if (run != null) {
          run.setGcsWorkspaceKey(gcsKey);
          stateStore.saveRun(run);
        }
        log.info("[SideEffectExecutor] GCS archive retry succeeded for run {} → {}", runId, gcsKey);
        return "ARCHIVED:" + gcsKey;
      } finally {
        Files.deleteIfExists(zipPath);
      }
    } catch (Exception e) {
      log.warn(
          "[SideEffectExecutor] GCS archive retry failed for run {}: {}", runId, e.getMessage());
      throw new RuntimeException("GCS archive failed: " + e.getMessage(), e);
    }
  }

  /**
   * RL-2026-05-041 (audit #16) — deletes an orphan GitHub branch left behind when a run pushed a
   * branch but PR creation failed. Enqueued by {@code ScmPushService} at exactly that point.
   *
   * <p>Payload carries {@code repoFullName} + {@code branch} only — never the PAT (no secrets at
   * rest in the outbox). The caller's PAT is resolved from the run owner at execution time,
   * honoring the "every GitHub op uses the caller's own PAT — there is no system PAT" rule. {@code
   * deleteBranch} refuses protected branches (main/master/develop) as a safety net, so this can
   * never delete a real target branch.
   */
  private String compensateGitHubBranch(String runId, Map<String, Object> payload) {
    String repoFullName = (String) payload.get("repoFullName");
    String branch = (String) payload.get("branch");
    // Back-compat with the legacy owner/repo payload shape.
    if (repoFullName == null) {
      String owner = (String) payload.get("owner");
      String repo = (String) payload.get("repo");
      if (owner != null && repo != null) repoFullName = owner + "/" + repo;
    }
    if (repoFullName == null || branch == null || branch.isBlank()) {
      throw new NonRetryableSideEffectException(
          "COMPENSATE_GITHUB_BRANCH missing repoFullName/branch for run " + runId);
    }
    String pat = resolveOwnerPat(runId);
    if (pat == null || pat.isBlank()) {
      throw new NonRetryableSideEffectException(
          "No GitHub PAT for run owner; cannot delete orphan branch "
              + branch
              + " on "
              + repoFullName);
    }
    boolean ok = gitHubRepoService.deleteBranch(repoFullName, branch, pat);
    if (ok) {
      log.info(
          "[SideEffectExecutor] COMPENSATE: deleted orphan branch {} on {} for run {}",
          branch,
          repoFullName,
          runId);
      return "BRANCH_ABSENT:" + repoFullName + ":" + branch;
    } else {
      // Real failure (network / 403 / 401) — throw so the outbox retries with backoff.
      throw new RuntimeException(
          "deleteBranch returned false for " + repoFullName + " branch " + branch);
    }
  }

  /** Resolve the run owner's GitHub PAT (no system PAT exists). Null when unavailable. */
  private String resolveOwnerPat(String runId) {
    try {
      var run = stateStore.findRun(runId).orElse(null);
      if (run == null || run.getCreatedByUserId() == null) return null;
      return appUserRepository
          .findById(run.getCreatedByUserId())
          .map(com.navneet.executespec.auth.entity.AppUser::getGithubPat)
          .orElse(null);
    } catch (Exception e) {
      log.debug("[SideEffectExecutor] PAT resolve failed for run {}: {}", runId, e.getMessage());
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parsePayload(String json) {
    try {
      Map<String, Object> payload = mapper.readValue(json, new TypeReference<>() {});
      if (payload == null) {
        throw new NonRetryableSideEffectException("Side-effect payload must be a JSON object");
      }
      return payload;
    } catch (NonRetryableSideEffectException invalidPayload) {
      throw invalidPayload;
    } catch (Exception e) {
      throw new NonRetryableSideEffectException("Malformed side-effect payload", e);
    }
  }

  private SideEffectHandlerContract verifiedContract(SideEffectClaimStore.Lease lease) {
    final SideEffectHandlerContract contract;
    try {
      contract = SideEffectHandlerContract.require(lease.effectType());
    } catch (IllegalArgumentException unsupported) {
      throw new NonRetryableSideEffectException(unsupported.getMessage(), unsupported);
    }
    if (!contract.version().equals(lease.handlerContractVersion())) {
      throw new NonRetryableSideEffectException(
          "Handler contract mismatch for "
              + lease.effectType()
              + ": persisted="
              + lease.handlerContractVersion()
              + ", runtime="
              + contract.version());
    }
    return contract;
  }

  private ScheduledFuture<?> startHeartbeat(
      SideEffectClaimStore.Lease lease, AtomicBoolean leaseValid) {
    long intervalSeconds =
        Math.max(
            1,
            Math.min(
                properties.getHeartbeatSeconds(), Math.max(1, properties.getLeaseSeconds() / 3)));
    return heartbeatExecutor.scheduleAtFixedRate(
        () -> {
          try {
            if (!claimStore.heartbeat(lease, Duration.ofSeconds(properties.getLeaseSeconds()))) {
              leaseValid.set(false);
            }
          } catch (Exception heartbeatFailure) {
            leaseValid.set(false);
            log.warn(
                "[SideEffectExecutor] Heartbeat failed entry={} token={}: {}",
                lease.id(),
                lease.fencingToken(),
                heartbeatFailure.getMessage());
          }
        },
        intervalSeconds,
        intervalSeconds,
        TimeUnit.SECONDS);
  }

  private void ensureLease(SideEffectClaimStore.Lease lease, AtomicBoolean leaseValid) {
    if (!leaseValid.get()
        || !claimStore.heartbeat(lease, Duration.ofSeconds(properties.getLeaseSeconds()))) {
      leaseValid.set(false);
      throw new LeaseLostException();
    }
  }

  private void recordFailureMetric(String effectType, SideEffectClaimStore.DeliveryResult result) {
    String metric =
        switch (result) {
          case RETRY_SCHEDULED -> "executespec.side_effect.retried";
          case FAILED -> "executespec.side_effect.failed";
          case LEASE_LOST -> "executespec.side_effect.lease_lost";
        };
    Counter.builder(metric).tag("type", effectType).register(meterRegistry).increment();
  }

  @PreDestroy
  void shutdownHeartbeatExecutor() {
    heartbeatExecutor.shutdownNow();
  }

  private static String hostLabel() {
    String host = System.getenv("HOSTNAME");
    return host == null || host.isBlank() ? "local" : host;
  }

  private static final class LeaseLostException extends RuntimeException {}

  private static final class NonRetryableSideEffectException extends RuntimeException {
    private NonRetryableSideEffectException(String message) {
      super(message);
    }

    private NonRetryableSideEffectException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}

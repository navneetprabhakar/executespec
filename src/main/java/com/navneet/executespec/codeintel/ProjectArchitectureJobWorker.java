package com.navneet.executespec.codeintel;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.navneet.executespec.project.service.ProjectRepoService;
import com.navneet.executespec.repo.ScmCapability;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/** Multi-JVM worker for V353 staged and fenced architecture refreshes. */
@Component
@Slf4j
public class ProjectArchitectureJobWorker {

  private final ProjectArchitectureJobStore store;
  private final ProjectArchitectureJobService jobs;
  private final ProjectArchitectureService architecture;
  private final ProjectArchitectureJobPublisher publisher;
  private final ProjectRepoService repoService;
  private final StalenessService staleness;
  private final ProjectArchitectureJobProperties properties;
  private final ThreadPoolTaskExecutor executor;
  private final ScheduledExecutorService leaseExecutor;
  private final AtomicInteger inFlight = new AtomicInteger();
  private final String workerId = hostLabel() + ":" + UUID.randomUUID();

  public ProjectArchitectureJobWorker(
      ProjectArchitectureJobStore store,
      ProjectArchitectureJobService jobs,
      ProjectArchitectureService architecture,
      ProjectArchitectureJobPublisher publisher,
      ProjectRepoService repoService,
      StalenessService staleness,
      ProjectArchitectureJobProperties properties,
      @Qualifier("projectArchitectureExecutor") ThreadPoolTaskExecutor executor,
      @Qualifier("projectArchitectureLeaseExecutor") ScheduledExecutorService leaseExecutor) {
    this.store = store;
    this.jobs = jobs;
    this.architecture = architecture;
    this.publisher = publisher;
    this.repoService = repoService;
    this.staleness = staleness;
    this.properties = properties;
    this.executor = executor;
    this.leaseExecutor = leaseExecutor;
  }

  @Scheduled(fixedDelayString = "${project.architecture-jobs.poll-interval-millis:2000}")
  public void poll() {
    store.sweep(properties.getMaxAttempts());
    int available = Math.max(0, properties.getMaxParallelJobs() - inFlight.get());
    if (available == 0) return;
    for (UUID candidate : store.claimCandidates(available, properties.getMaxAttempts())) {
      var lease =
          store.tryAcquire(
              candidate,
              workerId,
              Duration.ofSeconds(properties.getLeaseSeconds()),
              properties.getMaxAttempts());
      if (lease.isEmpty()) continue;
      inFlight.incrementAndGet();
      try {
        executor.execute(
            () -> {
              try {
                process(lease.get());
              } finally {
                inFlight.decrementAndGet();
              }
            });
      } catch (RejectedExecutionException saturated) {
        inFlight.decrementAndGet();
        store.failOrRequeue(
            lease.get(),
            "EXECUTOR_SATURATED",
            "Map refresh is waiting for worker capacity.",
            properties.getMaxAttempts(),
            Duration.ofSeconds(10));
      }
    }
  }

  @Scheduled(fixedDelayString = "${project.architecture-jobs.retention-sweep-millis:3600000}")
  public void pruneTerminalJobs() {
    store.deleteTerminalOlderThan(Duration.ofDays(properties.getRetentionDays()));
  }

  void process(ProjectArchitectureJobStore.Lease initialLease) {
    AtomicBoolean leaseLost = new AtomicBoolean(false);
    AtomicBoolean executionStarted = new AtomicBoolean(false);
    AtomicBoolean contextsClaimed = new AtomicBoolean(false);
    AtomicReference<ProjectArchitectureJobStore.Job> contextJob = new AtomicReference<>();
    ProjectArchitectureJobStore.Lease lease = initialLease;
    ScheduledFuture<?> heartbeat = null;
    try {
      long heartbeatSeconds = Math.max(5, properties.getLeaseSeconds() / 3L);
      ProjectArchitectureJobStore.Lease heartbeatLease = lease;
      heartbeat =
          leaseExecutor.scheduleAtFixedRate(
              () -> {
                try {
                  if (!store.renew(heartbeatLease, Duration.ofSeconds(properties.getLeaseSeconds()))
                      || (contextJob.get() != null
                          && !jobs.heartbeatContexts(
                              contextJob.get(),
                              heartbeatLease.owner(),
                              Duration.ofSeconds(properties.getLeaseSeconds())))) {
                    leaseLost.set(true);
                  }
                } catch (Throwable heartbeatFailure) {
                  leaseLost.set(true);
                  log.warn(
                      "architecture lease heartbeat failed closed job={} errorClass={}",
                      heartbeatLease.job().id(),
                      heartbeatFailure.getClass().getSimpleName());
                }
              },
              heartbeatSeconds,
              heartbeatSeconds,
              TimeUnit.SECONDS);

      repoService.requireProjectCapability(
          lease.job().projectId(), ScmCapability.ARCHITECTURE, "generate project architecture");
      ProjectArchitectureJobService.Prepared prepared = jobs.prepare(lease);
      if (leaseLost.get()) throw new ProjectArchitectureJobService.LeaseLostException();
      ProjectArchitectureJobStore.Job preparedJob = jobs.bindPrepared(lease, prepared);
      lease =
          new ProjectArchitectureJobStore.Lease(
              preparedJob, lease.owner(), lease.fencingToken(), lease.expiresAt());
      jobs.claimContexts(
          preparedJob, lease.owner(), Duration.ofSeconds(properties.getLeaseSeconds()));
      contextsClaimed.set(true);
      contextJob.set(preparedJob);
      if (!store.beginExecuting(lease)) {
        throw new ProjectArchitectureJobService.LeaseLostException();
      }

      executionStarted.set(true);
      ProjectArchitectureJobStore.Lease executionLease = lease;
      var staged =
          architecture.stageForDurableJob(
              preparedJob,
              prepared.snapshot(),
              jobs.resolveCredentials(preparedJob),
              () -> requireLive(leaseLost, executionLease));
      String stagedJson = jobs.canonicalJson(staged);
      String stagedDigest = ProjectArchitectureJobService.sha256(stagedJson);
      String qualityEvidence =
          jobs.canonicalJson(
              java.util.Map.of(
                  "schemaVersion", 1,
                  "status", staged.qualityStatus(),
                  "warnings", staged.qualityEvidence()));
      requireLive(leaseLost, lease);
      if (leaseLost.get()
          || !store.stage(
              lease, stagedJson, stagedDigest, staged.qualityStatus(), qualityEvidence)) {
        throw new ProjectArchitectureJobService.LeaseLostException();
      }
      ProjectArchitectureJobStore.Job stagedJob = store.get(preparedJob.id()).orElseThrow();
      requireLive(leaseLost, lease);
      if (leaseLost.get() || !jobs.sourceStillCurrent(stagedJob)) {
        store.fail(
            lease,
            "SOURCE_CHANGED_DURING_EXECUTION",
            "Linked repository source changed while the map refresh was running.");
        return;
      }
      requireLive(leaseLost, lease);
      if (leaseLost.get() || !store.beginPublishing(lease)) {
        throw new ProjectArchitectureJobService.LeaseLostException();
      }

      // Provider work is finished before the canonical publication transaction begins. Terminalize
      // both exact-route contexts first so a crash after DB publication cannot leave spend-capable
      // contexts orphaned.
      jobs.completeContexts(preparedJob, lease.owner());
      contextsClaimed.set(false);
      ProjectArchitectureJobPublisher.Published published =
          publisher.promote(lease, prepared.digest());
      // No workspace/customer-visible side effect occurs before the DB promotion commits.
      try {
        publisher.publishWorkspace(preparedJob.projectId(), published);
      } catch (Exception mirrorFailure) {
        log.warn(
            "architecture workspace mirror deferred job={} errorClass={}",
            preparedJob.id(),
            mirrorFailure.getClass().getSimpleName());
      }
      try {
        publisher.prune(published);
      } catch (Exception retentionFailure) {
        log.warn(
            "architecture retention deferred job={} errorClass={}",
            preparedJob.id(),
            retentionFailure.getClass().getSimpleName());
      }
      try {
        staleness.invalidate(preparedJob.projectId());
      } catch (Exception invalidationFailure) {
        log.warn(
            "architecture staleness invalidation deferred job={} errorClass={}",
            preparedJob.id(),
            invalidationFailure.getClass().getSimpleName());
      }
      log.info(
          "architecture job succeeded job={} project={} version={} quality={}",
          preparedJob.id(),
          preparedJob.projectId(),
          published.architecture().getVersion(),
          staged.qualityStatus());
    } catch (ProjectArchitectureJobService.LeaseLostException leaseFailure) {
      log.warn(
          "architecture job lease/cancellation/deadline fence stopped job={} token={}",
          lease.job().id(),
          lease.fencingToken());
    } catch (Exception failure) {
      ProjectArchitectureJobStore.Status status;
      if (executionStarted.get()) {
        status =
            store.needsConfirmation(lease, errorCode(failure), safeMessage(failure))
                ? ProjectArchitectureJobStore.Status.NEEDS_CONFIRMATION
                : store
                    .get(lease.job().id())
                    .map(ProjectArchitectureJobStore.Job::status)
                    .orElse(ProjectArchitectureJobStore.Status.FAILED);
      } else {
        status =
            store.failOrRequeue(
                lease,
                errorCode(failure),
                safeMessage(failure),
                properties.getMaxAttempts(),
                Duration.ofSeconds(Math.min(60, 10L * lease.job().attemptCount())));
      }
      log.warn(
          "architecture job did not complete job={} project={} status={} code={}",
          lease.job().id(),
          lease.job().projectId(),
          status,
          errorCode(failure));
    } finally {
      if (heartbeat != null) heartbeat.cancel(false);
      if (contextsClaimed.get() && contextJob.get() != null) {
        try {
          jobs.failContexts(contextJob.get(), lease.owner(), "ARCHITECTURE_JOB_TERMINATED");
        } catch (Exception contextFailure) {
          log.warn(
              "architecture execution-context cleanup deferred job={} errorClass={}",
              lease.job().id(),
              contextFailure.getClass().getSimpleName());
        }
      }
    }
  }

  private static String errorCode(Exception failure) {
    if (failure instanceof RepoArchitectureGenerator.ProviderOutputIncompleteException) {
      return "PROVIDER_OUTPUT_LIMIT";
    }
    String simple = failure.getClass().getSimpleName();
    return simple.isBlank()
        ? "ARCHITECTURE_REFRESH_FAILED"
        : simple.replaceAll("[^A-Za-z0-9_]", "_").toUpperCase(Locale.ROOT);
  }

  private static String safeMessage(Exception failure) {
    if (failure instanceof RepoArchitectureGenerator.ProviderOutputIncompleteException) {
      return "Provider output ended before the architecture was complete.";
    }
    if (failure instanceof org.springframework.web.server.ResponseStatusException status
        && status.getStatusCode().is4xxClientError()) {
      String reason = status.getReason();
      if (reason != null
          && !reason.toLowerCase(Locale.ROOT).contains("credential")
          && !reason.toLowerCase(Locale.ROOT).contains("token")
          && !reason.toLowerCase(Locale.ROOT).contains("secret")) {
        return reason;
      }
    }
    return "Map refresh could not be completed. Retry or check repository access.";
  }

  private static String hostLabel() {
    return System.getenv().getOrDefault("HOSTNAME", "local");
  }

  private void requireLive(AtomicBoolean heartbeatLost, ProjectArchitectureJobStore.Lease lease) {
    if (heartbeatLost.get() || !store.isLive(lease)) {
      heartbeatLost.set(true);
      throw new ProjectArchitectureJobService.LeaseLostException();
    }
  }
}

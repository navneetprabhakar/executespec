package com.navneet.executespec.export;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

/** Polls/claims V289 rows; a database fencing token, not the executor, owns publication rights. */
@Component
@Slf4j
public class ProjectExportWorker {

  private final ProjectExportJobStore store;
  private final ProjectExportBundleWriter bundleWriter;
  private final ProjectExportObjectStore objectStore;
  private final ProjectExportProperties properties;
  private final ThreadPoolTaskExecutor executor;
  private final MeterRegistry meterRegistry;
  private final AtomicInteger inFlight = new AtomicInteger();
  private final String workerId = hostLabel() + ":" + UUID.randomUUID();

  public ProjectExportWorker(
      ProjectExportJobStore store,
      ProjectExportBundleWriter bundleWriter,
      ProjectExportObjectStore objectStore,
      ProjectExportProperties properties,
      @Qualifier("projectExportExecutor") ThreadPoolTaskExecutor executor,
      MeterRegistry meterRegistry) {
    this.store = store;
    this.bundleWriter = bundleWriter;
    this.objectStore = objectStore;
    this.properties = properties;
    this.executor = executor;
    this.meterRegistry = meterRegistry;
  }

  @Scheduled(fixedDelayString = "${project.exports.poll-interval-millis:2000}")
  public void poll() {
    store.finishAbandonedCancellations();
    store.failExhaustedStale(properties.getMaxAttempts());
    int available = Math.max(0, parallelism() - inFlight.get());
    if (available > 0) {
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
        } catch (RejectedExecutionException rejected) {
          inFlight.decrementAndGet();
          store.failOrRequeue(
              lease.get(),
              "EXECUTOR_SATURATED",
              properties.getMaxAttempts(),
              Duration.ofSeconds(10));
        }
      }
    }
    cleanupExpired();
  }

  void process(ProjectExportJobStore.Lease lease) {
    Timer.Sample timer = Timer.start(meterRegistry);
    Path staging = null;
    ProjectExportObjectStore.Artifact artifact = null;
    try {
      staging = objectStore.createStagingFile(lease.job().id(), lease.fencingToken());
      ProjectExportJobStore.Lease stableLease = lease;
      bundleWriter.write(stableLease.job(), staging, () -> checkpoint(stableLease));
      checkpoint(lease);
      artifact =
          objectStore.publish(
              lease.job().id(),
              lease.fencingToken(),
              lease.job().projectId(),
              staging,
              lease.job().contentType());
      staging = null;
      checkpoint(lease);

      boolean completed =
          store.complete(
              lease,
              artifact.objectKey(),
              artifact.sizeBytes(),
              artifact.sha256(),
              Instant.now().plus(properties.getRetentionHours(), ChronoUnit.HOURS));
      if (!completed) {
        objectStore.delete(artifact.objectKey());
        throw new LeaseLostException();
      }
      outcome("SUCCEEDED");
      log.info(
          "project export completed job={} project={} bytes={} attempt={}",
          lease.job().id(),
          lease.job().projectId(),
          artifact.sizeBytes(),
          lease.job().attemptCount());
    } catch (CancellationRequestedException cancelled) {
      deleteQuietly(artifact);
      store.finishCancellation(lease);
      outcome("CANCELLED");
    } catch (LeaseLostException lost) {
      deleteQuietly(artifact);
      outcome("LEASE_LOST");
      log.warn("project export lease lost job={} token={}", lease.job().id(), lease.fencingToken());
    } catch (Exception failure) {
      deleteQuietly(artifact);
      if (store.cancellationRequested(lease)) {
        store.finishCancellation(lease);
        outcome("CANCELLED");
      } else {
        ProjectExportJobStore.Status result =
            store.failOrRequeue(
                lease,
                errorCode(failure),
                properties.getMaxAttempts(),
                Duration.ofSeconds(Math.min(120, 10L * lease.job().attemptCount())));
        outcome(result.name());
      }
      log.error(
          "project export failed job={} project={} code={} attempt={}",
          lease.job().id(),
          lease.job().projectId(),
          errorCode(failure),
          lease.job().attemptCount());
    } finally {
      if (staging != null) {
        try {
          Files.deleteIfExists(staging);
        } catch (IOException ignored) {
          // Private staging cleanup is best effort.
        }
      }
      timer.stop(Timer.builder("executespec.project.export.duration").register(meterRegistry));
    }
  }

  private void checkpoint(ProjectExportJobStore.Lease lease) {
    if (store.cancellationRequested(lease)) throw new CancellationRequestedException();
    if (!store.renew(lease, Duration.ofSeconds(properties.getLeaseSeconds()))) {
      throw new LeaseLostException();
    }
  }

  private void cleanupExpired() {
    for (ProjectExportJobStore.Job candidate : store.expiryCandidates(20)) {
      var expiring = store.beginExpiry(candidate.id());
      if (expiring.isEmpty() || expiring.get().objectKey() == null) continue;
      try {
        objectStore.delete(expiring.get().objectKey());
        store.finishExpiry(expiring.get().id(), expiring.get().objectKey());
        outcome("EXPIRED");
      } catch (Exception failure) {
        Counter.builder("executespec.project.export.expiry_failures")
            .register(meterRegistry)
            .increment();
        log.warn("project export expiry cleanup failed job={}", expiring.get().id());
      }
    }
  }

  private void deleteQuietly(ProjectExportObjectStore.Artifact artifact) {
    if (artifact == null) return;
    try {
      objectStore.delete(artifact.objectKey());
    } catch (IOException ignored) {
      log.warn("could not remove unpublished export artifact key={}", artifact.objectKey());
    }
  }

  private void outcome(String outcome) {
    Counter.builder("executespec.project.export.outcome")
        .tag("outcome", outcome)
        .register(meterRegistry)
        .increment();
  }

  private int parallelism() {
    return Math.max(1, Math.min(properties.getMaxParallelJobs(), 8));
  }

  private static String errorCode(Exception failure) {
    if (failure instanceof ProjectExportBundleWriter.ExportLimitExceededException) {
      return "EXPORT_TOO_LARGE";
    }
    if (failure instanceof IllegalArgumentException) return "PROJECT_NOT_FOUND";
    if (failure instanceof IOException) return "EXPORT_IO_FAILED";
    return "EXPORT_INTERNAL_FAILED";
  }

  private static String hostLabel() {
    String host = System.getenv("HOSTNAME");
    return host == null || host.isBlank() ? "local" : host.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  static final class CancellationRequestedException extends RuntimeException {}

  static final class LeaseLostException extends RuntimeException {}
}
